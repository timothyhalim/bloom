# SD1.5 inpaint, 512x512. Usage:
#   inpaint.py ref  <model_dir> <lcm_dir|-> <img> <mask> <out.png> <steps> <cfg> <sched ddim|lcm> [threads]
#   inpaint.py mnn  <mnn_dir>   <unet.mnn>  <img> <mask> <out.png> <steps> <cfg> <sched ddim|lcm> [threads]
# tokenizer/scheduler configs are read from MODEL_DIR env (diffusers folder; json/txt only).
import sys, os, time, ctypes, ctypes.wintypes as wt, numpy as np, torch
from PIL import Image
from diffusers import DDIMScheduler, LCMScheduler
from transformers import CLIPTokenizer

mode, a, b, img_p, mask_p, out_p = sys.argv[1:7]
steps, cfg, sched = int(sys.argv[7]), float(sys.argv[8]), sys.argv[9]
threads = int(sys.argv[10]) if len(sys.argv) > 10 else 4
model_dir = os.environ["MODEL_DIR"]
prompt, neg = os.environ.get("PROMPT", "cloudy blue sky"), ""
SEED = 42


class PMC(ctypes.Structure):
    _fields_ = [("cb", wt.DWORD), ("PageFaultCount", wt.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("a", ctypes.c_size_t), ("b", ctypes.c_size_t),
                ("c", ctypes.c_size_t), ("d", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t)]


def peak_mb():
    import psutil; m = psutil.Process().memory_info()
    return m.peak_wset / 2**20, m.peak_pagefile / 2**20


torch.set_num_threads(threads)
img = np.asarray(Image.open(img_p).convert("RGB"), np.float32) / 127.5 - 1  # HWC [-1,1]
mask = (np.asarray(Image.open(mask_p).convert("L"), np.float32) / 255 > 0.5).astype(np.float32)
noise = torch.randn((1, 4, 64, 64), generator=torch.Generator().manual_seed(SEED)).numpy()
Sched = DDIMScheduler if sched == "ddim" else LCMScheduler
sch = Sched.from_pretrained(model_dir, subfolder="scheduler")
use_cfg = cfg > 1.0
g = torch.Generator().manual_seed(1)  # LCM re-noising
kw = {"generator": g} if sched == "lcm" else {}

if mode == "ref":
    from diffusers import StableDiffusionInpaintPipeline
    pipe = StableDiffusionInpaintPipeline.from_pretrained(model_dir, variant="fp16", torch_dtype=torch.float32,
                                                          safety_checker=None, requires_safety_checker=False, scheduler=sch)
    if b != "-":
        pipe.load_lora_weights(b); pipe.fuse_lora()
    t0 = time.time()
    out = pipe(prompt, image=Image.open(img_p).convert("RGB"), mask_image=Image.open(mask_p).convert("L"),
               num_inference_steps=steps, guidance_scale=cfg, latents=torch.from_numpy(noise), height=512, width=512,
               output_type="np", generator=g).images[0]
    total = time.time() - t0
    Image.fromarray((out * 255).round().astype(np.uint8)).save(out_p)
    print(f"REF total {total:.1f}s steps={steps} cfg={cfg} threads={threads} peakWS/peakCommit MB={peak_mb()}")
    sys.exit()

import MNN.nn as nn, MNN.expr as F
rt = nn.create_runtime_manager(({"backend": "CPU", "precision": "normal", "memory": "low", "numThread": threads},))


def load(name, ins, outs, path=None):
    return nn.load_module_from_file(path or os.path.join(a, name + ".mnn"), ins, outs, runtime_manager=rt)


def var(x, dt=F.float):
    x = np.ascontiguousarray(x); return F.const(x.ravel(), list(x.shape), F.NCHW, dt)


def run(net, *xs):
    o = net.forward(list(xs)); o = o[0] if isinstance(o, list) else o
    o = F.convert(o, F.NCHW); return np.array(o.read()).copy().reshape(o.shape)


times = {}
t0 = time.time()
tok = CLIPTokenizer.from_pretrained(model_dir, subfolder="tokenizer")
te = load("text_encoder", ["input_ids"], ["last_hidden_state"])
ids = lambda s: var(np.array(tok(s, padding="max_length", max_length=77, truncation=True).input_ids, np.int32)[None], F.int)
cond = run(te, ids(prompt)); uncond = run(te, ids(neg)) if use_cfg else None
del te
times["text"] = time.time() - t0

t = time.time()
enc = load("vae_encoder", ["sample"], ["latent_sample"])
masked = (img * (mask[..., None] < 0.5)).transpose(2, 0, 1)[None]
masked_lat = run(enc, var(masked)) * 0.18215
del enc
times["vae_enc"] = time.time() - t
m64 = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((64, 64), Image.NEAREST), np.float32)[None, None] / 255

t = time.time()
unet = load(None, ["sample", "timestep", "encoder_hidden_states"], ["out_sample"], path=b)
times["unet_load"] = time.time() - t
sch.set_timesteps(steps)
lat = torch.from_numpy(noise) * sch.init_noise_sigma
emb = np.concatenate([uncond, cond]) if use_cfg else cond
n = 2 if use_cfg else 1
step_t = []
for ts in sch.timesteps:
    t = time.time()
    x = sch.scale_model_input(torch.cat([lat] * n), ts).numpy()
    x = np.concatenate([x, np.repeat(m64, n, 0), np.repeat(masked_lat, n, 0)], 1).astype(np.float32)
    eps = run(unet, var(x), var(np.full(n, int(ts), np.int32), F.int), var(emb.astype(np.float32)))
    if use_cfg:
        eps = eps[:1] + cfg * (eps[1:] - eps[:1])
    lat = sch.step(torch.from_numpy(eps), ts, lat, **kw).prev_sample
    step_t.append(time.time() - t)
del unet
t = time.time()
dec = load("vae_decoder", ["latent_sample"], ["sample"])
im = run(dec, var((lat.numpy() / 0.18215).astype(np.float32)))[0].transpose(1, 2, 0)
times["vae_dec"] = time.time() - t
total = time.time() - t0
Image.fromarray(((im + 1) * 127.5).clip(0, 255).round().astype(np.uint8)).save(out_p)
print(f"MNN {os.path.basename(b)} steps={steps} cfg={cfg} threads={threads} total {total:.1f}s "
      f"step mean {np.mean(step_t[1:] or step_t):.2f}s first {step_t[0]:.2f}s parts { {k: round(v, 2) for k, v in times.items()} } "
      f"peakWS/peakCommit MB={tuple(round(v) for v in peak_mb())}")
