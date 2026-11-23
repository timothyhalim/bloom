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
| Basics | Hold to compare, navigator when zoomed, export JPEG with EXIF | PNG/WebP/16-bit export, saved projects to reopen and keep editing (M7) |

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
| **M4** Detail (~1–2 wk) | **Done (emulator):** Detail tool with Blur and Sharpen layers. Gaussian blur (separable, mip-sampled, mask-weighted so a Background blur picks up no subject color); Lens blur driven by Depth Anything V2 Small (Apache-2.0, 27 MB with 8-bit weights, downloaded on first use): tap to focus, Amount, Range, Highlights (bokeh bloom), gather with no background bleed over sharp foreground; Sharpen (unsharp amount, radius) and Clarity (midtone local contrast). Depth range mask step (post-freeze change): the Luminance step over the depth map instead of brightness (Near / Middle / Far presets, 4-handle strip), edges snapped to the photo like subject masks. Export tiles overlap by the blur reach, aligned to 64 px for mipmaps. **Open:** aperture shapes (polygon bokeh needs highlight sprites; a gather with 64 taps draws them round); a 24 MP lens-blur export took ~5 min on the emulator, so measure on a phone; Sharpen radius is tuned for ~3000 px tall photos | Background blur ≥ 30 fps; 12 MP export < 5 s |
| **M4.5** Crop | Crop, straighten, rotate, perspective, scale (document transform, no layer) | Round-trip crop/rotate keeps full resolution |
| **M5** Liquify (~2 wk) | Five brushes on a displacement grid, full-res export | ≥ 30 fps; Restore returns exact original pixels |
| **M6** AI eraser (~3 wk) | Brush or tap area, MI-GAN inpaint on a 512 px crop, blend back, CPU fallback | < 2 s; 40/50 removals rated acceptable |
| **M7** Hardening (~2 wk) | Projects: autosave layers + masks as JSON with a copy of the original, Recent projects on the start screen (later: shareable `.bloom` file, saved undo history); crash reporting, low-memory handling, accessibility, Play internal track | ≥ 99.5% crash-free sessions over 20 testers; size budget met |
| **M8** Providers + bring-your-own (~2 wk) | `AiProvider` interface; on-device, IOPaint, ComfyUI, fal (own key), OpenAI-compatible adapters; "Ask" natural-language edits | Same erase works on device, IOPaint and fal; "make it warmer" → correct layers ≥ 8/10 with local llama.cpp |
| **M8.5** Free advanced (~3 wk) | Real-ESRGAN compact upscale (tiled, ≤ 24 MP), RVM matting cutout, background swap with color match + light wrap | 3 → 12 MP upscale < 10 s, < 400 MB; no hair halo in 40/50 portraits |
| **M9** Bloom Cloud (~3–4 wk) | Backend proxy, accounts, credit ledger, Play Billing, report button, privacy policy | Purchase → fill → balance deducted, no double charge on retry; no provider key in the APK (`apktool`) |
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
| Eraser | MI-GAN | MIT |
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
| `BloomCloud` (paid) | all, routed to fal (Runway only if its API adds editing endpoints) | Bloom account + credits |
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
