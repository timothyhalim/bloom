# Export SD1.5-inpainting parts to ONNX (fp32). Usage: export_onnx.py <model_dir> <out_dir> [lcm_lora_dir]
import sys, os, torch
from pathlib import Path
from diffusers import UNet2DConditionModel, AutoencoderKL
from transformers import CLIPTextModel

src, out = Path(sys.argv[1]), Path(sys.argv[2])
lcm = sys.argv[3] if len(sys.argv) > 3 else None
parts = os.environ.get("PARTS", "text,unet,vae").split(",")


def export(model, args, path, inputs, outputs, dyn=None, external=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(model, args, path.as_posix(), input_names=inputs, output_names=outputs,
                      dynamic_axes=dyn, opset_version=17, do_constant_folding=True, dynamo=False,
                      external_data=external)
    print("exported", path)


with torch.no_grad():
    if "text" in parts:
        te = CLIPTextModel.from_pretrained(src / "text_encoder", variant="fp16", torch_dtype=torch.float32).eval()
        class TE(torch.nn.Module):
            def __init__(s): super().__init__(); s.m = te
            def forward(s, ids): return s.m(ids)[0]
        export(TE(), (torch.zeros(1, 77, dtype=torch.int32),), out / "text_encoder" / "model.onnx",
               ["input_ids"], ["last_hidden_state"])

    if "unet" in parts:
        unet = UNet2DConditionModel.from_pretrained(src / "unet", variant="fp16", torch_dtype=torch.float32).eval()
        name = "unet"
        if lcm:
            from diffusers import StableDiffusionInpaintPipeline
            pipe = StableDiffusionInpaintPipeline.from_pretrained(src, unet=unet, variant="fp16", torch_dtype=torch.float32,
                                                                  safety_checker=None, requires_safety_checker=False)
            pipe.load_lora_weights(lcm)
            pipe.fuse_lora()
            pipe.unload_lora_weights()
            unet = pipe.unet
            name = "unet_lcm"
        if os.environ.get("ATTN") == "classic": unet.set_default_attn_processor()
        class U(torch.nn.Module):
            def __init__(s): super().__init__(); s.m = unet
            def forward(s, x, t, e): return s.m(x, t, e, return_dict=False)[0]
        export(U(), (torch.randn(2, 9, 64, 64), torch.tensor([999, 999], dtype=torch.int32), torch.randn(2, 77, 768)),
               out / name / "model.onnx", ["sample", "timestep", "encoder_hidden_states"], ["out_sample"],
               dyn={"sample": {0: "b"}, "timestep": {0: "b"}, "encoder_hidden_states": {0: "b"}, "out_sample": {0: "b"}},
               external=True)

    if "vae" in parts:
        vae = AutoencoderKL.from_pretrained(src / "vae", variant="fp16", torch_dtype=torch.float32).eval()
        class Enc(torch.nn.Module):
            def __init__(s): super().__init__(); s.m = vae
            def forward(s, x): return s.m.encode(x).latent_dist.mode()
        class Dec(torch.nn.Module):
            def __init__(s): super().__init__(); s.m = vae
            def forward(s, z): return s.m.decode(z).sample
        export(Enc(), (torch.randn(1, 3, 512, 512),), out / "vae_encoder" / "model.onnx", ["sample"], ["latent_sample"])
        export(Dec(), (torch.randn(1, 4, 64, 64),), out / "vae_decoder" / "model.onnx", ["latent_sample"], ["sample"])
