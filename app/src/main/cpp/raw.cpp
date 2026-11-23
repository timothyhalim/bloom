// Camera raw decode through LibRaw: demosaic, camera white balance, linear sRGB, a default tone curve,
// written upright into an RGBA_F16 bitmap.
#include <jni.h>
#include <android/bitmap.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>
#include "libraw/libraw.h"

namespace {

std::vector<char> readAll(int fd) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return {};
    std::vector<char> buf(st.st_size);
    size_t got = 0;
    while (got < buf.size()) {
        ssize_t n = pread(fd, buf.data() + got, buf.size() - got, got);
        if (n <= 0) return {};
        got += n;
    }
    return buf;
}

// dcraw's auto-bright: the level that 1% of pixels exceed in their brightest channel becomes white.
// Unlike dcraw, values above it are kept (> 1.0 in the float bitmap) so lowering Exposure brings them back.
float autoBright(const ushort (*img)[4], size_t n) {
    std::vector<size_t> hist(3 * 0x2000);
    for (size_t i = 0; i < n; i++) for (int c = 0; c < 3; c++) hist[c * 0x2000 + (img[i][c] >> 3)]++;
    int white = 0;
    for (int c = 0; c < 3; c++) {
        int v = 0x2000;
        for (size_t total = 0; --v > 32;) if ((total += hist[c * 0x2000 + v]) > n / 100) break;
        white = std::max(white, v);
    }
    return 1.f / float(white << 3);
}

float toSrgb(float v) { return v <= 0.0031308f ? v * 12.92f : 1.055f * std::pow(v, 1 / 2.4f) - 0.055f; }
float toLinear(float s) { return s <= 0.04045f ? s / 12.92f : std::pow((s + 0.055f) / 1.055f, 2.4f); }

// LibRaw stage -> rough share of decode time done when it starts (loading and demosaic dominate).
float stageDone(int stage) {
    if (stage < LIBRAW_PROGRESS_LOAD_RAW) return 0.f;
    if (stage < LIBRAW_PROGRESS_RAW2_IMAGE) return 0.05f;
    if (stage < LIBRAW_PROGRESS_INTERPOLATE) return 0.45f;
    if (stage < LIBRAW_PROGRESS_MIX_GREEN) return 0.55f;
    return 0.85f;
}

struct Progress {
    JNIEnv *env;
    jobject cb;
    jmethodID report;
    void operator()(float f) const { if (cb) env->CallVoidMethod(cb, report, f); }
};

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_bloom_editor_Raw_decode(JNIEnv *env, jclass cls, jint fd, jboolean half, jfloatArray toneArr, jobject cb) {
    Progress progress{env, cb, cb ? env->GetMethodID(env->GetObjectClass(cb), "report", "(F)V") : nullptr};
    std::vector<char> file = readAll(fd);
    if (file.empty()) return nullptr;
    auto raw = std::make_unique<LibRaw>();
    auto &o = raw->imgdata.params;
    o.output_bps = 16;
    o.gamm[0] = o.gamm[1] = 1;  // linear
    o.no_auto_bright = 1;
    o.use_camera_wb = 1;
    o.output_color = 1;  // sRGB primaries
    o.half_size = half;
    raw->set_progress_handler([](void *data, LibRaw_progress stage, int iteration, int) {
        if (iteration == 0) (*static_cast<Progress *>(data))(stageDone(stage));
        return 0;
    }, &progress);
    if (raw->open_buffer(file.data(), file.size()) != LIBRAW_SUCCESS || raw->unpack() != LIBRAW_SUCCESS) return nullptr;
    if (raw->dcraw_process() != LIBRAW_SUCCESS) return nullptr;

    auto &s = raw->imgdata.sizes;
    const ushort (*img)[4] = raw->imgdata.image;
    int w = s.width, h = s.height, flip = s.flip;
    int ow = flip & 4 ? h : w, oh = flip & 4 ? w : h;
    float gain = autoBright(img, size_t(w) * h);
    progress(0.9f);

    // Tone curve on the sRGB-encoded value, like the Curves tool; values above white pass through.
    std::vector<float> tone(env->GetArrayLength(toneArr));
    env->GetFloatArrayRegion(toneArr, 0, tone.size(), tone.data());
    std::vector<_Float16> lut(65536);
    for (int i = 0; i < 65536; i++) {
        float s = toSrgb(i * gain);
        if (s < 1.f) {
            float x = s * (tone.size() - 1);
            int k = std::min(int(x), int(tone.size()) - 2);
            s = tone[k] + (tone[k + 1] - tone[k]) * (x - k);
        }
        lut[i] = _Float16(toLinear(s));
    }

    jmethodID alloc = env->GetStaticMethodID(cls, "alloc", "(II)Landroid/graphics/Bitmap;");
    jobject bmp = env->CallStaticObjectMethod(cls, alloc, ow, oh);
    if (!bmp || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != 0 || AndroidBitmap_lockPixels(env, bmp, &px) != 0) return nullptr;
    // same mapping as LibRaw's flip_index: output (row, col) reads source pixel at index
    for (int row = 0; row < oh; row++) {
        auto *out = reinterpret_cast<_Float16 *>(static_cast<char *>(px) + size_t(row) * info.stride);
        for (int col = 0; col < ow; col++, out += 4) {
            int r = row, c = col;
            if (flip & 4) std::swap(r, c);
            if (flip & 2) r = h - 1 - r;
            if (flip & 1) c = w - 1 - c;
            const ushort *p = img[size_t(r) * w + c];
            out[0] = lut[p[0]];
            out[1] = lut[p[1]];
            out[2] = lut[p[2]];
            out[3] = _Float16(1.f);
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    progress(1.f);
    return bmp;
}
