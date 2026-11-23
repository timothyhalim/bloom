// On-device models through MNN, all as Modules on one shared runtime: IS-Net (subject mask), MobileSAM (tap-to-select),
// Depth Anything (lens blur depth), LaMa (eraser), Real-ESRGAN (upscale) and RVM (person matte).
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <MNN/expr/Module.hpp>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
using namespace MNN::Express;
constexpr int SIZE = 1024;
std::mutex lock;

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// [bmp]'s pixels if it is RGBA (and [size] x [size] when size > 0), locked until AndroidBitmap_unlockPixels; else null.
uint8_t *lockRgba(JNIEnv *env, jobject bmp, AndroidBitmapInfo &info, int size = 0) {
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (size > 0 && (int(info.width) != size || int(info.height) != size)) || AndroidBitmap_lockPixels(env, bmp, &px) != 0)
        return nullptr;
    return static_cast<uint8_t *>(px);
}

// [n] values stretched to 0..255.
jbyteArray toBytes(JNIEnv *env, const float *v, int n) {
    auto [lo, hi] = std::minmax_element(v, v + n);
    float range = std::max(*hi - *lo, 1e-6f);
    jbyteArray res = env->NewByteArray(n);
    jbyte *r = env->GetByteArrayElements(res, nullptr);
    for (int i = 0; i < n; i++) r[i] = jbyte(int((v[i] - *lo) / range * 255.f + 0.5f));
    env->ReleaseByteArrayElements(res, r, 0);
    return res;
}

// All models share one runtime and so one cache file of compiled GPU programs (compiling them can take seconds).
std::shared_ptr<Executor::RuntimeManager> runtime;

std::shared_ptr<Executor::RuntimeManager> getRuntime(bool gpu, const std::string &cache) {
    if (runtime) return runtime;
    static MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Low;
    MNN::ScheduleConfig cfg;
    cfg.type = gpu ? MNN_FORWARD_AUTO : MNN_FORWARD_CPU;  // AUTO: the best backend this phone has
    cfg.numThread = int(std::max(1u, std::thread::hardware_concurrency()));
    cfg.backendConfig = &backend;
    runtime.reset(Executor::RuntimeManager::createRuntimeManager(cfg));
    if (runtime) runtime->setCache(cache);
    return runtime;
}

struct Net {
    std::string path;
    std::shared_ptr<Module> m;
    bool cached = false;  // its compiled programs are in the cache file
};

// Loads [path] into [net] unless it already holds it. False if the model can't load.
bool load(Net &net, const std::string &path, const std::vector<std::string> &ins, const std::vector<std::string> &outs,
          const std::shared_ptr<Executor::RuntimeManager> &rt) {
    if (net.m && net.path == path) return true;
    net.m.reset(Module::load(ins, outs, path.c_str(), rt));
    net.path = path;
    net.cached = false;
    return net.m != nullptr;
}

// MNN writes the cache file only when asked, after an inference.
std::vector<VARP> forward(Net &net, const std::vector<VARP> &in) {
    auto out = net.m->onForward(in);
    if (!out.empty() && !net.cached) {
        runtime->updateCache();
        net.cached = true;
    }
    return out;
}
}  // namespace

// IS-Net subject mask: [bmp] is SIZE x SIZE RGBA. Returns the mask (row-major, 0..255) or null.
namespace {
Net isnet;
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_bloom_editor_Segment_run(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject bmp, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    auto t0 = std::chrono::steady_clock::now();
    auto rt = getRuntime(gpu, str(env, cachePath));
    if (!rt || !load(isnet, str(env, modelPath), {"input_image"}, {"output_image"}, rt)) return nullptr;
    AndroidBitmapInfo info;
    const uint8_t *rgba = lockRgba(env, bmp, info, SIZE);
    if (!rgba) return nullptr;
    // rembg's IS-Net preprocessing: divide by the brightest value, subtract 0.5
    VARP in = _Input({1, 3, SIZE, SIZE}, NCHW, halide_type_of<float>());
    float *x = in->writeMap<float>();
    int peak = 1;
    for (int y = 0; y < SIZE; y++)
        for (int i = 0; i < SIZE * 4; i++) if (i % 4 != 3) peak = std::max(peak, int(rgba[y * info.stride + i]));
    for (int y = 0; y < SIZE; y++)
        for (int c = 0; c < SIZE; c++)
            for (int k = 0; k < 3; k++)
                x[(k * SIZE + y) * SIZE + c] = rgba[y * info.stride + c * 4 + k] / float(peak) - 0.5f;
    AndroidBitmap_unlockPixels(env, bmp);
    auto out = forward(isnet, {in});
    const float *m = out.empty() ? nullptr : out[0]->readMap<float>();
    auto i = out.empty() ? nullptr : out[0]->getInfo();
    if (!m || !i || i->size != SIZE * SIZE) return nullptr;
    __android_log_print(ANDROID_LOG_DEBUG, "Bloom", "subject: %lld ms",
                        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
    return toBytes(env, m, SIZE * SIZE);
}

// --- MobileSAM: tap-to-select. The encoder runs once per photo; the decoder once per set of pins. ---
namespace {
struct Sam {
    Net enc, dec;
    VARP embedding;  // of the last encoded photo, copied out of the encoder graph
    int w = 0, h = 0;
};
Sam sam;
}  // namespace

// [bmp]: the photo with its long side 1024, RGBA. Keeps the embedding for samDecode. False if the model can't run.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_bloom_editor_Segment_samEncode(JNIEnv *env, jclass, jstring encPath, jstring cachePath, jobject bmp, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    auto rt = getRuntime(gpu, str(env, cachePath));
    if (!rt || !load(sam.enc, str(env, encPath), {"input_image"}, {"image_embeddings"}, rt)) return false;
    AndroidBitmapInfo info;
    const uint8_t *rgba = lockRgba(env, bmp, info);
    if (!rgba) return false;
    int w = int(info.width), h = int(info.height);
    VARP x = _Input({h, w, 3}, NCHW, halide_type_of<float>());  // RGB 0..255; the model normalises and pads itself
    float *dst = x->writeMap<float>();
    for (int y = 0; y < h; y++)
        for (int c = 0; c < w; c++)
            for (int k = 0; k < 3; k++) dst[(y * w + c) * 3 + k] = rgba[y * info.stride + c * 4 + k];
    AndroidBitmap_unlockPixels(env, bmp);
    auto out = forward(sam.enc, {x});
    if (out.empty()) return false;
    auto i = out[0]->getInfo();
    if (!i) return false;
    sam.embedding = _Input(i->dim, i->order, halide_type_of<float>());
    std::memcpy(sam.embedding->writeMap<float>(), out[0]->readMap<float>(), i->size * sizeof(float));
    sam.w = w;
    sam.h = h;
    return true;
}

// [pins]: x, y (0..1 over the encoded photo), label (1 = include, 0 = exclude) triples. Returns w*h mask bytes.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_bloom_editor_Segment_samDecode(JNIEnv *env, jclass, jstring decPath, jfloatArray pinArr) {
    std::lock_guard<std::mutex> guard(lock);
    if (sam.embedding.get() == nullptr || !runtime ||
        !load(sam.dec, str(env, decPath), {"image_embeddings", "point_coords", "point_labels", "mask_input", "has_mask_input", "orig_im_size"},
              {"masks"}, runtime)) return nullptr;
    int n = env->GetArrayLength(pinArr) / 3;
    std::vector<float> pins(n * 3);
    env->GetFloatArrayRegion(pinArr, 0, n * 3, pins.data());
    // SAM's exported decoder wants a padding point (label -1) when there is no box
    VARP coords = _Input({1, n + 1, 2}, NCHW), labels = _Input({1, n + 1}, NCHW);
    float *c = coords->writeMap<float>(), *l = labels->writeMap<float>();
    float scale = 1024.f / float(std::max(sam.w, sam.h));  // coordinates in the 1024-long-side frame
    for (int i = 0; i < n; i++) {
        c[i * 2] = pins[i * 3] * sam.w * scale;
        c[i * 2 + 1] = pins[i * 3 + 1] * sam.h * scale;
        l[i] = pins[i * 3 + 2];
    }
    c[n * 2] = c[n * 2 + 1] = 0;
    l[n] = -1;
    VARP maskIn = _Input({1, 1, 256, 256}, NCHW), hasMask = _Input({1}, NCHW), size = _Input({2}, NCHW);
    std::memset(maskIn->writeMap<float>(), 0, 256 * 256 * sizeof(float));
    hasMask->writeMap<float>()[0] = 0;
    size->writeMap<float>()[0] = float(sam.h);
    size->writeMap<float>()[1] = float(sam.w);
    auto out = forward(sam.dec, {sam.embedding, coords, labels, maskIn, hasMask, size});
    if (out.empty()) return nullptr;
    const float *m = out[0]->readMap<float>();
    if (!m) return nullptr;
    int count = sam.w * sam.h;
    jbyteArray mask = env->NewByteArray(count);
    jbyte *dst = env->GetByteArrayElements(mask, nullptr);
    for (int i = 0; i < count; i++) dst[i] = jbyte(int(255.f / (1.f + std::exp(-m[i])) + 0.5f));  // logits -> 0..255
    env->ReleaseByteArrayElements(mask, dst, 0);
    return mask;
}

// --- Depth Anything V2 Small: relative depth for lens blur. ---
namespace {
Net depthNet;
}  // namespace

// [bmp]: the photo with both sides multiples of 14 (long side ~518), RGBA. Returns w*h bytes, 255 = nearest.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_bloom_editor_Segment_depth(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject bmp, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    auto rt = getRuntime(gpu, str(env, cachePath));
    if (!rt || !load(depthNet, str(env, modelPath), {"pixel_values"}, {"predicted_depth"}, rt)) return nullptr;
    AndroidBitmapInfo info;
    const uint8_t *rgba = lockRgba(env, bmp, info);
    if (!rgba) return nullptr;
    int w = int(info.width), h = int(info.height);
    VARP x = _Input({1, 3, h, w}, NCHW, halide_type_of<float>());
    float *dst = x->writeMap<float>();
    const float mean[3] = {0.485f, 0.456f, 0.406f}, sd[3] = {0.229f, 0.224f, 0.225f};
    for (int y = 0; y < h; y++)
        for (int c = 0; c < w; c++)
            for (int k = 0; k < 3; k++) dst[(k * h + y) * w + c] = (rgba[y * info.stride + c * 4 + k] / 255.f - mean[k]) / sd[k];
    AndroidBitmap_unlockPixels(env, bmp);
    auto out = forward(depthNet, {x});
    if (out.empty()) return nullptr;
    const float *d = out[0]->readMap<float>();
    auto i = out[0]->getInfo();
    if (!d || !i || i->size != w * h) return nullptr;
    return toBytes(env, d, w * h);
}

// --- LaMa eraser: fills the masked part of a 512 x 512 crop. ---
namespace {
Net eraseNet;
}  // namespace

// [bmp]: 512 x 512 RGBA, overwritten with the model's output. [mask]: 512 * 512 bytes, non-zero = fill.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_bloom_editor_Segment_erase(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject bmp, jbyteArray maskArr, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    const int n = 512;
    auto rt = getRuntime(gpu, str(env, cachePath));
    if (!rt || !load(eraseNet, str(env, modelPath), {"image", "mask"}, {"output"}, rt)) return false;
    AndroidBitmapInfo info;
    uint8_t *rgba = env->GetArrayLength(maskArr) == n * n ? lockRgba(env, bmp, info, n) : nullptr;
    if (!rgba) return false;
    auto t0 = std::chrono::steady_clock::now();
    VARP image = _Input({1, 3, n, n}, NCHW, halide_type_of<float>());
    VARP mask = _Input({1, 1, n, n}, NCHW, halide_type_of<float>());
    float *img = image->writeMap<float>();
    float *msk = mask->writeMap<float>();
    std::vector<jbyte> m(n * n);
    env->GetByteArrayRegion(maskArr, 0, n * n, m.data());
    for (int y = 0; y < n; y++)
        for (int x = 0; x < n; x++) {
            for (int k = 0; k < 3; k++) img[(k * n + y) * n + x] = rgba[y * info.stride + x * 4 + k] / 255.f;
            msk[y * n + x] = m[y * n + x] != 0 ? 1.f : 0.f;
        }
    auto out = forward(eraseNet, {image, mask});
    bool ok = !out.empty();
    if (ok) {
        const float *r = out[0]->readMap<float>();
        auto i = out[0]->getInfo();
        ok = r && i && i->size == 3 * n * n;
        if (ok)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++)
                    for (int k = 0; k < 3; k++)
                        rgba[y * info.stride + x * 4 + k] = uint8_t(std::min(255.f, std::max(0.f, r[(k * n + y) * n + x] + 0.5f)));
    }
    AndroidBitmap_unlockPixels(env, bmp);
    // LaMa's weights and work buffers are large and erasing is occasional: free them, so the app isn't a memory-kill target
    out.clear();
    eraseNet = {};
    ExecutorScope::Current()->gc(Executor::FULL);
    __android_log_print(ANDROID_LOG_DEBUG, "Bloom", "erase: %lld ms",
                        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
    return ok;
}

// --- Real-ESRGAN: 4x upscale, one tile per call (Segment.upscale tiles the photo and blends the seams). ---
namespace {
Net upscaleNet;
}  // namespace

// The [n] x [m] px region of [src] at ([x0], [y0]) made [by] (2 or 4) times bigger, weighted by wx (n * by) x wy (m * by)
// and added into [dst] at (x0 * by, y0 * by). Both RGBA. False if the model can't run.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_bloom_editor_Segment_upscaleTile(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject src, jint x0, jint y0, jint n, jint m,
                                          jobject dst, jint by, jfloatArray wxArr, jfloatArray wyArr, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    auto rt = getRuntime(gpu, str(env, cachePath));
    if (!rt || !load(upscaleNet, str(env, modelPath), {"input"}, {"output"}, rt)) return false;
    if (env->GetArrayLength(wxArr) != n * by || env->GetArrayLength(wyArr) != m * by) return false;
    std::vector<float> wx(n * by), wy(m * by);
    env->GetFloatArrayRegion(wxArr, 0, n * by, wx.data());
    env->GetFloatArrayRegion(wyArr, 0, m * by, wy.data());
    AndroidBitmapInfo si;
    const uint8_t *in = lockRgba(env, src, si);
    if (!in) return false;
    VARP x = _Input({1, 3, m, n}, NCHW, halide_type_of<float>());
    float *xp = x->writeMap<float>();
    for (int y = 0; y < m; y++)
        for (int c = 0; c < n; c++)
            for (int k = 0; k < 3; k++) xp[(k * m + y) * n + c] = in[(y0 + y) * si.stride + (x0 + c) * 4 + k] / 255.f;
    AndroidBitmap_unlockPixels(env, src);
    auto out = forward(upscaleNet, {x});
    const float *r = out.empty() ? nullptr : out[0]->readMap<float>();
    auto i = out.empty() ? nullptr : out[0]->getInfo();
    if (!r || !i || i->size != 3 * 16 * n * m) return false;
    AndroidBitmapInfo di;
    uint8_t *o = lockRgba(env, dst, di);
    if (!o) return false;
    const int W = 4 * n, H = 4 * m, f = 4 / by;  // f x f output texels average into one pixel
    for (int y = 0; y < m * by; y++)
        for (int c = 0; c < n * by; c++) {
            uint8_t *p = o + (y0 * by + y) * di.stride + (x0 * by + c) * 4;
            float w = wx[c] * wy[y] * 255.f / float(f * f);
            for (int k = 0; k < 3; k++) {
                float v = 0;
                for (int j = 0; j < f; j++)
                    for (int q = 0; q < f; q++) v += std::min(1.f, std::max(0.f, r[(k * H + y * f + j) * W + c * f + q]));  // it overshoots
                // ponytail: overlaps add rounded 8-bit parts (off by at most 2 levels at tile corners); a float strip buffer if it shows
                p[k] = uint8_t(std::min(255.f, p[k] + v * w + 0.5f));
            }
            p[3] = 255;
        }
    AndroidBitmap_unlockPixels(env, dst);
    return true;
}

// Frees Real-ESRGAN after a run: upscaling is occasional.
extern "C" JNIEXPORT void JNICALL
Java_dev_bloom_editor_Segment_upscaleEnd(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    upscaleNet = {};
    ExecutorScope::Current()->gc(Executor::FULL);
}

// Frees every model, the SAM embedding and the shared runtime (low memory). The next run loads them again.
extern "C" JNIEXPORT void JNICALL
Java_dev_bloom_editor_Segment_release0(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    isnet = {};
    sam = {};
    depthNet = {};
    eraseNet = {};
    upscaleNet = {};
    ExecutorScope::Current()->gc(Executor::FULL);
    runtime.reset();
}
