# M10 spike: on-device SD 1.5 inpainting with MNN

Done 2026-11-24 on a desktop only: Ryzen 9 9950X3D (16 cores), 94 GB RAM, MNN 3.6.2 Python package (CPU, x86), torch 2.15 CPU, diffusers 0.43. The two RTX 5060 Ti GPUs were busy with other jobs and were not used. Unrelated jobs also used about 4 CPU cores during the runs, so desktop times are approximate. **No phone was measured.** The phone numbers below are estimates.

## Recommendation: go, only with LCM (4 steps, no CFG), pending a real-phone test

- **Plain SD 1.5 inpainting at 20 steps: no-go.** It needs 40 UNet passes (CFG doubles the batch). The estimate is about 55 s on a Snapdragon 8 Gen 2 with MNN OpenCL, about 4× over the 15 s budget.
- **SD 1.5 inpainting + LCM-LoRA (fused), 4 steps, guidance 1, int8 HQQ weights: go.** The estimate is about 9–14 s on an 8 Gen 2 (5–7 s with a TAESD VAE, see below). Quality on the test matched the diffusers reference.
- 8 LCM steps come to about 16 s on an 8 Gen 2, which is over budget. Use them only on 8 Gen 3 / 8 Elite, or with TAESD.
- Download: **about 1.28 GB** (UNet 865 MB + text encoder 247 MB + VAE 167 MB). RAM: estimated 1.5–2.6 GB peak, under the 3 GB budget. This must be measured on a phone.

## 1. Licenses

| Model | License | Source used | Commercial use | Must do |
|---|---|---|---|---|
| SD 1.5 inpainting | CreativeML OpenRAIL-M | `stable-diffusion-v1-5/stable-diffusion-inpainting` @ `8a4288a7`. This is a community mirror: the original `runwayml/stable-diffusion-inpainting` was taken down in 2024, and the card says the mirror is "not affiliated in any way with RunwayML". The weights and license are unchanged. | Yes. §2/§3 grant a perpetual, worldwide, royalty-free license to use, reproduce, sublicense and distribute the model and derivatives. The card's "intended for research purposes" is card text, not a license term. | See below |
| LCM-LoRA SD 1.5 | CreativeML OpenRAIL++-M | `latent-consistency/lcm-lora-sdv1-5` @ `cf2fced5` | Yes, the same royalty-free grant. Its §4 also allows SaaS hosting. | Same as above |
| TAESD (optional, not tested) | MIT | `madebyollin/taesd` | Yes | Keep the notice |

The weights we would ship are a **derivative** of both models: LoRA fused into the UNet, then quantized. Both licenses require, for distribution (§4):
1. The **use restrictions of §5 / Attachment A must be in the app Terms as an enforceable provision**, and users must be bound by them. Attachment A forbids using the model or its outputs: to break the law; to exploit or harm minors; to generate verifiably false information meant to harm; to generate personal data that harms people; to defame or harass; for fully automated decisions that affect legal rights; to discriminate by social behaviour, personal traits or protected characteristics; to exploit the vulnerabilities of a group; for medical advice or interpreting medical results; or for justice, law-enforcement, immigration or asylum uses such as predicting crime.
2. Ship a copy of both license texts with the download (and show them in the in-app licenses screen).
3. State that the files were modified: "LCM-LoRA fused, converted to MNN, 8-bit weights".
4. Keep the copyright notices.
- §7 lets the licensor restrict use "remotely or otherwise" when it violates the license. In practice we must comply with the restrictions. Outputs belong to the user (§6).
- **GPL-3.0 interaction:** the use restrictions are "further restrictions" that GPL-3.0 forbids on GPL code. Keep the weights a separate, optional download under their own license. Never put them in the APK or the repo. The app code stays GPL-3.0. This is my reading, not legal advice; the lead should confirm before shipping.
- Google Play's AI-Generated Content policy applies to generative features (in-app reporting or flagging of offensive output). Check it at build time.

## 2. What MNN supports

Read from the submodule (`app/src/main/cpp/mnn`, tag 3.6.2, `transformers/diffusion/`):
- `engine/src/stable_diffusion.cpp`: **SD 1.5 text-to-image only.** It has a fixed 4-channel latent input (`_Input({1, 4, 64, 64})`), a PNDM scheduler, CFG fixed at 7.5, and always runs the UNet at batch 2 (`_Concat({plms, plms}, 0)`, line 278). It has no VAE encoder, no img2img, no inpainting, no LCM scheduler. Sana and SD3.5 have img2img; nothing has inpainting.
- `export/onnx_export.py` + `convert_mnn.py`: diffusers → ONNX (TorchScript exporter, `dynamo=False`) → `mnnconvert`. They export text_encoder, unet, vae_encoder and vae_decoder, but `convert_mnn.py` converts only three of them (no vae_encoder). The README / HF model `taobao-mnn/stable-diffusion-v1-5-mnn-opencl` uses `--weightQuantBits=8 --transformerFuse` for OpenCL; `--transformerFuse` is OpenCL-only.
- **Conclusion:** don't use the MNN diffusion engine. Convert the four inpainting models ourselves and drive them from our own loop, as with LaMa: tokenizer → text encoder → VAE encoder → N × UNet (9-channel input: latent + mask + masked-image latent) → VAE decoder, with a small LCM/DDIM scheduler in Kotlin. The inpainting UNet converts and runs in MNN without problems.

## 3. Conversion (works)

Scripts: [`docs/eval/m10/`](m10/) (`export_onnx.py`, `inpaint.py`, `cmp_parts.py`).

```
# fp16 diffusers weights -> fp32 ONNX, opset 17, TorchScript exporter; UNet with dynamic batch
PARTS=text,vae python -I export_onnx.py <sd-inpaint dir> onnx
PARTS=unet     python -I export_onnx.py <sd-inpaint dir> onnx                 # base UNet
PARTS=unet     python -I export_onnx.py <sd-inpaint dir> onnx <lcm-lora dir>  # LCM-LoRA fused (fuse_lora) -> onnx/unet_lcm

mnnconvert -f ONNX --modelFile onnx/text_encoder/model.onnx --MNNModel text_encoder.mnn --fp16
mnnconvert -f ONNX --modelFile onnx/vae_encoder/model.onnx  --MNNModel vae_encoder.mnn  --fp16
mnnconvert -f ONNX --modelFile onnx/vae_decoder/model.onnx  --MNNModel vae_decoder.mnn  --fp16
mnnconvert -f ONNX --modelFile onnx/unet_lcm/model.onnx     --MNNModel unet_lcm_w8hqq.mnn --weightQuantBits 8 --hqq
# OpenCL build of the same (attention fused; converts, can't run on desktop CPU):
mnnconvert ... --weightQuantBits 8 --hqq --transformerFuse
```

Pitfalls found (each one cost a run):
1. **Attention processor:** exporting with diffusers' classic `AttnProcessor` (`baddbmm`) gives a UNet that MNN runs **wrong** (corr 0.97 vs onnxruntime: grey blob in the hole). The default SDPA processor exports to plain MatMul/Softmax and matches: max abs diff 1.2e-3 vs onnxruntime, which itself matches torch to 2e-7.
2. **Plain int8 breaks the LCM-fused UNet:** `--weightQuantBits 8` (per channel, or block 64) gives corr 0.972 and the output is a posterized blue mess or a grey blob. **`--hqq` fixes it** (corr 0.99999). Plain int8 was fine on the base UNet (corr 0.9998), but HQQ is the safe default.
3. Plain int4 (block 64) is broken on both UNets. **int4 `--hqq` block 32** works (corr 0.998), with visible drift (see quality).
4. fp32 UNet > 2 GB can't load unless `--saveExternalData` is used. Not needed.
5. `mnnconvert.exe` segfaults **after** printing "Converted Success!". The files are complete and verified.

Per-part check vs onnxruntime (random inputs): text encoder corr 0.99999, VAE encoder 0.9999999, VAE decoder 0.9999998, UNet fp16 0.99999998, UNet LCM int8-HQQ 0.99999.

## 4. Sizes

| File | Size |
|---|---|
| `text_encoder.mnn` (fp16) | 246.6 MB. int8 HQQ only saves 8 MB (the embedding table isn't quantized). |
| `vae_encoder.mnn` (fp16) | 68.4 MB |
| `vae_decoder.mnn` (fp16) | 99.1 MB |
| `unet_lcm_w8hqq.mnn` | **864.7 MB** (fp16: 1,720.9 MB; int4 HQQ: 527.1 MB) |
| Tokenizer (vocab + merges) | 1.6 MB |
| **Total, LCM int8** | **≈ 1.28 GB** (int4 UNet: ≈ 0.94 GB; with TAESD instead of the SD VAE: ≈ 1.12 GB) |

## 5. Quality (test: CC BY 4.0 `test-images/uhdr_pixel_01.jpg`, 512 crop, tree masked, prompt "cloudy blue sky above a parking lot, distant trees, photo", seed 42)

Images in scratch `tmp/m10/out/`:
- `cmp_ddim20_ref_fp16_w8.png`: diffusers DDIM 20 / MNN fp16 / MNN int8. MNN fp16 is visually identical to diffusers; int8 has small composition drift but is clean.
- `cmp_grid2.png` row 1: LCM 4 steps, guidance 1: diffusers / MNN fp16 / MNN int8-HQQ / MNN int4-HQQ. All clean, with seamless sky and treeline. int8-HQQ ≈ fp16; int4-HQQ fills with a different, denser tree (acceptable, more drift). Row 2: DDIM 20 ref / int8 / int4-HQQ / LCM 8 steps int8-HQQ.
- `cmp_hole_crops.png`: hole region only: DDIM20 ref, MNN fp16, MNN int8, LCM4 ref, LCM4 int8-HQQ, LCM4 int4-HQQ.
- `cmp_grid.png`: the broken plain-int8/int4 LCM outputs (pitfall 2/3), kept as evidence.
- LCM at guidance 1 follows the prompt less (it filled with trees, not sky), but it blends well, which is what a fill/eraser needs. LCM 4 steps with guidance 4 (batch 2) looked similar and costs 2×.

One test image, one prompt: enough to show the pipeline is correct, not a quality evaluation.

## 6. Desktop numbers (MNN CPU, x86, 16 threads, fp32 compute)

| Run | UNet per step | Text / VAE enc / VAE dec | Total | Peak working set |
|---|---|---|---|---|
| DDIM 20, CFG 7.5 (batch 2), fp16 / int8 / int4 | 2.42–2.45 s | 0.5 / 2.4 / 4.0 s | 65 s | 10.3–11.5 GB |
| LCM 4, guidance 1 (batch 1), int8-HQQ | 1.45 s | same | 21.8 s | 9.6 GB |
| LCM 8, guidance 1, int8-HQQ | 1.41 s | same | 27.3 s | 9.6 GB |
| LCM 4, int8-HQQ, 4 threads | 1.57 s | 0.5 / 2.7 / 4.6 s | 27.1 s | 11.7 GB |
| diffusers torch reference, LCM 4 (16 / 4 threads) | ≈1.0 s | | 7.2 / 11.3 s | 6.1 GB |

- The first UNet step takes 8–10 s longer (MNN shape resize and weight packing on first run). On a phone the same happens once per session; the OpenCL kernel cache must also be kept on disk.
- Weight precision doesn't change x86 CPU speed. MNN unpacks weights to fp32 (plus Winograd copies), which is why the **desktop peak RAM of 9–12 GB says nothing about the phone**. On ARM/OpenCL, weights stay fp16/int8.
- Measured cost ratios used for the phone estimate: VAE decoder ≈ 2.8 UNet passes, VAE encoder ≈ 1.7, text encoder ≈ 0.35.

## 7. Phone estimate (Snapdragon 8 Gen 2, MNN OpenCL fp16)

**Basis:**
- MNN team benchmark (Taobao Tech, 2024-11-20, [53ai mirror](https://www.53ai.com/news/OpenSourceLLM/2024112038076.html)): SD 1.5, 512², **Xiaomi 14 (8 Gen 3): GPU fp16 2.0 s/iter, CPU int8 4.2 s/iter**. ONNX Runtime on Android: 6.5 s/iter. An MNN iteration is a batch-2 UNet (CFG, see above), so **≈ 1.0 s per UNet image pass on 8 Gen 3**.
- 8 Gen 2 → 8 Gen 3 GPU: Qualcomm claims +25 %. So the 8 Gen 2 is ≈ **1.25 s per pass** (range 1.0–1.6 s).
- The inpainting UNet has the same FLOPs as the base one: ≈ 339 GMACs per pass at 512² ([BK-SDM, arXiv 2305.15798](https://arxiv.org/abs/2305.15798)). The 9-channel `conv_in` adds 0.1 GMACs.
- Upper bound of what the hardware can do: Google's GPU-optimized SD 1.4 ran 20 iterations at 512² in < 12 s on a Galaxy S23 Ultra (8 Gen 2) ([arXiv 2304.11267](https://arxiv.org/abs/2304.11267)), about 4× faster than MNN's figure. The MNN path should be expected at the slow end.
- Secondary (unverified) reports say ~15 s for 20 steps on an 8 Gen 3 with MNN. That doesn't match the MNN team's own 2 s/iter, so it isn't used.

| Config (8 Gen 2) | UNet passes | + VAE/text (passes) | Estimate | Budget 15 s |
|---|---|---|---|---|
| SD 1.5 inpaint, 20 steps, CFG | 40 | 4.9 | **≈ 55 s** (45–72) | no |
| 20 steps, no CFG | 20 | 4.9 | ≈ 31 s | no |
| **LCM 4 steps, guidance 1** | 4 | 4.9 | **≈ 11 s (9–14)** | **yes** |
| LCM 8 steps, guidance 1 | 8 | 4.9 | ≈ 16 s (13–21) | borderline |
| LCM 4 + TAESD VAE | 4 | ≈ 0.4 | ≈ 6 s (5–7) | yes |
| LCM 4, guidance 4 (batch 2) | 8 | 4.9 | ≈ 16 s | borderline |

On an 8 Gen 3 everything is about 20 % faster (LCM 4 ≈ 9 s, LCM 8 ≈ 13 s). Add model load from storage if the models aren't kept resident (≈ 1–2 s for 1.3 GB on UFS 4) and the first-run OpenCL tuning (can be 10–20 s, once).

**Uncertainty:** high, about ±40 %. The basis is one 2024 MNN figure for text-to-image at an older MNN version, scaled across chips. VAE decode on the mobile GPU may scale worse than the UNet (512² × 128-channel fp16 activations are 64 MB each). Thermal throttling over repeated fills is unknown.

**RAM (estimate):** load models one at a time (text encoder → free; VAE encoder → free; UNet; free; VAE decoder). Peak is the UNet stage: weights 0.86 GB if MNN keeps int8 on OpenCL, or 1.7 GB if it unpacks to fp16 (unknown for convolutions) + activations ≈ 0.3–0.6 GB (attention 4096×4096 per head; `--transformerFuse` reduces this) + app ≈ 0.3 GB. That comes to **≈ 1.5–2.6 GB**, under 3 GB. The MNN docs ask for ≥ 2 GB free for OpenCL fp16. Matches the plan: ≥ 8 GB RAM devices only, never 4 GB.

## 8. What the real-phone test must confirm

1. Time per UNet pass at batch 1 with `unet_lcm_w8hqq_fuse.mnn` on OpenCL (fp16 precision, `MNN_FORWARD_OPENCL`) on an 8 Gen 2 phone: **pass if ≤ 1.5 s**. Also time the VAE encoder and decoder at 512². Total LCM-4 fill ≤ 15 s, warm.
2. That the OpenCL output matches desktop (corr > 0.999 on fixed inputs), i.e. that int8 HQQ + `--transformerFuse` is correct on the GPU path.
3. Peak RSS (PSS) during the UNet stage < 3 GB, and that the int8 weights stay int8 on OpenCL.
4. Cold start (first run) time with and without the OpenCL cache file. Plan to cache it in app storage.
5. Time for 5 fills in a row (thermal) and cancel latency (one step ≈ 1.3 s).
6. If step 1 fails: fall back to TAESD, then to the CPU int8 path (expect ≈ 2 s per pass on an 8 Gen 2; LCM 4 ≈ 20 s, which fails the budget).

## Files kept (scratch, not the repo)

`tmp/m10/mnn/`: `text_encoder.mnn`, `vae_encoder.mnn`, `vae_decoder.mnn`, `unet_lcm_w8hqq.mnn` (CPU-verified), `unet_lcm_w8hqq_fuse.mnn` (OpenCL build for the phone test). The ONNX files, downloads and other UNet variants were deleted. Re-create them with the commands above (downloads: `snapshot_download` of the two repos, fp16 safetensors only, ≈ 2.1 GB).
