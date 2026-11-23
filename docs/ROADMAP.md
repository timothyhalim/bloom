# Implementation plan: M8 to M10

Follows `docs/PLAN.md` (milestones, provider rules) and the decisions there. Each milestone: goal, steps in build order, tests, done-when, open questions. Estimates are for one developer with the current codebase. Every step ends with a commit once its tests pass.

Rules that hold throughout:
- No provider key in the APK. Bring-your-own keys live on the phone, encrypted with Android Keystore; paid requests go through the Bloom backend.
- A photo leaves the phone only on an explicit action, with a one-time notice per provider. Send the crop and mask, not the whole photo, when the API allows.
- Every AI result lands as a normal layer (Retouch patch, mask step, Color layer…), so undo, masks, projects and export already work for it.
- Free models only for the free tier; licenses compatible with GPL-3.0 and commercial use.

---

## M8: Providers and "Ask" (~2 weeks)

**Goal:** the same AI action can run on the phone, on the user's own server, or with the user's own cloud key; plus natural-language edits.

### Steps
1. **`AiProvider` interface** (`ai/AiProvider.kt`), as sketched in PLAN.md: `Capability` (SEGMENT, ERASE, GEN_FILL, UPSCALE, BG_REPLACE, EDIT_COMMAND), `AiRequest` (photo crop as PNG bytes, mask bytes, prompt, size), `AiResult` (patch bitmap + rect, mask, or edit ops). Progress and cancel via coroutine.
2. **`OnDevice` adapter**: wraps today's `Segment.subject/pinned/erase`. The eraser and subject code paths in `EditorScreen` call the provider instead of `Segment` directly. No behaviour change; existing tests must pass.
3. **Settings screen** (new, from the Home top-right icon): per capability a provider picker; per provider its URL / key fields, a "Test connection" button, and a "Forget key" button. Keys stored with `EncryptedSharedPreferences` replacement: Android Keystore AES-GCM key + ciphertext in prefs (no Jetpack Security dependency, it's deprecated).
4. **HTTP client**: `HttpURLConnection` (no OkHttp dependency) with timeouts, multipart upload, JSON via kotlinx.serialization (already in). One retry on network errors, never on 4xx.
5. **`IOPaint` adapter** (ERASE, GEN_FILL, UPSCALE): `POST /api/v1/inpaint` (image + mask base64, model chosen on the server), `/api/v1/run_plugin_gen_image` for upscale. Verify the current IOPaint API against a local `iopaint start` before coding.
6. **`ComfyUI` adapter**: user supplies a workflow JSON exported in API format with two marked nodes (input image/mask, output). Upload via `/upload/image`, queue `/prompt`, poll `/history/<id>`, download `/view`. Ship two sample workflows in `docs/comfyui/` (inpaint, upscale).
7. **`Fal` adapter** (own key): erase/inpaint, upscale, background removal endpoints (pick current model ids from fal's docs at build time; keep ids in one table). Queue API with polling.
8. **`OpenAiCompatible` adapter** (EDIT_COMMAND): `POST /v1/chat/completions` with a system prompt describing Bloom's edit ops and a JSON schema; the photo is never sent, only the text and a small summary of the current layers (kinds, parameters).
9. **Edit ops** (`ai/EditOps.kt`): a small serializable list: `AddColorLayer(params, mask?)`, `SetParam(layerId, param, value)`, `AddMaskStep(layerId, step)` (Subject / Background / Gradient / Luminance only), `Blur(type, amount)`. Validated and clamped before applying; applied as one undo step. Unknown ops rejected with a message.
10. **AI tool modes**: the AI tool gets its mode menu from the spec (Erase, Fill, Upscale, Replace bg, Ask), each mode showing which provider will run it; split run button (run / choose provider). Fill and Ask hidden until a provider supports them.
11. **First-send notice** per provider ("This sends the selected area to <host>"), remembered.
12. **Ask UI**: a text field in the AI tool; result preview as the new layers in draft; ✓ applies, Cancel discards.

### Tests
- Unit: request/response JSON for each adapter against recorded fixtures (`src/test/resources/ai/*`), edit-ops parsing, clamping and rejection, key encryption round-trip (instrumented, Keystore needs a device).
- Fake server: a tiny local HTTP server in instrumented tests (`ServerSocket` on 127.0.0.1) replaying fixtures for IOPaint, ComfyUI and fal adapters: success, 4xx, timeout, cancel.
- Ask: a fixed set of 10 prompts ("make it warmer", "darken the sky", "blur the background"…) run against a local llama.cpp (manual, scored), target ≥ 8/10 correct.
- Manual: the same erase on device, IOPaint (local PC) and fal (user's key) gives a patch layer.

### Done when
Same erase works on device, IOPaint and fal; "make it warmer" gives correct layers ≥ 8/10 with local llama.cpp; no key appears in logs or the project file.

### Open questions
- Which fal models for erase / upscale / background (cost vs quality)? Decide with a 10-photo comparison.
- Ask: is a 3–8 B local model good enough on the user's PC? If not, recommend a hosted OpenAI-compatible endpoint.

---

## M8.5: Free advanced AI on the phone (~3 weeks)

**Goal:** upscale, cut-out and background swap without any server.

### Steps
1. **Real-ESRGAN `realesr-general-x4v3`** (BSD-3): convert ONNX → MNN (fp16; test 8-bit weights like the other models, keep fp16 if it breaks). Tiled inference (e.g. 256 px tiles with 16 px overlap, feathered seams), CPU/GPU via the shared runtime. 2× = run 4× then downscale.
2. **Upscale as a document operation**: like Crop, it changes the output size, so it is applied at export (and to the preview at screen size). Store `Document.upscale: Int = 1` (1, 2, 4); the export path renders the layered result, then upscales tile by tile; cap at 24 MP output (warn above).
3. **RVM MobileNetV3** (GPL-3.0, fine): single-image matting (run with zero recurrent state), gives an alpha matte at photo resolution; refine edges with the existing joint bilateral upsampling. Exposed as a mask step "Person (matte)" or as an improvement to Subject for people.
4. **Background swap**: AI mode "Replace bg": pick an image (photo picker) or a solid/gradient; stored as a new layer kind `Background(image ref, fit, blur)` under a matte mask; color match (mean/variance transfer of the subject toward the new background, strength slider) and light wrap (blurred background bleeding into the subject edge, width slider). Project format gets the background image copied into the project folder.
5. **Models in `models.json`** with SHA-256 and multi-part URLs, downloaded on first use, freed after use (like LaMa).

### Tests
- GPU tests: upscale tile seams invisible (tile vs full on a pattern), 2× output size exact; matte of a synthetic subject; background layer renders under/over correctly; color match moves means as expected.
- Project round-trip with a background layer and upscale setting.
- Manual: 40/50 portraits without a hair halo (score sheet in `docs/eval/matting.md`), 3 → 12 MP upscale time and peak memory.

### Done when
3 → 12 MP upscale < 10 s and < 400 MB on a mid-range phone; no hair halo in 40/50 portraits.

### Open questions
- Upscale preview: show at screen size (fast) or a zoomed 1:1 crop on demand? Recommend crop on demand.
- RVM vs MODNet (Apache-2.0) quality on stills: compare on 20 portraits before committing.

---

## M9: Bloom Cloud (~3–4 weeks)

**Goal:** paid generative fill, creative upscale and background generation, with no provider key on the phone.

### Steps
1. **Backend** (separate repo, closed or AGPL): small HTTP service (Kotlin/Ktor or Go) + Postgres. Endpoints: `POST /v1/auth` (Google sign-in token → session), `GET /v1/balance`, `POST /v1/jobs` (capability, crop, mask, prompt; idempotency key), `GET /v1/jobs/<id>`, `POST /v1/report`. Calls fal server-side; stores nothing but job metadata (images deleted after delivery, max 24 h).
2. **Credit ledger**: append-only table (grant, purchase, spend, refund). A job reserves credits, commits on success, refunds on provider failure. The idempotency key makes retries charge once.
3. **Play Billing** (play flavor only): consumable credit packs; purchase token verified server-side with the Play Developer API, then credits granted; acknowledged after the grant.
4. **App**: `BloomCloud` adapter (all capabilities), account section in Settings (sign in, balance, buy, sign out, delete account), cost shown on the run button, Report button on generated results.
5. **Safety**: provider safety filter on; prompt filter for obvious abuse; rate limits per account; abuse report queue.
6. **Legal and store**: privacy policy and terms (OpenRAIL-M use restrictions if SD is used server-side), Play Data Safety form, AI-generated content policy (report button), account deletion flow (required by Play).
7. **fdroid flavor**: no billing, no Google sign-in; Bloom Cloud hidden (bring-your-own providers still there).

### Tests
- Backend: unit tests for the ledger (reserve/commit/refund, idempotent retry, concurrent jobs), integration tests with a fake fal.
- App: billing flow with Play's test cards on the internal track; `apktool d` the release APK and grep for keys (none).
- Load: 50 concurrent jobs without double charges.

### Done when
Purchase → fill → balance deducted, no double charge on retry; no provider key in the APK.

### Open questions (user decisions)
- Hosting (Fly.io, Cloud Run, a VPS?) and budget.
- Pricing: credits per action; free monthly credits?
- Sign-in: Google only, or also email link?

---

## M10: On-device diffusion (optional, ~3–4 weeks)

**Goal:** prompted fill without a server on flagship phones.

### Steps
1. **Feasibility spike first (3 days)**: SD 1.5 inpainting (CreativeML OpenRAIL-M; use restrictions go into the Terms) converted to MNN, which has a diffusion example; measure 512 px, 20 steps on a Snapdragon 8 Gen 2 class phone. Stop here if > 15 s or > 3 GB RAM; consider an LCM/turbo distilled variant (check license) for 4–8 steps.
2. **Eligibility**: offered only with ≥ 8 GB RAM and a supported GPU; never on 4 GB devices. Download (~1–2 GB) only after an explicit opt-in with the size shown.
3. **Pipeline**: crop around the mask (like the eraser), text encoder → UNet loop with cancel and progress → VAE decode; result as a Retouch patch.
4. **Fill mode** in the AI tool uses it when chosen as the provider for GEN_FILL.

### Tests
- Instrumented: deterministic seed gives a stable output hash on the same device; cancel stops within one step; memory freed after.
- Manual: 512 px fill time on the target phone.

### Done when
512 px fill < 15 s on Snapdragon 8 Gen 2; never offered on 4 GB devices.

---

## Order and checkpoints

1. M8 steps 1–2 (interface + on-device, no behaviour change) → commit.
2. M8 settings + HTTP + IOPaint → test with a local IOPaint → commit. Then ComfyUI, fal, Ask, one commit each.
3. M8.5 upscale → matting → background swap, one commit each.
4. M9 needs the user's decisions (hosting, pricing, sign-in) before starting.
5. M10 starts with the spike; continue only if the numbers hold.

Each milestone also adds its rows to `docs/FEATURES.md` with test names.
