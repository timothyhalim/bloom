# Bloom plan

Free, open-source (GPL-3.0) Android photo editor. Everything that can run on the phone is free; an optional paid cloud tier sells advanced generative AI. The UI is specified in [`design/UI.md`](../design/UI.md) (v3.3) with a clickable [`design/mockup.html`](../design/mockup.html).

## Status

| Milestone | State |
|---|---|
| M0 Skeleton | Done |
| M1 Edit engine + Color | Done |
| M2a Masks: brush, gradient, invert, overlay, blend modes | Done |
| M2b Masks: color range, luminance range, feather, loupe, reorder, copy/clear | Done (not yet tested on a phone) |
| M1.5 Curves | Done (not yet tested on a phone) |
| M2.5a Recover: 16-bit sources kept, smooth banding on 8-bit photos | Done (not yet tested on a phone) |
| M2.5c part 1: Ultra HDR photos (open as in the gallery; darkening edits reveal the hidden highlight range) | Done (not yet tested on a phone) |
| M2.5c part 2: 16-bit PNG export (Export menu: JPEG or 16-bit PNG; drawn upright; ~55 MB at 29 MP) | Done (not yet tested on a phone) |
| M2.5c part 3: camera raw (CR3, NEF, ARW, DNG and the rest LibRaw 0.22 reads) via vendored LibRaw (LGPL-2.1) in `app/src/main/cpp`; linear 16-bit, camera white balance, brightest 1% kept above white for Exposure to recover; "Open file" for CR3, which the photo picker hides. Opens with Lightroom's "Medium Contrast" tone curve (values above white pass through); export shows a % progress ring; CR3 exports lose EXIF (ExifInterface can't read it); JPEG-XL DNGs unsupported | Done (not yet tested on a phone) |
| Everything below | Not started |

## Features

**Core idea:** non-destructive layers. Every tool makes a layer; each layer has its own mask built from ordered add/remove steps (Brush, Subject, Background, Gradient, Color range, Luminance range) plus Invert. Masks are parameters, so every step stays editable.

| Area | v1 | Later |
|---|---|---|
| Layers | Stack with opacity, blend modes (Normal, Multiply, Screen, Overlay, Soft light), hide, reorder, undo/redo | Layer groups |
| Masks | The six step types above, feather, invert, copy from another layer | Select sky / person / hair |
| Color | Exposure, contrast, highlights, shadows, temp, tint, saturation, vibrance, curves | HSL per color, LUTs, presets |
| Detail | Gaussian and lens blur, unsharp mask, clarity | Motion/radial blur, depth blur |
| Liquify | Push, bloat, pinch, twirl, restore | Face-aware liquify |
| Crop | Crop, straighten, rotate, perspective, scale | |
| AI (free, on device) | Subject/background masks, object eraser, upscale 2×/4×, background replace (matting + your image) | Prompted fill on flagships (M10) |
| AI (own server / own key) | IOPaint, ComfyUI, fal key; natural-language edits via llama.cpp, LiteLLM, any OpenAI-compatible endpoint | |
| AI (paid, Bloom Cloud → fal) | Prompted generative fill, creative upscale, text-prompted background + relighting | |
| Range / HDR | Dithered 8-bit output, 16-bit sources, smooth banding (done) | Ultra HDR gain maps, DNG, AI "Recover" (see M2.5) |
| Basics | Hold to compare, navigator when zoomed, export JPEG with EXIF; **done:** WebP (with EXIF), HEIC and AVIF (where the phone encodes them), 16-bit PNG export | saved projects to reopen and keep editing (M7) |

## Budgets ("lightweight")

- Base APK < 25 MB; models downloaded on demand, < 60 MB total.
- Target: 4 GB RAM, Snapdragon 6-series or similar, Android 8+ (API 26), GLES 3.0.
- Peak RAM editing 12 MP < 400 MB; 48 MP input must export without running out of memory.
- Slider preview ≥ 30 fps. Tap-select < 500 ms first, < 100 ms after. Eraser < 2 s. 12 MP export < 5 s.

## Milestones

Each ends with a measurable exit test on a real phone. Times are for one developer.

| # | Scope | Exit test |
|---|---|---|
| **M0** Skeleton (done) | Open from gallery, GL preview with pan/zoom, tiled export with EXIF | 48 MP opens and exports, EXIF kept |
| **M1** Edit engine + Color (done) | Layer stack, undo/redo, compare, Color layer on a 16-bit float GPU pipeline, layer rail | 8 stacked layers ≥ 30 fps; 50 undo/redo steps stable |
| **M1.5** Curves (done) | RGB/R/G/B curves in the Color tool | Curves match a reference LUT within 1 level |
| **M2** Masks (done) | Steps: brush, gradient, color range, luminance range; feather, invert, overlay, loupe, reorder, copy/clear; blend modes | Brushing < 16 ms/frame; no seams between export tiles |
| **M2.5** Recover (range) | **a (done):** 16-bit/10-bit sources stay deep; GPU deband on 8-bit photos, on by default, switch in the Photo layer bubble. **Tried and dropped:** classical highlight rebuild (turned blown sunset skies pink) and lift-driven shadow denoise (no visible gain at edit size); AI HDR with HDRUNet (MIT) and ExpandNet (BSD), scored on photos clipped on purpose: HDRUNet fixed some blown suns but made fog worse and left a cyan halo on a real blown sunset, ExpandNet left blotches, and neither removed banding. Masked-HDR (Santos 2020, BSD-3) was the most consistent and looked natural on a real blown sunset; HDRCNN (BSD-3) was close but left artifacts near the sun. No model removes banding (they only touch near-clipped pixels). **Done:** Ultra HDR, 16-bit PNG export, camera raw via LibRaw. **Later (after M3's model downloads):** Masked-HDR as an optional "Recover highlights" download: 51.5M params (~100 MB fp16, ~50 MB int8), so check int8 quality, TFLite conversion and phone speed at ~1 MP first; confirm the weights' license with the author | Pushed gradients show no banding on the test set |
| **M3** AI selection (~3 wk) | **Done (emulator):** Subject/Background mask steps with IS-Net (Apache-2.0, 44 MB with 8-bit weights, `models/`) on MNN 3.6.2 (Apache-2.0, submodule, OpenCL → Vulkan → CPU); model listed in `assets/models.json` (URL + SHA-256) and downloaded on first use; mask found once per photo from the preview, edges snapped to the photo by joint bilateral upsampling, export tiles padded. MediaPipe MagicTouch dropped (MNN converter crashes on it). Pins (post-freeze change): tapping the photo on a selected Subject step adds include/exclude pins and switches that step to MobileSAM (Apache-2.0, 20 MB with 8-bit weights; encoder once per photo, decoder per include pin, masks merged). Tried replacing IS-Net with U²-Netp auto-pins for SAM to save 43 MB: worse on fine shapes (sunset tree) and multi-object scenes, so IS-Net stays. **Open:** `models.json` URL needs the public repo path; phone timing; IoU on a labelled set; mask from the original photo, not the composite below as the design says | Timing budgets met; IoU ≥ 0.85 on 50 test photos |
| **M4** Detail (~1–2 wk) | **Done (emulator):** Detail tool with Blur and Sharpen layers. Gaussian blur (separable, mip-sampled, mask-weighted so a Background blur picks up no subject color); Lens blur driven by Depth Anything V2 Small (Apache-2.0, 27 MB with 8-bit weights, downloaded on first use): tap to focus, Amount, Range, Highlights (bokeh bloom), gather with no background bleed over sharp foreground; Motion blur (angle) and Zoom blur (tap the centre), 48 mip-sampled taps along the streak. Sharpen (unsharp amount, radius) and Clarity (midtone local contrast). Depth range mask step (post-freeze change): the Luminance step over the depth map instead of brightness (Near / Middle / Far presets, 4-handle strip), edges snapped to the photo like subject masks. Export tiles overlap by the blur reach, aligned to 64 px for mipmaps. **Open:** aperture shapes (polygon bokeh needs highlight sprites; a gather with 64 taps draws them round); a 24 MP lens-blur export took ~5 min on the emulator, so measure on a phone; Sharpen radius is tuned for ~3000 px tall photos | Background blur ≥ 30 fps; 12 MP export < 5 s |
| **M4.5** Crop | **Done (emulator):** Crop tool as a document transform after all layers (layers, masks, pins and depth keep the uncropped photo's coordinates, so re-cropping never loses edits). Corner and edge handles, aspect presets (Free, Original, 1:1, 4:5, 3:2, 16:9; tap again to turn), Straighten ±45°, Vertical and Horizontal keystone, rotate 90°, flip. The frame is the box around the warped photo, so all of it is reachable; straightening, keystone and aspect presets set the crop to the largest that fits (position searched, nearest the centre on ties). Preview and touch go through the same projective matrix, so brushes, pins and focus taps land under the finger. Export tiles cover the output and resample with an n×n box filter (no seams, exact averaging when shrinking); Size 100 / 50 / 25% in the Export menu. **Open:** custom long-edge size; cropped or scaled JPEGs are written upright (orientation tag reset) | Round-trip crop/rotate keeps full resolution |
| **M5** Liquify (~2 wk) | **Done (emulator):** Liquify layer kind (maskable, reorderable, undoable): Push, Bloat, Pinch, Twirl, Restore as icon buttons; Size, with Strength, Softness and Twirl direction under More (one box like the mask brush). Bloat, Pinch, Twirl and Restore keep working while the finger rests. Brush ring shows softness (inner dashed ring); moving Size or Softness shows the ring mid-photo (mask brush too). Strokes replay into a 512-cell offset grid (CPU, only new dabs while painting; dirty rows uploaded as RG16F); the layer reads each pixel from its offset, exact where the offset is zero, so Restore returns the original. Export tiles overlap by the largest offset. **Open:** 30 fps unverified (the emulator renders any layer drag at ~150-200 ms, Color included; the grid update itself is 13-27 ms on a debug emulator build) | ≥ 30 fps; Restore returns exact original pixels |
| **M6** AI eraser (~3 wk) | **Done (emulator):** AI tool: paint what to remove (mask brush with Size, Softness, Flow), Erase runs Big LaMa (Apache-2.0, 105 MB with half-precision weights; 8-bit breaks its Fourier layers; two parts on GitHub) on a square crop of 2× the hole's long side (min 512) scaled to 512, and keeps the fill in a Retouch layer at the bottom of the stack (maskable, undoable; every other layer still applies over it). The model is freed after each run. MI-GAN was tried first and dropped: large holes came out smeared. Brush ring: solid where the brush is at full strength, dashed where it fades, resting at the last touch. Tool settings are kept between sessions. All MNN models now run as Modules on one runtime with one saved GPU program cache. **Measured:** ~9–13 s on the emulator CPU; phone GPU time not measured yet | < 2 s; 40/50 removals rated acceptable |
| **M7** Hardening (~2 wk) | Projects: autosave layers + masks as JSON with a copy of the original, Recent projects on the start screen (later: shareable `.bloom` file, saved undo history) — **done**: `Project.kt`, files/projects/<id>/ with original.<ext>, doc.json (version 1), patch PNGs, thumb.jpg; 2 s autosave + on stop; Home grid (long-press deletes); Back asks Keep project / Discard / Cancel; **done (emulator):** crash reports with ACRA (dialog, then share sheet; no server; stack trace, versions, model, memory only); low memory: models freed on trim (228 to 67 MB native heap measured), clear out-of-memory messages for export and model runs, preview already capped at 2048 px; accessibility: 48 dp touch targets, slider name + value, font scale 2.0 fixed (labels and rows grow), no unlabeled clickable nodes in a uiautomator sweep; release: R8 + resource shrinking (APK 75 to 22.5 MB), signing from gitignored keystore.properties, docs/RELEASE.md and docs/store/listing.md. **Open:** upload key and Play Console app (docs/RELEASE.md), TalkBack pass on a phone | ≥ 99.5% crash-free sessions over 20 testers; size budget met |
| **M8** Providers + bring-your-own (~2 wk) | `AiProvider` interface; on-device, IOPaint, ComfyUI, fal (own key), OpenAI-compatible adapters; "Ask" natural-language edits | Same erase works on device, IOPaint and fal; "make it warmer" → correct layers ≥ 8/10 with local llama.cpp |
| **M8.5** Free advanced (~3 wk) | Real-ESRGAN compact upscale (tiled, ≤ 24 MP), RVM matting cutout, background swap with color match + light wrap | 3 → 12 MP upscale < 10 s, < 400 MB; no hair halo in 40/50 portraits |
| **M10** On-device diffusion (optional, ~3–4 wk) | SD 1.5 inpainting, downloaded only on eligible devices | 512 px fill < 15 s on Snapdragon 8 Gen 2; never offered on 4 GB devices |

## Tech decisions

- **Kotlin + Jetpack Compose** (Material 3, dark first). **OpenGL ES 3.0** for rendering, not AGSL: AGSL needs Android 13 and has no offscreen or compute support.
- **Pipeline:** one GPU pass per visible layer, ping-pong RGBA16F targets (RGBA8 fallback). Masks are R8 textures drawn in upright image coordinates, so preview and rotated export tiles match. Export renders 1024 px tiles in an offscreen EGL context, with overlap when masks are feathered; output is dithered to 8-bit.
- **Document model:** immutable snapshots of layer parameters; undo keeps 100 steps. The open tool edits a draft layer; ✓ commits it as one step.
- **ML runtime:** LiteRT (TFLite) with GPU delegate and CPU fallback; models downloaded on demand.
- **Build flavors:** `fdroid` (no Google libraries) and `play` (ML Kit, Play Billing).

## Models and licenses

| Use | Model | License |
|---|---|---|
| Tap select | MediaPipe Interactive Segmenter; MobileSAM / EfficientSAM | Apache-2.0 |
| Auto subject (play flavor) | ML Kit Subject Segmentation | Play Services, 0 MB |
| Eraser | Big LaMa (half-precision MNN, 105 MB; MI-GAN was tried first and smeared medium/large fills) | Apache-2.0 |
| Upscale | Real-ESRGAN `realesr-general-x4v3` | BSD-3 |
| Matting | RVM MobileNetV3 | GPL-3.0 (fine: the app is GPL) |
| Prompted fill (M10) | SD 1.5 inpainting | CreativeML OpenRAIL-M (use restrictions go in the Terms) |
| **Avoid** | EdgeSAM (non-commercial); SD-Turbo / SDXL-Turbo until their license is re-checked | |

App license is GPL-3.0; contributors sign a CLA. The backend may stay closed (AGPL if opened).

## AI providers

```kotlin
enum class Capability { SEGMENT, ERASE, GEN_FILL, UPSCALE, BG_REPLACE, EDIT_COMMAND }

interface AiProvider {
    val id: String
    val capabilities: Set<Capability>
    suspend fun run(cap: Capability, req: AiRequest): AiResult // mask | image patch | edit ops
}
```

| Adapter | Capabilities | Auth |
|---|---|---|
| `OnDevice` (default, free) | SEGMENT, ERASE, UPSCALE, BG_REPLACE | none |
| `BloomCloud` (paid, planned in a separate repo) | all, routed to fal (Runway only if its API adds editing endpoints) | Bloom account + credits |
| `Fal` (own key) | SEGMENT, ERASE, GEN_FILL, UPSCALE, BG_REPLACE | user's key |
| `OpenAiCompatible` (llama.cpp, LiteLLM, Ollama, OpenRouter) | EDIT_COMMAND (text only, never the photo) | user's URL + key |
| `IOPaint` / `ComfyUI` | ERASE, GEN_FILL, UPSCALE | user's URL |

The user picks a provider per capability in Settings.

**Rules:**
1. Never ship fal/Runway keys in the APK. Paid requests go app → Bloom backend → fal; the backend owns auth, credits and rate limits.
2. Bring-your-own keys go straight from the phone to the provider, encrypted with Android Keystore.
3. Cloud is opt-in per action, with a notice the first time a photo leaves the phone; declared in Play's Data Safety form.
4. Send only the crop and mask, not the whole photo, where the API allows.
5. Generative results get a Report button (Play AI-generated content policy); keep the provider's safety filter on in the paid tier.

## Design changes after the v3.3 freeze

Requested while testing M2a; `design/UI.md` still shows the frozen version.

- Chrome rows float over the photo; only the bottom bar reserves space, so the photo never moves when rows appear (also while zoomed or painting).
- Color parameters and mask step types are icon-only chips; the slider row and accessibility labels carry the names.
- Add/Remove is a round +/− button at the start of the settings row, not in the bar.
- A step's extra settings (Softness, Flow, Smoothness, Feather) open inside the same box as its main slider.
- Scrolling rows fade at the edge that has more content.
- The mask overlay (red tint and dashed edge) shows only in mask mode; the tool screen shows the plain result.
- Exposure reads in stops (EV, −3.0 to +3.0 in 0.1 steps), like a camera.
- Tool bars are icon-only; each tool's settings are one box (main row + More), its label naming what is selected ("Gaussian / Blur", "Push / Size"); scrolling rows also show ‹ › at the edges.

### Requested, not built yet (after M6)

- Rename layers (from the layer bubble).
- Swipe the layers button left / right to hide / show all adjustment layers at once (like Compare, but it stays).
- Layer groups: collect layers into a group; hide, show, reorder and set opacity for the group together.
- Save the work file and a start screen of recent files with thumbnails: already M7 (projects autosave + Recent projects); the user asked for it again, so keep it first in M7.
- More export types (e.g. WebP, HEIC/AVIF where the phone encodes them) next to JPEG and 16-bit PNG: **done**.
- Code: split `Layer`'s per-kind fields (values/curves, detail, warp, patch) into `Layer(…, effect: Effect)` with one sealed `Effect` subtype per kind. Do it before M7's project file format fixes the layer shape, or when a 6th kind arrives.
