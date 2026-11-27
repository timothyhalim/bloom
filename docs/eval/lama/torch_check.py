"""PyTorch: DFT-matmul generator (what is exported, with the irfft fix) vs stock torch.fft generator, at several sizes.
Usage: python torch_check.py <carve lama repo> <best.ckpt>"""
import sys, numpy as np, torch
sys.path.insert(0, '.')
from export import build
from verify import CASES, inputs
jit, ref = build(*sys.argv[1:3]), build(*sys.argv[1:3], use_jit=False)
with torch.no_grad():
    for n in (512, 776, 1024):
        i, m = (torch.from_numpy(a) for a in inputs(*CASES[0][::2], n))
        d = (jit(i, m) - ref(i, m)).abs()
        print(n, f'jit-vs-torch.fft max {d.max():.3f} mean {d.mean():.5f}')
