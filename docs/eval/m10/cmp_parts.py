# Compare each MNN part with onnxruntime on random inputs. Usage: cmp_parts.py <onnx_dir> <mnn_dir> [unet_mnn_name]
import sys, os, numpy as np, onnxruntime as ort, MNN.nn as nn, MNN.expr as F
od, md = sys.argv[1], sys.argv[2]
un = sys.argv[3] if len(sys.argv) > 3 else "unet_fp16"
rt = nn.create_runtime_manager(({"backend": "CPU", "precision": "normal", "memory": "low", "numThread": 16},))
B = int(os.environ.get("B", 2))
rng = np.random.default_rng(0)


def var(x, dt=F.float):
    x = np.ascontiguousarray(x); return F.const(x.ravel(), list(x.shape), F.NCHW, dt)


def mnn(path, names, outs, xs):
    net = nn.load_module_from_file(path, names, outs, runtime_manager=rt)
    o = net.forward([var(x, F.int if x.dtype == np.int32 else F.float) for x in xs]); o = o[0] if isinstance(o, list) else o
    o = F.convert(o, F.NCHW); return np.array(o.read()).copy().reshape(o.shape)


cases = {
    "text_encoder": (["input_ids"], ["last_hidden_state"], [np.array([[49406, 320, 1125, 49407] + [49407] * 73], np.int32)]),
    "vae_encoder": (["sample"], ["latent_sample"], [rng.uniform(-1, 1, (1, 3, 512, 512)).astype(np.float32)]),
    "vae_decoder": (["latent_sample"], ["sample"], [rng.standard_normal((1, 4, 64, 64)).astype(np.float32)]),
    "unet": (["sample", "timestep", "encoder_hidden_states"], ["out_sample"],
             [rng.standard_normal((B, 9, 64, 64)).astype(np.float32), np.array([500] * B, np.int32),
              rng.standard_normal((B, 77, 768)).astype(np.float32)]),
}
for k in os.environ.get("PARTS", "text_encoder,vae_encoder,vae_decoder,unet").split(","):
    ins, outs, xs = cases[k]
    s = ort.InferenceSession(os.path.join(od, k, "model.onnx"), providers=["CPUExecutionProvider"])
    r = s.run(None, dict(zip(ins, xs)))[0]
    m = mnn(os.path.join(md, (un if k == "unet" else k) + ".mnn"), ins, outs, xs)
    print(k, r.shape, m.shape, "maxabs ref", float(np.abs(r).max()), "mean abs diff", float(np.abs(r - m).mean()),
          "max", float(np.abs(r - m).max()), "corr", float(np.corrcoef(r.ravel(), m.ravel())[0, 1]))
