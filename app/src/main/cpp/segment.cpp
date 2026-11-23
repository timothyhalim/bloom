// Subject segmentation with IS-Net through MNN: 1024x1024 RGBA in, 1024x1024 8-bit mask out.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {
constexpr int SIZE = 1024;

// Tries the GPU backends first; MNN quietly falls back to CPU when one is missing, so check what it picked.
MNN::Session *session(MNN::Interpreter &net, bool gpu, int &picked) {
    static MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Low;
    for (auto type : {MNN_FORWARD_OPENCL, MNN_FORWARD_VULKAN, MNN_FORWARD_CPU}) {
        if (!gpu && type != MNN_FORWARD_CPU) continue;
        MNN::ScheduleConfig cfg;
        cfg.type = type;
        cfg.backupType = MNN_FORWARD_CPU;
        cfg.numThread = type == MNN_FORWARD_CPU ? std::max(1u, std::thread::hardware_concurrency()) : 0;
        cfg.backendConfig = &backend;
        MNN::Session *s = net.createSession(cfg);
        if (!s) continue;
        int got = MNN_FORWARD_CPU;
        net.getSessionInfo(s, MNN::Interpreter::BACKENDS, &got);
        if (got == type || type == MNN_FORWARD_CPU) { picked = type; return s; }
        net.releaseSession(s);
    }
    return nullptr;
}

// Creating a GPU session compiles the model's shaders and can take seconds, so the session is kept for the next
// photo, and the compiled programs go to a cache file for the next app start.
struct Loaded {
    std::string key;
    std::unique_ptr<MNN::Interpreter> net;
    MNN::Session *session = nullptr;
    int backend = -1;
};
Loaded loaded;
std::mutex lock;
}  // namespace

// Returns the mask (row-major, 0..255) or null; [backend] gets the MNN forward type used.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_bloom_editor_Segment_run(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject bmp, jboolean gpu,
                                  jintArray backendOut) {
    std::lock_guard<std::mutex> guard(lock);
    auto ms = [t = std::chrono::steady_clock::now()]() mutable {
        auto now = std::chrono::steady_clock::now();
        long d = std::chrono::duration_cast<std::chrono::milliseconds>(now - t).count();
        t = now;
        return d;
    };
    const char *p = env->GetStringUTFChars(modelPath, nullptr);
    std::string key = std::string(p) + (gpu ? "|gpu" : "|cpu");
    env->ReleaseStringUTFChars(modelPath, p);
    if (loaded.key != key) {
        loaded = {};
        std::unique_ptr<MNN::Interpreter> net(MNN::Interpreter::createFromFile(key.substr(0, key.rfind('|')).c_str()));
        if (!net) return nullptr;
        const char *c = env->GetStringUTFChars(cachePath, nullptr);
        net->setCacheFile(c);
        env->ReleaseStringUTFChars(cachePath, c);
        int picked = -1;
        MNN::Session *s = session(*net, gpu, picked);
        if (!s) return nullptr;
        net->updateCacheFile(s);
        loaded = {key, std::move(net), s, picked};
    }
    MNN::Interpreter *net = loaded.net.get();
    MNN::Session *s = loaded.session;
    long setup = ms();

    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || info.width != SIZE || info.height != SIZE ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bmp, &px) != 0) return nullptr;
    // rembg's IS-Net preprocessing: divide by the brightest value, subtract 0.5
    MNN::Tensor *in = net->getSessionInput(s, nullptr);
    MNN::Tensor host(in, MNN::Tensor::CAFFE);
    float *x = host.host<float>();
    auto *rgba = static_cast<const uint8_t *>(px);
    int peak = 1;
    for (int y = 0; y < SIZE; y++)
        for (int i = 0; i < SIZE * 4; i++) if (i % 4 != 3) peak = std::max(peak, int(rgba[y * info.stride + i]));
    for (int y = 0; y < SIZE; y++)
        for (int c = 0; c < SIZE; c++)
            for (int k = 0; k < 3; k++)
                x[(k * SIZE + y) * SIZE + c] = rgba[y * info.stride + c * 4 + k] / float(peak) - 0.5f;
    AndroidBitmap_unlockPixels(env, bmp);
    in->copyFromHostTensor(&host);

    long prep = ms();
    if (net->runSession(s) != MNN::NO_ERROR) return nullptr;
    MNN::Tensor *out = net->getSessionOutput(s, "output_image");
    MNN::Tensor outHost(out, MNN::Tensor::CAFFE);
    out->copyToHostTensor(&outHost);
    __android_log_print(ANDROID_LOG_DEBUG, "Bloom", "segment: load+session %ld ms, input %ld ms, run %ld ms (backend %d)",
                        setup, prep, ms(), loaded.backend);
    const float *m = outHost.host<float>();
    auto [lo, hi] = std::minmax_element(m, m + SIZE * SIZE);
    float range = std::max(*hi - *lo, 1e-6f);
    jbyteArray mask = env->NewByteArray(SIZE * SIZE);
    jbyte *dst = env->GetByteArrayElements(mask, nullptr);
    for (int i = 0; i < SIZE * SIZE; i++) dst[i] = jbyte(int((m[i] - *lo) / range * 255.f + 0.5f));
    env->ReleaseByteArrayElements(mask, dst, 0);
    env->SetIntArrayRegion(backendOut, 0, 1, &loaded.backend);
    return mask;
}

// --- MobileSAM: tap-to-select. The encoder runs once per photo; the decoder once per set of pins. ---
#include <MNN/expr/Module.hpp>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <cmath>
#include <cstring>

namespace {
using namespace MNN::Express;

struct Sam {
    std::string encPath, decPath;
    std::shared_ptr<Executor::RuntimeManager> runtime;
    std::shared_ptr<Module> enc, dec;
    VARP embedding;  // of the last encoded photo, copied out of the encoder graph
    int w = 0, h = 0;
};
Sam sam;

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

std::shared_ptr<Executor::RuntimeManager> samRuntime(bool gpu, const std::string &cache) {
    if (sam.runtime) return sam.runtime;
    static MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Low;
    MNN::ScheduleConfig cfg;
    cfg.type = gpu ? MNN_FORWARD_AUTO : MNN_FORWARD_CPU;  // AUTO: the best backend this phone has
    cfg.numThread = int(std::max(1u, std::thread::hardware_concurrency()));
    cfg.backendConfig = &backend;
    sam.runtime.reset(Executor::RuntimeManager::createRuntimeManager(cfg));
    if (sam.runtime) sam.runtime->setCache(cache);
    return sam.runtime;
}
}  // namespace

// [bmp]: the photo with its long side 1024, RGBA. Keeps the embedding for samDecode. False if the model can't run.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_bloom_editor_Segment_samEncode(JNIEnv *env, jclass, jstring encPath, jstring cachePath, jobject bmp, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    std::string path = str(env, encPath);
    auto rt = samRuntime(gpu, str(env, cachePath));
    if (!rt) return false;
    if (!sam.enc || sam.encPath != path) {
        sam.enc.reset(Module::load({"input_image"}, {"image_embeddings"}, path.c_str(), rt));
        sam.encPath = path;
        if (!sam.enc) return false;
    }
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bmp, &px) != 0) return false;
    int w = int(info.width), h = int(info.height);
    VARP x = _Input({h, w, 3}, NCHW, halide_type_of<float>());  // RGB 0..255; the model normalises and pads itself
    float *dst = x->writeMap<float>();
    auto *rgba = static_cast<const uint8_t *>(px);
    for (int y = 0; y < h; y++)
        for (int c = 0; c < w; c++)
            for (int k = 0; k < 3; k++) dst[(y * w + c) * 3 + k] = rgba[y * info.stride + c * 4 + k];
    AndroidBitmap_unlockPixels(env, bmp);
    auto out = sam.enc->onForward({x});
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
    std::string path = str(env, decPath);
    if (sam.embedding.get() == nullptr || !sam.runtime) return nullptr;
    if (!sam.dec || sam.decPath != path) {
        sam.dec.reset(Module::load({"image_embeddings", "point_coords", "point_labels", "mask_input", "has_mask_input", "orig_im_size"},
                                   {"masks"}, path.c_str(), sam.runtime));
        sam.decPath = path;
        if (!sam.dec) return nullptr;
    }
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
    auto out = sam.dec->onForward({sam.embedding, coords, labels, maskIn, hasMask, size});
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
std::shared_ptr<Module> depthNet;
std::string depthPath;
}  // namespace

// [bmp]: the photo with both sides multiples of 14 (long side ~518), RGBA. Returns w*h bytes, 255 = nearest.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_bloom_editor_Segment_depth(JNIEnv *env, jclass, jstring modelPath, jstring cachePath, jobject bmp, jboolean gpu) {
    std::lock_guard<std::mutex> guard(lock);
    std::string path = str(env, modelPath);
    auto rt = samRuntime(gpu, str(env, cachePath));  // one runtime for the Module-API models
    if (!rt) return nullptr;
    if (!depthNet || depthPath != path) {
        depthNet.reset(Module::load({"pixel_values"}, {"predicted_depth"}, path.c_str(), rt));
        depthPath = path;
        if (!depthNet) return nullptr;
    }
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bmp, &px) != 0) return nullptr;
    int w = int(info.width), h = int(info.height);
    VARP x = _Input({1, 3, h, w}, NCHW, halide_type_of<float>());
    float *dst = x->writeMap<float>();
    const float mean[3] = {0.485f, 0.456f, 0.406f}, sd[3] = {0.229f, 0.224f, 0.225f};
    auto *rgba = static_cast<const uint8_t *>(px);
    for (int y = 0; y < h; y++)
        for (int c = 0; c < w; c++)
            for (int k = 0; k < 3; k++) dst[(k * h + y) * w + c] = (rgba[y * info.stride + c * 4 + k] / 255.f - mean[k]) / sd[k];
    AndroidBitmap_unlockPixels(env, bmp);
    auto out = depthNet->onForward({x});
    if (out.empty()) return nullptr;
    const float *d = out[0]->readMap<float>();
    auto i = out[0]->getInfo();
    if (!d || !i || i->size != w * h) return nullptr;
    auto [lo, hi] = std::minmax_element(d, d + w * h);
    float range = std::max(*hi - *lo, 1e-6f);
    jbyteArray res = env->NewByteArray(w * h);
    jbyte *r = env->GetByteArrayElements(res, nullptr);
    for (int k = 0; k < w * h; k++) r[k] = jbyte(int((d[k] - *lo) / range * 255.f + 0.5f));
    env->ReleaseByteArrayElements(res, r, 0);
    return res;
}
