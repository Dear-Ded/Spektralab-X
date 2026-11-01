// JNI surface for the Android app. Every call is made from one Kotlin worker
// thread (EngineThread), because the engine's last-error string is
// thread-local. Nothing here throws into the JVM: failures return 0/null/false
// and leave a message for lastError().
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "io/raw_decoder.hpp"
#include "resize.hpp"
#include "spektrafilm/spk_engine.h"
#include "tiff16.hpp"
#include "volk.h"

namespace {

constexpr const char* kTag = "SpektraLabJNI";
thread_local std::string t_error;

struct Session {
    spk_engine* engine = nullptr;
    spk_session* session = nullptr;
    uint32_t width = 0, height = 0;
    uint32_t source_width = 0, source_height = 0;
    std::string make, model, kind;
    std::vector<uint8_t> before;   // RGBA8, see make_before
    uint32_t before_w = 0, before_h = 0;
};

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring jstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::string json_escape(const std::string& s) {
    std::string out;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", c); out += buf; }
        else out += char(c);
    }
    return out;
}

void fail(const std::string& message) {
    t_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string engine_error(const char* what) {
    const char* e = spk_last_error(nullptr);
    return std::string(what) + ": " + (e && *e ? e : "unknown engine error");
}

// Fits (w, h) inside max_edge on its long side and inside max_pixels in area;
// 0 means no limit. The pixel cap comes from the GPU's maxStorageBufferRange:
// one storage buffer holds a whole plane, so a frame larger than the range
// cannot be rendered at all.
void fit(uint32_t w, uint32_t h, uint32_t max_edge, uint64_t max_pixels, uint32_t& nw, uint32_t& nh) {
    double s = 1.0;
    if (max_edge && std::max(w, h) > max_edge) s = double(max_edge) / double(std::max(w, h));
    if (max_pixels && double(w) * h * s * s > double(max_pixels))
        s = std::sqrt(double(max_pixels) / (double(w) * h));
    nw = w; nh = h;
    if (s >= 1.0) return;
    nw = std::max<uint32_t>(1, uint32_t(std::floor(w * s)));
    nh = std::max<uint32_t>(1, uint32_t(std::floor(h * s)));
}

Session* open_pixels(spk_engine* engine, std::vector<float>& rgb, uint32_t w, uint32_t h,
                     const std::string& params) {
    const spk_image input{rgb.data(), w, h, 3};
    char* reply = nullptr;
    spk_session* s = spk_open(engine, &input, params.c_str(), &reply);
    if (reply) spk_string_free(reply);
    if (!s) { fail(engine_error("open image")); return nullptr; }
    auto* session = new Session();
    session->engine = engine;
    session->session = s;
    session->width = w;
    session->height = h;
    return session;
}

// RGBA16 rows (engine-encoded sRGB) into an ARGB_8888 Bitmap, whose memory
// order is R, G, B, A.
jobject to_bitmap(JNIEnv* env, const spk_result& r) {
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap_class || !config_class) { fail("Bitmap classes not found"); return nullptr; }
    jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(config_class, argb_field);
    jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(r.width), jint(r.height), argb);
    if (env->ExceptionCheck() || !bitmap) {
        env->ExceptionClear();
        fail("cannot allocate a " + std::to_string(r.width) + "x" + std::to_string(r.height) + " bitmap");
        return nullptr;
    }
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        fail("cannot lock bitmap pixels");
        return nullptr;
    }
    for (uint32_t y = 0; y < r.height; ++y) {
        const uint16_t* src = r.rgba16 + size_t(y) * r.row_stride_px * 4;
        auto* dst = static_cast<uint8_t*>(pixels) + size_t(y) * info.stride;
        for (uint32_t x = 0; x < r.width; ++x) {
            dst[4 * x + 0] = uint8_t((uint32_t(src[4 * x + 0]) + 128u) / 257u);
            dst[4 * x + 1] = uint8_t((uint32_t(src[4 * x + 1]) + 128u) / 257u);
            dst[4 * x + 2] = uint8_t((uint32_t(src[4 * x + 2]) + 128u) / 257u);
            dst[4 * x + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

jobject new_argb_bitmap(JNIEnv* env, uint32_t w, uint32_t h) {
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap_class || !config_class) return nullptr;
    jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(config_class, argb_field);
    jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(w), jint(h), argb);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    return bitmap;
}

float srgb_encode(float v) {
    v = std::min(1.0f, std::max(0.0f, v));
    return v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}

// The "before" view: a small RGBA8 thumbnail of the frame as it went into the
// engine. RAW arrives as linear ProPhoto (D50) and is converted to encoded sRGB
// (Bradford to D65); a gallery photo is already encoded and is only scaled.
constexpr uint32_t kBeforeEdge = 1600;

void make_before(Session* s, const std::vector<float>& rgb, uint32_t w, uint32_t h, bool linear_prophoto) {
    uint32_t nw = w, nh = h;
    fit(w, h, kBeforeEdge, 0, nw, nh);
    std::vector<float> small;
    const std::vector<float>* src = &rgb;
    if (nw != w || nh != h) { spk::android::resize_area_rgb(rgb, w, h, nw, nh, small); src = &small; }
    static constexpr float m[9] = {
        2.0341926f, -0.7274198f, -0.3067728f,
        -0.2288151f, 1.2317584f, -0.0029433f,
        -0.0085497f, -0.1532550f, 1.1618047f,
    };
    s->before.assign(size_t(nw) * nh * 4, 255);
    for (size_t i = 0, n = size_t(nw) * nh; i < n; ++i) {
        const float* p = src->data() + 3 * i;
        float r = p[0], g = p[1], b = p[2];
        if (linear_prophoto) {
            const float rr = m[0] * r + m[1] * g + m[2] * b;
            const float gg = m[3] * r + m[4] * g + m[5] * b;
            const float bb = m[6] * r + m[7] * g + m[8] * b;
            r = srgb_encode(rr); g = srgb_encode(gg); b = srgb_encode(bb);
        }
        s->before[4 * i + 0] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, r)) * 255.0f));
        s->before[4 * i + 1] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, g)) * 255.0f));
        s->before[4 * i + 2] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, b)) * 255.0f));
    }
    s->before_w = nw;
    s->before_h = nh;
}

bool render(Session* s, const std::string& tier, spk_result& out) {
    std::memset(&out, 0, sizeof out);
    // spk_reprint reuses the cached negative when the last edit was print-side
    // and develops a new one otherwise, so it is right after every edit.
    const spk_status status = spk_reprint(s->session, tier.c_str(), &out);
    if (status != SPK_OK) { fail(engine_error(("render " + tier).c_str())); return false; }
    if (!out.rgba16 || !out.width || !out.height || out.row_stride_px < out.width) {
        spk_result_free(&out);
        fail("render returned no pixels");
        return false;
    }
    return true;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_dearded_spektralab_NativeEngine_##name

JNI_FN(jstring, lastError)(JNIEnv* env, jobject) { return jstr(env, t_error); }

JNI_FN(jstring, deviceInfo)(JNIEnv* env, jobject) {
    // Queried through vkGetInstanceProcAddr directly, so volk's global table
    // (which the engine loads for its own instance) is not touched.
    if (volkInitialize() != VK_SUCCESS) return jstr(env, "{\"vulkan\":false}");
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    create.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&create, nullptr, &instance) != VK_SUCCESS) return jstr(env, "{\"vulkan\":false}");
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(vkGetInstanceProcAddr(instance, "vkDestroyInstance"));
    std::string out = "{\"vulkan\":true,\"devices\":[";
    uint32_t count = 0;
    if (enumerate && properties && enumerate(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        enumerate(instance, &count, devices.data());
        for (uint32_t i = 0; i < count; ++i) {
            VkPhysicalDeviceProperties p{};
            properties(devices[i], &p);
            if (i) out += ",";
            out += "{\"name\":\"" + json_escape(p.deviceName) + "\"";
            out += ",\"api\":\"" + std::to_string(VK_API_VERSION_MAJOR(p.apiVersion)) + "." +
                   std::to_string(VK_API_VERSION_MINOR(p.apiVersion)) + "." +
                   std::to_string(VK_API_VERSION_PATCH(p.apiVersion)) + "\"";
            out += ",\"driver\":" + std::to_string(p.driverVersion);
            out += ",\"maxStorageBufferRange\":" + std::to_string(p.limits.maxStorageBufferRange);
            out += ",\"maxComputeWorkGroupCountX\":" + std::to_string(p.limits.maxComputeWorkGroupCount[0]);
            out += "}";
        }
    }
    out += "]}";
    if (destroy) destroy(instance, nullptr);
    return jstr(env, out);
}

JNI_FN(jlong, create)(JNIEnv* env, jobject, jstring resources, jstring tmp_dir) {
    try {
        // The engine's residency snapshot uses tmpfile(); bionic falls back to
        // $TMPDIR when /data/local/tmp is not writable, which it never is here.
        const std::string tmp = str(env, tmp_dir);
        if (!tmp.empty()) setenv("TMPDIR", tmp.c_str(), 1);
        const std::string dir = str(env, resources);
        spk_engine* engine = spk_engine_create(dir.c_str(), nullptr);
        if (!engine) { fail(engine_error("engine")); return 0; }
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& e) {
        fail(std::string("engine: ") + e.what());
    } catch (...) {
        fail("engine: unknown failure");
    }
    return 0;
}

JNI_FN(void, destroy)(JNIEnv*, jobject, jlong engine) {
    spk_engine_destroy(reinterpret_cast<spk_engine*>(engine));
}

JNI_FN(jstring, capabilities)(JNIEnv* env, jobject, jlong engine) {
    const char* caps = spk_capabilities(reinterpret_cast<spk_engine*>(engine));
    return jstr(env, caps ? caps : "{}");
}

JNI_FN(jlong, openRaw)(JNIEnv* env, jobject, jlong engine_handle, jstring path_j, jint max_edge,
                       jlong max_pixels, jstring params_j) {
    try {
        auto* engine = reinterpret_cast<spk_engine*>(engine_handle);
        const std::string path = str(env, path_j);
        // Half-size decode only when the sensor is more than 4x what we keep, so
        // the binned frame still covers the working size before the CPU downscale.
        // The edge budget assumes 4:3, the widest common sensor shape by area.
        uint64_t budget = uint64_t(std::max<jlong>(0, max_pixels));
        if (max_edge > 0) {
            const uint64_t edge_px = uint64_t(max_edge) * uint64_t(max_edge) * 3 / 4;
            budget = budget ? std::min(budget, edge_px) : edge_px;
        }
        spk::io::set_raw_half_size_budget(budget);
        spk::io::DecodedRaw decoded;
        std::string error;
        const bool ok = spk::io::decode_raw_compatible(path, decoded, error);
        spk::io::set_raw_half_size_budget(0);
        if (!ok) { fail("RAW decode: " + error); return 0; }
        const uint32_t sw = decoded.width, sh = decoded.height;
        uint32_t w = sw, h = sh;
        fit(sw, sh, uint32_t(std::max(0, int(max_edge))), uint64_t(std::max<jlong>(0, max_pixels)), w, h);
        std::vector<float> rgb;
        if (w != sw || h != sh) {
            spk::android::resize_area_rgb(decoded.rgb, sw, sh, w, h, rgb);
            std::vector<float>().swap(decoded.rgb);
        } else {
            rgb.swap(decoded.rgb);
        }
        Session* session = open_pixels(engine, rgb, w, h, str(env, params_j));
        if (!session) return 0;
        make_before(session, rgb, w, h, /*linear_prophoto=*/true);
        session->source_width = sw;
        session->source_height = sh;
        session->make = decoded.metadata.make;
        session->model = decoded.metadata.model;
        session->kind = "raw";
        return reinterpret_cast<jlong>(session);
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the RAW file; choose a smaller working size");
    } catch (const std::exception& e) {
        fail(std::string("open RAW: ") + e.what());
    } catch (...) {
        fail("open RAW: unknown failure");
    }
    return 0;
}

// `bitmap` is ARGB_8888 in sRGB, already scaled by the caller.
JNI_FN(jlong, openBitmap)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jstring params_j) {
    try {
        AndroidBitmapInfo info{};
        void* pixels = nullptr;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            fail("image must be an ARGB_8888 bitmap"); return 0;
        }
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
            fail("cannot lock bitmap pixels"); return 0;
        }
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        for (uint32_t y = 0; y < info.height; ++y) {
            const auto* src = static_cast<const uint8_t*>(pixels) + size_t(y) * info.stride;
            float* dst = rgb.data() + size_t(y) * info.width * 3;
            for (uint32_t x = 0; x < info.width; ++x) {
                dst[3 * x + 0] = src[4 * x + 0] / 255.0f;
                dst[3 * x + 1] = src[4 * x + 1] / 255.0f;
                dst[3 * x + 2] = src[4 * x + 2] / 255.0f;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        Session* session = open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, str(env, params_j));
        if (!session) return 0;
        make_before(session, rgb, info.width, info.height, /*linear_prophoto=*/false);
        session->source_width = info.width;
        session->source_height = info.height;
        session->kind = "bitmap";
        return reinterpret_cast<jlong>(session);
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the image");
    } catch (const std::exception& e) {
        fail(std::string("open image: ") + e.what());
    } catch (...) {
        fail("open image: unknown failure");
    }
    return 0;
}

// The frame as it went into the engine, for press-and-hold "original".
JNI_FN(jobject, beforeBitmap)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->before.empty()) { fail("no original thumbnail"); return nullptr; }
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap_class || !config_class) { fail("Bitmap classes not found"); return nullptr; }
    jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(config_class, argb_field);
    jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(s->before_w), jint(s->before_h), argb);
    if (env->ExceptionCheck() || !bitmap) { env->ExceptionClear(); fail("cannot allocate original bitmap"); return nullptr; }
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        fail("cannot lock bitmap pixels");
        return nullptr;
    }
    for (uint32_t y = 0; y < s->before_h; ++y)
        std::memcpy(static_cast<uint8_t*>(pixels) + size_t(y) * info.stride,
                    s->before.data() + size_t(y) * s->before_w * 4, size_t(s->before_w) * 4);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

JNI_FN(jstring, sourceInfo)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return jstr(env, "{}");
    std::string out = "{\"width\":" + std::to_string(s->width) + ",\"height\":" + std::to_string(s->height) +
        ",\"sourceWidth\":" + std::to_string(s->source_width) +
        ",\"sourceHeight\":" + std::to_string(s->source_height) +
        ",\"kind\":\"" + s->kind + "\",\"make\":\"" + json_escape(s->make) +
        "\",\"model\":\"" + json_escape(s->model) + "\"}";
    return jstr(env, out);
}

JNI_FN(jstring, setParams)(JNIEnv* env, jobject, jlong handle, jstring delta_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    const std::string delta = str(env, delta_j);
    char* reply = nullptr;
    if (spk_set_params(s->session, delta.c_str(), &reply) != SPK_OK) {
        if (reply) spk_string_free(reply);
        fail(engine_error("set params"));
        return nullptr;
    }
    std::string text = reply ? reply : "{}";
    if (reply) spk_string_free(reply);
    return jstr(env, text);
}

JNI_FN(jstring, getParams)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    char* reply = nullptr;
    if (spk_get_params(s->session, &reply) != SPK_OK) {
        if (reply) spk_string_free(reply);
        fail(engine_error("get params"));
        return nullptr;
    }
    std::string text = reply ? reply : "{}";
    if (reply) spk_string_free(reply);
    return jstr(env, text);
}

JNI_FN(jobject, renderBitmap)(JNIEnv* env, jobject, jlong handle, jstring tier_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        spk_result result;
        if (!render(s, str(env, tier_j), result)) return nullptr;
        jobject bitmap = to_bitmap(env, result);
        spk_result_free(&result);
        return bitmap;
    } catch (const std::exception& e) {
        fail(std::string("render: ") + e.what());
    } catch (...) {
        fail("render: unknown failure");
    }
    return nullptr;
}

JNI_FN(jboolean, exportTiff)(JNIEnv* env, jobject, jlong handle, jstring path_j, jstring icc_j, jstring tier_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return JNI_FALSE; }
    try {
        std::vector<uint8_t> icc;
        const std::string icc_path = str(env, icc_j);
        if (!icc_path.empty()) {
            std::ifstream file(icc_path, std::ios::binary);
            icc.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
        }
        spk_result result;
        const std::string tier = tier_j ? str(env, tier_j) : std::string("full");
        if (!render(s, tier, result)) return JNI_FALSE;
        std::string error;
        const bool ok = spk::android::write_tiff16(str(env, path_j), result.rgba16, result.width, result.height,
                                                   result.row_stride_px, icc, error);
        spk_result_free(&result);
        if (!ok) { fail("TIFF: " + error); return JNI_FALSE; }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        fail(std::string("TIFF: ") + e.what());
    } catch (...) {
        fail("TIFF: unknown failure");
    }
    return JNI_FALSE;
}

JNI_FN(void, release)(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    spk_session_release(s->session);
    delete s;
}
