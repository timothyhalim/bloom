"""Dynamic-size LaMa (lama_dyn_f16.mnn): one loaded Module run at several sizes in a row (MNN resizes on a new input
shape), checked against onnxruntime and the fixed models; then the size rule on the 4 cases -> cmp_rule.png.
Usage: python verify_dyn.py <old lama_f16.mnn>   (run in this folder, after export.py made lama512/1024/dyn .onnx)"""
import sys, numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, '.')
from verify import CASES, square, ort_run, mnn_run, diff

old_mnn = sys.argv[1]
dyn, dyn_ort = mnn_run('lama_dyn_f16.mnn'), ort_run('lama_dyn.onnx')


def inputs_at(im, box, n):  # like verify.inputs, from an already opened square crop
    img = np.asarray(im.resize((n, n), Image.BILINEAR), np.float32) / 255
    m = np.zeros((n, n), np.float32); x0, y0, x1, y1 = (round(v * n) for v in box); m[y0:y1, x0:x1] = 1
    return img.transpose(2, 0, 1)[None].copy(), m[None, None].copy()


def rule(side, hole):  # model size: hole at most ~400 px in model space, never above the crop's own size
    return min(1024, max(512, round(side * min(1.0, 400 / hole) / 8) * 8))


# 1) one module, sizes in a row (and back down, so both grow and shrink resizes are hit)
fixed = {512: ort_run('lama512.onnx'), 1024: ort_run('lama1024.onnx')}
f1024 = mnn_run('lama1024_f16.mnn')
for f, name, box in CASES:
    im = square(f)
    for n in (512, 640, 776, 1024, 640, 512):
        i, m = inputs_at(im, box, n); o = dyn(i, m); r = dyn_ort(i, m)
        line = f'{name:11s} {n:5d} dynmnn-vs-dynort {diff(o, r, m)}'
        if n in fixed: line += f' | dynort-vs-fixedort max {np.abs(r - fixed[n](i, m)).max():.2f}'
        if n == 1024: line += f' | dynmnn-vs-fixedmnn max {np.abs(o - f1024(i, m)).max():.2f}'
        print(line, flush=True)
        if name == 'uhdr_large' and n in (640, 776):  # look at the in-between sizes
            Image.fromarray(o[0].transpose(1, 2, 0).clip(0, 255).round().astype(np.uint8)).save(f'dyn_{name}_{n}.png')

# 2) the rule vs 512 / 1024 fixed, each fill resized to the crop's own size and pasted in the hole there
old = mnn_run(old_mnn); rows, labels = [], []
for f, name, box in CASES:
    im = square(f); side = im.width
    x0, y0, x1, y1 = (round(v * side) for v in box); n = rule(side, max(x1 - x0, y1 - y0))
    print(f'{name}: crop {side} px, hole {max(x1 - x0, y1 - y0)} px -> rule size {n}')
    orig = np.asarray(im); hole = np.zeros((side, side), bool); hole[y0:y1, x0:x1] = True
    def filled(run, s):
        i, m = inputs_at(im, box, s)
        out = Image.fromarray(run(i, m)[0].transpose(1, 2, 0).clip(0, 255).round().astype(np.uint8)).resize((side, side), Image.BILINEAR)
        return np.where(hole[..., None], np.asarray(out), orig)
    pad = max(x1 - x0, y1 - y0) // 3
    c = (max(0, x0 - pad), max(0, y0 - pad), min(side, x1 + pad), min(side, y1 + pad))
    tiles = [Image.fromarray(a).crop(c) for a in (filled(old, 512), filled(f1024, 1024), filled(dyn, n))]
    s = 400 / tiles[0].height; rows.append([t.resize((round(t.width * s), 400), Image.LANCZOS) for t in tiles])
    labels.append([f'{name}: 512 fixed', '1024 fixed', f'rule: {n} (crop {side}, hole {max(x1 - x0, y1 - y0)} px)'])
w = max(sum(t.width for t in r) + 10 * (len(r) - 1) for r in rows)
o = Image.new('RGB', (w, 430 * len(rows)), 'white'); d = ImageDraw.Draw(o)
for k, (r, lab) in enumerate(zip(rows, labels)):
    x = 0
    for t, l in zip(r, lab):
        d.text((x + 4, 430 * k + 8), l, fill='black'); o.paste(t, (x, 430 * k + 30)); x += t.width + 10
o.save('cmp_rule.png')
print('ok')
