// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Fixture generation and row statistics only; production JNI stays unchanged.
#include <android/bitmap.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>

namespace {
class Pixels {
public:
    Pixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS) data = nullptr;
    }
    ~Pixels() { if (data) AndroidBitmap_unlockPixels(env_, bitmap_); }
    AndroidBitmapInfo info{};
    void* data = nullptr;
private:
    JNIEnv* env_;
    jobject bitmap_;
};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dearded_spektralab_FeedbackAndroid_fill(JNIEnv* env, jclass, jobject image) {
    Pixels pixels(env, image);
    if (!pixels.data) return JNI_FALSE;
    const auto& info = pixels.info;
    for (uint32_t y = 0; y < info.height; ++y) {
        auto* row = static_cast<uint8_t*>(pixels.data) + size_t(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            row[4*x] = 40 + 150*x / std::max(1u, info.width - 1);
            row[4*x+1] = 40 + 150*y / std::max(1u, info.height - 1);
            row[4*x+2] = 70 + ((x/17 + y/11) % 3)*35;
            row[4*x+3] = 255;
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_dearded_spektralab_FeedbackAndroid_statistics(JNIEnv* env, jclass, jobject image) {
    Pixels pixels(env, image);
    if (!pixels.data) return nullptr;
    const auto& info = pixels.info;
    uint64_t hash = 0xcbf29ce484222325ull;
    jlong black = 0, white = 0, alpha = 0;
    for (uint32_t y = 0; y < info.height; ++y) {
        const auto* row = static_cast<const uint8_t*>(pixels.data) + size_t(y) * info.stride;
        unsigned lo = 255, hi = 0;
        for (uint32_t x = 0; x < info.width; ++x) {
            const auto* p = row + 4*x;
            alpha += p[3] != 255;
            for (unsigned c = 0; c < 3; ++c) {
                lo = std::min(lo, unsigned(p[c])); hi = std::max(hi, unsigned(p[c]));
            }
            const uint32_t argb = uint32_t(p[3])<<24 | uint32_t(p[0])<<16 |
                                  uint32_t(p[1])<<8 | p[2];
            hash = (hash ^ argb) * 0x100000001b3ull;
        }
        black += hi <= 1; white += lo >= 254;
    }
    const jlong values[] = {black, white, alpha, static_cast<jlong>(hash)};
    jlongArray result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}
