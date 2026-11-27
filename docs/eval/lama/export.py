"""Big LaMa (advimman/lama best.ckpt, Apache-2.0) -> ONNX at a fixed SIZE, the Carve-Photos/lama way (FourierUnitJIT:
DFT as matmuls). Same contract as Carve's lama_fp32.onnx: image 1x3xNxN 0..1, mask 1x1xNxN 0/1, output 1x3xNxN 0..255
composited. Usage: python export.py <carve lama repo> <best.ckpt> <out.onnx> [size] [dynamic]
Inputs: git clone https://github.com/Carve-Photos/lama (f5fb39a); https://huggingface.co/smartywu/big-lama/resolve/main/big-lama.zip
'dynamic' makes H and W free (square, any multiple of 8): matches the fixed exports exactly in onnxruntime. Needs the
irfft fix below: Carve's drops one bin too many when the bottleneck side (size / 8) is odd, e.g. 776 -> 97.
Then: mnnconvert -f ONNX --modelFile lama1024.onnx --MNNModel lama1024_f16.mnn --bizCode MNN --fp16
(half-precision weights: 8-bit breaks the Fourier layers; mnnconvert may segfault after "Converted Success!", file is fine)"""
import sys, types
import torch

# ffc.py imports these through unused paths (rotate in a spatial transform big-lama doesn't use, seed_everything)
k = types.ModuleType('kornia'); kg = types.ModuleType('kornia.geometry'); kt = types.ModuleType('kornia.geometry.transform')
kt.rotate = None
pl = types.ModuleType('pytorch_lightning'); pl.seed_everything = None
sys.modules.update({'kornia': k, 'kornia.geometry': kg, 'kornia.geometry.transform': kt, 'pytorch_lightning': pl})
import pickle


class Stub:  # the checkpoint pickles Lightning callbacks/config objects too; never construct them, only read tensors
    def __init__(self, *a, **kw): pass
    def __setstate__(self, s): pass


class SafeUnpickler(pickle.Unpickler):
    def find_class(self, mod, name):
        safe_builtin = mod == 'builtins' and name in ('set', 'frozenset', 'slice', 'dict', 'list', 'tuple', 'int', 'float', 'bool', 'str', 'bytes', 'bytearray', 'complex')
        if safe_builtin or mod.split('.')[0] in ('torch', 'collections', 'numpy') or (mod, name) == ('_codecs', 'encode'):
            return super().find_class(mod, name)
        return Stub


def irfft(REAL, IMAG, n=None, axis=-1, norm=None):  # Carve's, with the mirrored bins from the real size n (square)
    axis = axis if axis >= 0 else REAL.ndim + axis
    end = n[-1] - n[-1] // 2  # mirror bins 1..end-1: the Nyquist bin is skipped only when n is even
    REAL_ext = torch.cat([REAL, torch.flip(REAL[..., 1:end], dims=[axis])], dim=axis)
    IMAG_ext = torch.cat([IMAG, -torch.flip(IMAG[..., 1:end], dims=[axis])], dim=axis)
    return ffc.ifft1d(REAL_ext, IMAG_ext, axis=axis)[0].permute(0, 1, 3, 2)


def build(repo, ckpt, use_jit=True):
    """The big-lama generator; use_jit=False is the stock torch.fft version (the PyTorch reference)."""
    global ffc
    sys.path.insert(0, repo)
    import saicinpainting.training.modules.ffc as ffc
    ffc.irfft = irfft
    # big-lama/config.yaml generator section, interpolations resolved; use_jit as in Carve's notebook
    gen = ffc.FFCResNetGenerator(input_nc=4, output_nc=3, ngf=64, n_downsampling=3, n_blocks=18, add_out_act='sigmoid',
                                 init_conv_kwargs=dict(ratio_gin=0, ratio_gout=0, enable_lfu=False),
                                 downsample_conv_kwargs=dict(ratio_gin=0, ratio_gout=0, enable_lfu=False),
                                 resnet_conv_kwargs=dict(ratio_gin=0.75, ratio_gout=0.75, enable_lfu=False,
                                                         **({'use_jit': True} if use_jit else {})))
    sd = torch.load(ckpt, map_location='cpu', weights_only=False,
                    pickle_module=types.SimpleNamespace(Unpickler=SafeUnpickler, load=pickle.load, __name__='pickle'))['state_dict']
    sd = {k[len('generator.'):]: v for k, v in sd.items() if k.startswith('generator.')}
    gen.load_state_dict(sd)  # strict: every generator weight must match
    return ExportLama(gen.eval()).eval()


class ExportLama(torch.nn.Module):  # Carve's wrapper, verbatim
    def __init__(self, g):
        super().__init__()
        self.generator = g

    def forward(self, image, mask):
        masked = torch.cat([image * (1 - mask), mask], dim=1)
        pred = self.generator(masked)
        return torch.clamp((mask * pred + (1 - mask) * image) * 255, min=0, max=255)


if __name__ == '__main__':
    repo, ckpt, out = sys.argv[1:4]
    SIZE = int(sys.argv[4]) if len(sys.argv) > 4 else 1024
    hw = {2: 'h', 3: 'w'}
    DYN = dict(dynamic_axes={'image': hw, 'mask': hw, 'output': hw}) if sys.argv[5:] == ['dynamic'] else {}
    model = build(repo, ckpt)
    with torch.no_grad():
        torch.onnx.export(model, (torch.rand(1, 3, SIZE, SIZE), (torch.rand(1, 1, SIZE, SIZE) > 0.5).float()), out,
                          input_names=['image', 'mask'], output_names=['output'], opset_version=17,
                          do_constant_folding=True, dynamo=False, **DYN)
    print('exported', out)
