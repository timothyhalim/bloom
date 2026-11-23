# M8.5 on-device models: upscale and matting

Prepared 2026-11-24 on desktop (Ryzen 9 9950X3D, MNN 3.6.2 Python package, onnxruntime 1.32, torch 2.15 CPU). No device runs yet.

## Result

| Key (proposed) | File | Size | Use |
|---|---|---|---|
| `upscale` | `models/realesr_x4v3_f16.mnn` | 2,470,880 B | Real-ESRGAN general x4v3, 4× upscale, fp16 weights |
| `matting` | `models/rvm_mobilenetv3_f16.mnn` | 7,556,104 B | RVM MobileNetV3, portrait alpha matte, fp16 weights |

Both are under 50 MB, so no split parts. 8-bit weight variants were converted and tested but not kept (see below; the size saving is 1–3 MB).

**Matting recommendation: RVM.** It beats MODNet on every test: lower error on the composites (edge-band MAE 0.041 vs 0.051), much more robust on hard real photos (MODNet lost the subject on the dark profile shot and on the group shot), and about 4× faster.

## Sources and licenses

| Model | License | Source | SHA-256 of download |
|---|---|---|---|
| Real-ESRGAN `realesr-general-x4v3.pth` | BSD-3-Clause | https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth | `8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292` |
| RVM `rvm_mobilenetv3_fp32.onnx` (official export) | GPL-3.0 | https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_mobilenetv3_fp32.onnx | `88d4531297118f595bf2fd60f6f566aec2e559393802d1f436c380f0cbbd2828` |
| MODNet `modnet_photographic_portrait_matting.onnx` (compared only, not shipped) | Apache-2.0 | ZHKKKe/MODNet official ONNX; official copy is on Google Drive, fetched from the mirror https://huggingface.co/DavG25/modnet-pretrained-models/resolve/main/models/modnet_photographic_portrait_matting.onnx | `07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9` |

There is no official ONNX for realesr-general-x4v3, so it was exported from the official `.pth` (key `params`) with an SRVGGNetCompact definition written from the published architecture, not the repo code:

```python
class SRVGG(nn.Module):  # num_feat 64, num_conv 32, upscale 4, act PReLU
    def __init__(s, nf=64, nc=32, up=4):
        super().__init__(); s.up = up
        b = [nn.Conv2d(3, nf, 3, 1, 1), nn.PReLU(nf)]
        for _ in range(nc): b += [nn.Conv2d(nf, nf, 3, 1, 1), nn.PReLU(nf)]
        b += [nn.Conv2d(nf, 3 * up * up, 3, 1, 1)]
        s.body = nn.ModuleList(b); s.ps = nn.PixelShuffle(up)
    def forward(s, x):
        y = x
        for m in s.body: y = m(y)
        return s.ps(y) + F.interpolate(x, scale_factor=s.up, mode='nearest')
```

`load_state_dict(strict=True)` matched every key. Exported with `torch.onnx.export(..., opset_version=17, dynamic_axes={'input': {2: 'h', 3: 'w'}, 'output': {2: 'H', 3: 'W'}}, dynamo=False)`. Torch vs ONNX max abs diff: 1.1e-6 (256×256 and 200×312 inputs).

## Conversion commands

```
mnnconvert -f ONNX --modelFile realesr_x4v3.onnx --MNNModel realesr_x4v3_f16.mnn --bizCode bloom --fp16
mnnconvert -f ONNX --modelFile realesr_x4v3.onnx --MNNModel realesr_x4v3_w8.mnn --bizCode bloom --weightQuantBits 8
mnnconvert -f ONNX --modelFile rvm_mobilenetv3_fp32.onnx --MNNModel rvm_mobilenetv3_f16.mnn --bizCode bloom --fp16
mnnconvert -f ONNX --modelFile rvm_mobilenetv3_fp32.onnx --MNNModel rvm_mobilenetv3_w8.mnn --bizCode bloom --weightQuantBits 8
mnnconvert -f ONNX --modelFile modnet_photographic_portrait_matting.onnx --MNNModel modnet_f16.mnn --bizCode bloom --fp16
mnnconvert -f ONNX --modelFile modnet_photographic_portrait_matting.onnx --MNNModel modnet_w8.mnn --bizCode bloom --weightQuantBits 8
```

Sizes: ESRGAN f16 2.47 MB / w8 1.29 MB; RVM f16 7.56 MB / w8 4.01 MB; MODNet f16 13.0 MB / w8 6.7 MB.

## Real-ESRGAN (upscale)

### Input / output

- Input `input`: float32 NCHW `[1, 3, h, w]`, RGB in **0..1** (no mean/std). Dynamic h, w: MNN handles any size; 256×256 tiles recommended.
- Output `output`: float32 NCHW `[1, 3, 4h, 4w]`, RGB nominally 0..1 but **overshoots** (seen −0.34..1.45): clamp to 0..1.
- 2×: run 4×, then downscale (as planned).

### MNN vs ONNX (256×256 center crops, output 1024×1024)

| Image | f16 max / mean abs diff | f16 PSNR | w8 max / mean | w8 PSNR |
|---|---|---|---|---|
| alex-gagareen | 0.0082 / 0.00023 | 68.4 dB | 0.055 / 0.0040 | 45.6 dB |
| andrew-castillo | 0.0137 / 0.00070 | 58.7 dB | 0.174 / 0.0081 | 38.9 dB |
| safe-republic | 0.0122 / 0.00077 | 58.2 dB | 0.183 / 0.0081 | 38.8 dB |

8-bit is not visibly broken side by side, but its error is ~10× fp16's and it saves only 1.2 MB, so **ship fp16**.

### Tiling (512 px image, 256 tiles, linear feather in the overlap, vs one whole-image run)

| Overlap | max abs diff | mean | 99.9th pct |
|---|---|---|---|
| 16 px | 0.070 (18 levels of 255) | 0.00029 | 0.016 (4 levels) |
| 32 px | 0.030 (8 levels) | 0.00018 | 0.008 (2 levels) |

No seam is visible at 16 px (tiled vs whole crops look the same; the ×20 diff map shows faint lines only along the tile borders). The network's receptive field is ~33 px per side, so **32 px overlap** halves the max error for ~15% more tiles; recommend 32 if time allows, 16 is acceptable. Same numbers for w8.

### Timing (desktop CPU, MNN Python, CPU backend)

- 256×256 → 1024×1024: MNN f16 **~395 ms** (thread count 4/8/16 made no difference in the Python build; 1 thread incl. load 764 ms). onnxruntime 4 threads: 146 ms.
- Throughput: ~6 s per input megapixel on CPU (512 tiles: ~7 s/MP).

**Risk for the M8.5 target (3 → 12 MP < 10 s):** the model costs ~2.4 MFLOP per input pixel, i.e. ~2.4 TFLOP per input MP, ~7 TFLOP for a 3 MP photo. On desktop CPU that is ~20 s; on a mid-range phone CPU much more. It needs the GPU backend (Precision_Low) and may still miss 10 s on mid-range hardware. Measure on device early; fallbacks: upscale only the visible crop for preview (already recommended), progress + cancel for export.

## Matting comparison

### Test set (12 images, long side 1024)

Real: alex-gagareen (long hair, hat), alex head crop, dark plane-window profile, andrew-castillo boat group, plus two studio portraits on a plain backdrop. Synthetic: those two portraits keyed off their backdrop (colour-distance key, unmixed foreground) and composited onto three repo backgrounds (fog beach, street, sky), giving a ground-truth alpha.

Settings: RVM with zero recurrent states (`[1,1,1,1]` zeros) at full 1024 input and `downsample_ratio` 0.25 / 0.4; MODNet with its official preprocessing (ref size 512, multiple of 32, input `(x-0.5)/0.5`), matte resized back bilinearly.

### Error vs ground truth on the 6 composites (MAE ×1e-3, whole image / edge band = 21 px around the GT transition)

| Composite | RVM dr0.25 | RVM dr0.4 | MODNet |
|---|---|---|---|
| studio A beach | 2.4 / 35.1 | 1.9 / 28.4 | 3.7 / 47.3 |
| studio A sky | 2.9 / 42.6 | 2.4 / 36.0 | 3.6 / 48.4 |
| studio A street | 4.8 / 64.2 | 3.4 / 49.2 | 4.9 / 53.1 |
| studio B beach | 3.1 / 39.5 | 3.1 / 38.3 | 4.2 / 50.3 |
| studio B sky | 4.1 / 52.6 | 3.7 / 47.1 | 4.4 / 53.1 |
| studio B street | 3.5 / 45.0 | 4.0 / 48.8 | 5.1 / 54.5 |
| **mean** | 3.4 / 46.5 | 3.1 / 41.3 | 4.3 / 51.1 |

RVM ratio sweep (mean over composites): 0.25 → 3.44 / 46.5, 0.3 → 3.80 / 45.9, **0.375 → 3.04 / 40.9**, 0.4 → 3.08 / 41.3, 0.5 → 2.81 / 38.0.

### Visual (hair edges and failures)

- alex: both get the hat and long side hair; RVM 0.25 keeps the crispest knit/pompom edge; MODNet is softer and blurs the outline, but kept a few more loose strands at the bottom right.
- alex head crop: RVM 0.4 punches small holes into the pompom; 0.25 and MODNet are solid. MODNet edge is blurry.
- dark plane profile: **MODNet fails** (matte is two blobs, face missing); RVM gets head and shoulders.
- boat group: **MODNet keeps only one person** (the back-facing one); RVM gets both foreground people, with some clutter between them.
- Studio composites: all three are clean; MODNet leaves a hole in the shirt (studio A street); RVM 0.4 and MODNet pick up a faint overhead wire on the street background.

Sheets (row 1: image, mattes RVM 0.25 / RVM 0.4 / MODNet, then composites on green; row 2: hair crop): `C:\Users\timot\.claude\jobs\fe31ea0f\tmp\m85\out\matting_<name>.png`, names `r1_alex`, `r2_plane`, `r3_boat`, `r6_alexhead` (sheets of the two studio portraits were deleted: personal photos). Not in the repo. 12 images, not the 20 the roadmap asks for; the 40/50 score sheet (`docs/eval/matting.md`) is still to do on device.

### Runtime (desktop, ~683×1024 input)

| | onnxruntime 4 thr | MNN f16 |
|---|---|---|
| RVM dr0.25 | 19 ms | 25 ms |
| RVM dr0.375 / 0.4 | 35 ms | 35 ms (1 thread: 55 ms) |
| MODNet 512 | 115 ms | 114 ms |

### MNN vs ONNX (all 12 images, alpha abs diff)

| Model | max | mean | worst image mean |
|---|---|---|---|
| RVM f16 dr0.25 | 0.027 | 0.00007 | 0.00028 |
| RVM f16 dr0.375 | 0.059 | 0.00008 | 0.00049 |
| RVM w8 dr0.25 | 0.444 | 0.0010 | 0.0072 |
| RVM w8 dr0.375 | 0.537 | 0.0016 | 0.0128 |
| MODNet f16 | 0.380 | 0.00020 | 0.0018 |
| MODNet w8 | 1.000 | 0.0017 | 0.0170 |

RVM w8 is not broken (worst image, the dark profile, looks almost the same), but it drifts up to 0.5 alpha on some pixels; fp16 is only 3.5 MB more, so **ship fp16**.

## RVM integration spec

- Load as a Module (like `segment.cpp`) with inputs `src, r1i, r2i, r3i, r4i, downsample_ratio`, outputs `pha` (and `fgr` if wanted).
- `src`: float32 NCHW `[1, 3, H, W]`, RGB **0..1**, no normalisation. Any H, W (tested 683×1024, 1024×683, 1024×768).
- `r1i..r4i`: float32 zeros `[1, 1, 1, 1]` each (single still = no recurrence).
- `downsample_ratio`: float32 `[1]`. Recommended: resize the photo to **long side 1024**, ratio **0.375** (internal pass ~384 px). Ratio ≈ 384 / long side if you use another size.
- `pha`: float32 `[1, 1, H, W]`, alpha 0..1 at `src` resolution. Upsample to photo size with the existing joint bilateral upsampling. `fgr` is the predicted foreground colour (0..1), useful for decontaminating edges in background swap.
- **Pitfall:** MNN fixes `downsample_ratio` at the first forward of a Module (shape cache). Changing the ratio on a loaded Module is silently ignored (mean error jumps to 0.004–0.007). Use one constant ratio, or reload the Module to change it. Changing `src` size with the same ratio works.

## models.json snippets

```json
  "upscale": {
    "file": "realesr_x4v3_f16.mnn",
    "url": "https://raw.githubusercontent.com/timothyhalim/bloom/main/models/realesr_x4v3_f16.mnn",
    "sha256": "e71c6fc46514b688b76f77717e0820179ebdb9efc481b136150dc58652360e71",
    "size": 2470880,
    "about": "Real-ESRGAN realesr-general-x4v3 (BSD-3-Clause), 4x upscale, exported to ONNX from the official weights and converted to MNN with half-precision weights. Source: https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth"
  },
  "matting": {
    "file": "rvm_mobilenetv3_f16.mnn",
    "url": "https://raw.githubusercontent.com/timothyhalim/bloom/main/models/rvm_mobilenetv3_f16.mnn",
    "sha256": "4e1e2a8b45cf573b361a5c5eb6854c146398afceb2d8ac4b713db1f1de909104",
    "size": 7556104,
    "about": "Robust Video Matting, MobileNetV3 (GPL-3.0), used on single photos, converted to MNN with half-precision weights. Source: https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_mobilenetv3_fp32.onnx"
  }
```

## Reproduce

Scripts in `C:\Users\timot\.claude\jobs\fe31ea0f\tmp\m85\scripts\` (run with the MNN venv Python, `-I`): `esr_export.py` (export), `esr_verify.py` (MNN vs ONNX, timing, tiling), `make_set.py` (test set and composites), `matting.py` (comparison and sheets), `mat_verify.py` (MNN vs ONNX for matting), `rvm_dr.py` (ratio pitfall). ESRGAN check images: `...\m85\out\esr_*_onnx_f16_w8.png`, tiling sheet `...\m85\out\esr_tiling_sheet.png`.
