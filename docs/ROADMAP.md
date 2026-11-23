# Implementation plan: M8, M8.5 and M10

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

### UI decisions (agreed 2026-11-23)
- **Bar:** mode icons like Liquify's tools (Erase, Fill, Upscale, Background, Ask); a mode shows only when some provider supports it. Each mode has the usual settings box ("Erase / Size").
- **Choosing a model:** the run button is split: tap runs; ▾ opens a menu of every provider *and model* that can run this mode ("Phone · LaMa", "My server · big-lama", "fal · flux-fill"…), the current one ticked, plus "AI settings…". The choice is remembered per mode; the default per mode is set in Settings. A small label under the run icon names where it runs.
- **Running remotely locks the AI tool** until the job ends, with live status in the settings box: "Uploading 40 %", "Queued, 3 ahead", "Step 12 / 30", "Downloading result", or the provider's error message, plus Cancel. Status comes from fal's queue status, ComfyUI's websocket progress, and elapsed time for IOPaint (no progress API).
- **Consent:** a sheet the first time a photo crop goes to a provider (what is sent, where, `Send` / `Not now` / `Use a different provider`, `Don't ask again for <provider>`); reset or hide cloud features in Settings › Privacy.
- **Settings page:** AI providers (one row per mode → provider + model; `+ Add provider` form: type, URL, key, `Test connection` which must pass and lists the modes and models found before Save), On-device models, Privacy, Appearance, About. Keys: Replace / Forget only, never shown again.
- **Ask:** the LLM turns a sentence into Bloom layers (Color parameters, masks from Subject / Background / Gradient / Luminance, Blur). Only the text and a summary of the current layers are sent, never the photo. Result is a checklist of layers; `Apply n layers` is one undo step. A vision option (send a small thumbnail for requests like "fix the white balance") is a later, opt-in addition.
- **Refine a layer with Ask:** an Ask icon in each layer's bubble opens the Ask field targeted at that layer ("a bit less orange", "only on the sky"). The LLM gets that layer's parameters and mask steps and may only return `SetParam` / mask-step ops for it; the change previews live and applies as one undo step.
- **Fill (prompted):** in M8 for bring-your-own providers; same plumbing as Erase plus a prompt field.
- Update `design/mockup.html` to the shipped design after M8.

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

## Bloom Cloud

Not a milestone of this repo: the paid cloud (backend, credits, billing) is planned in its own repository later. When its API exists, this app gets a `BloomCloud` provider adapter like the others.

---

## M10: On-device diffusion (optional, ~3–4 weeks)

**Goal:** prompted fill without a server on flagship phones.

### Steps
1. **Feasibility spike first (3 days)** (desktop part done, see [`docs/eval/m10-spike.md`](eval/m10-spike.md): go with LCM-LoRA 4 steps; plain 20 steps is about 55 s): SD 1.5 inpainting (CreativeML OpenRAIL-M; use restrictions go into the Terms) converted to MNN, which has a diffusion example; measure 512 px, 20 steps on a Snapdragon 8 Gen 2 class phone. Stop here if > 15 s or > 3 GB RAM; consider an LCM/turbo distilled variant (check license) for 4–8 steps.
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
4. M10 starts with the spike; continue only if the numbers hold.

Each milestone also adds its rows to `docs/FEATURES.md` with test names.
