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
#include <thread>
#include <vector>

#include "io/raw_decoder.hpp"
#include "resize.hpp"
#include "spektrafilm/spk_engine.h"
#include "tiff16.hpp"
#include "pipeline/view.hpp"
#include "volk.h"
#include "white_balance.hpp"

#include <libraw/libraw.h>
#include <array>
#include <memory>

namespace {

constexpr const char* kTag = "SpektraLabJNI";
thread_local std::string t_error;

struct Session {
    spk_engine* engine = nullptr;
    spk_session* session = nullptr;
    uint32_t width = 0, height = 0;
    uint32_t source_width = 0, source_height = 0;
    std::string make, model, kind;
    // RAW only: the illuminant the camera recorded, and the one decoded with (0 = as shot).
    double as_shot_k = 0, as_shot_tint = 0, wb_k = 0, wb_tint = 0;
    std::vector<uint8_t> before;   // RGBA8, see make_before
    uint32_t before_w = 0, before_h = 0;
    // The same small frame before encoding, in the session's input space:
    // what the film preview cards render from (see thumbnail()).
    std::vector<float> small_rgb;
    uint32_t small_w = 0, small_h = 0;
};

// Owns a Session until it is handed to Kotlin, so an exception between the
// engine open and the return never leaks the session's GPU buffers.
struct SessionDeleter {
    void operator()(Session* s) const {
        if (!s) return;
        spk_session_release(s->session);
        delete s;
    }
};
using SessionPtr = std::unique_ptr<Session, SessionDeleter>;

// A render result freed on every exit (spk_result_free is a no-op once freed).
struct ResultGuard {
    spk_result r;
    ResultGuard() { std::memset(&r, 0, sizeof r); }
    ~ResultGuard() { spk_result_free(&r); }
    ResultGuard(const ResultGuard&) = delete;
    ResultGuard& operator=(const ResultGuard&) = delete;
};

// Bitmap pixels locked for exactly this scope.
struct PixelLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    bool ok = false;
    PixelLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        ok = b && AndroidBitmap_lockPixels(e, b, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS && pixels;
    }
    ~PixelLock() { if (ok) AndroidBitmap_unlockPixels(env, bitmap); }
    PixelLock(const PixelLock&) = delete;
    PixelLock& operator=(const PixelLock&) = delete;
};

// Runs body(y0, y1) over [0, rows) on up to `workers` threads, the caller
// taking the first slice. If a thread cannot be created (memory or thread
// limits), the rows it would have taken run on the caller instead: a vector
// of joinable threads must never be unwound, that is std::terminate.
template <typename F>
void run_rows(uint32_t rows, uint32_t workers, F&& body) {
    if (rows == 0) return;
    workers = std::max(1u, workers);
    const uint32_t chunk = (rows + workers - 1) / workers;
    std::vector<std::thread> threads;
    uint32_t spawned_to = std::min(rows, chunk);
    try {
        threads.reserve(workers);
        for (uint32_t y = chunk; y < rows; y += chunk) {
            const uint32_t y1 = std::min(rows, y + chunk);
            threads.emplace_back(body, y, y1);
            spawned_to = y1;
        }
    } catch (...) {
        // Fall through: whatever was not spawned runs here.
    }
    body(0u, std::min(rows, chunk));
    if (spawned_to < rows) body(spawned_to, rows);
    for (auto& t : threads) t.join();
}

// LibRaw's make/model are raw bytes; NewStringUTF aborts (under CheckJNI) on
// anything that is not valid modified UTF-8. Keep valid UTF-8, replace the rest.
std::string utf8_clean(const std::string& s) {
    std::string out;
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = n > 0 && i + n <= s.size() && c != 0;
        for (size_t k = 1; ok && k < n; ++k) ok = (static_cast<unsigned char>(s[i + k]) >> 6) == 0x2;
        if (ok) { out.append(s, i, n); i += n; }
        else { out += '?'; i += 1; }
    }
    return out;
}

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring jstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(utf8_clean(s).c_str()); }

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

// RGBA16 rows (engine-encoded sRGB) to RGBA8, split across up to four
// threads: at the 3400 px tier it is ~8 M pixels, a visible slice of a drag.
void pack_rgba8(const spk_result& r, uint8_t* out, size_t stride) {
    const auto rows = [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            const uint16_t* src = r.rgba16 + size_t(y) * r.row_stride_px * 4;
            uint8_t* dst = out + size_t(y) * stride;
            for (uint32_t x = 0; x < r.width; ++x) {
                dst[4 * x + 0] = uint8_t((uint32_t(src[4 * x + 0]) + 128u) / 257u);
                dst[4 * x + 1] = uint8_t((uint32_t(src[4 * x + 1]) + 128u) / 257u);
                dst[4 * x + 2] = uint8_t((uint32_t(src[4 * x + 2]) + 128u) / 257u);
                dst[4 * x + 3] = 255;
            }
        }
    };
    const uint64_t px = uint64_t(r.width) * r.height;
    const uint32_t workers = px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    run_rows(r.height, workers, rows);
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
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        fail("cannot read bitmap info");
        return nullptr;
    }
    PixelLock lock(env, bitmap);
    if (!lock.ok) { fail("cannot lock bitmap pixels"); return nullptr; }
    pack_rgba8(r, static_cast<uint8_t*>(lock.pixels), info.stride);
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
constexpr uint32_t kThumbEdge = 384;

float srgb_decode(float v) {
    v = std::min(1.0f, std::max(0.0f, v));
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// `display_p3`: an encoded Display P3 photo, shown here in sRGB (the before
// bitmap is sRGB), so press-and-hold does not look desaturated against the print.
// `linear`: the values are already linear (an Ultra HDR recovery), not encoded.
void make_before(Session* s, const std::vector<float>& rgb, uint32_t w, uint32_t h, bool linear_prophoto,
                 bool display_p3 = false, bool linear = false) {
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
        } else if (display_p3 || linear) {
            const float lr = linear ? r : srgb_decode(r);
            const float lg = linear ? g : srgb_decode(g);
            const float lb = linear ? b : srgb_decode(b);
            if (display_p3) {
                // Display P3 (D65) -> sRGB, linear, then the shared sRGB curve.
                r = srgb_encode(1.2249401f * lr - 0.2249404f * lg);
                g = srgb_encode(-0.0420569f * lr + 1.0420571f * lg);
                b = srgb_encode(-0.0196376f * lr - 0.0786361f * lg + 1.0982735f * lb);
            } else {
                r = srgb_encode(lr); g = srgb_encode(lg); b = srgb_encode(lb);
            }
        }
        s->before[4 * i + 0] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, r)) * 255.0f));
        s->before[4 * i + 1] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, g)) * 255.0f));
        s->before[4 * i + 2] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, b)) * 255.0f));
    }
    s->before_w = nw;
    s->before_h = nh;

    // Film cards: a still smaller copy, unencoded, so each card opens a
    // throwaway session on it in the same input space as the real one.
    uint32_t tw = nw, th = nh;
    fit(nw, nh, kThumbEdge, 0, tw, th);
    if (tw != nw || th != nh) spk::android::resize_area_rgb(*src, nw, nh, tw, th, s->small_rgb);
    else s->small_rgb = *src;
    s->small_w = tw;
    s->small_h = th;
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
                       jlong max_pixels, jstring params_j, jdouble temperature_k, jdouble tint) {
    try {
        auto* engine = reinterpret_cast<spk_engine*>(engine_handle);
        const std::string path = str(env, path_j);
        // Half-size decode (2x2 binning) only when the binned frame still covers
        // the size the frame is fitted to on both edges, whatever the sensor's
        // shape; decided below from the sensor size the metadata open reads.
        uint32_t sensor_w = 0, sensor_h = 0;
        bool sensor_turned = false;
        // White balance is chosen at decode (LibRaw's user_mul), as upstream's
        // desktop host does: the metadata-only open reads the as-shot
        // illuminant and, for a custom one, derives the multipliers.
        double as_shot_k = 0, as_shot_tint = 0;
        std::array<float, 4> mul{};
        const bool custom_wb = temperature_k > 0;
        std::string error;
        {
            auto probe = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
            if (probe->open_file(path.c_str()) == LIBRAW_SUCCESS) {
                sensor_w = probe->imgdata.sizes.width;
                sensor_h = probe->imgdata.sizes.height;
                sensor_turned = (probe->imgdata.sizes.flip & 4) != 0;
                if (!spkhost::as_shot_temperature_tint(*probe, as_shot_k, as_shot_tint) ||
                    !std::isfinite(as_shot_k) || !std::isfinite(as_shot_tint)) {
                    as_shot_k = 0; as_shot_tint = 0;
                }
                if (custom_wb && !spkhost::multipliers_for(*probe, temperature_k, tint, mul, error)) {
                    fail("white balance: " + error); return 0;
                }
            } else if (custom_wb) {
                fail("white balance: cannot read the RAW metadata"); return 0;
            }
        }
        bool binned = false;
        if (sensor_w && sensor_h) {
            uint32_t fw = 0, fh = 0;
            fit(sensor_w, sensor_h, uint32_t(std::max(0, int(max_edge))), uint64_t(std::max<jlong>(0, max_pixels)), fw, fh);
            binned = sensor_w / 2 >= fw && sensor_h / 2 >= fh;
        }
        // The patch bins when area > 4 x budget (Bayer only): 1 forces it, 0 never.
        spk::io::set_raw_half_size_budget(binned ? 1 : 0);
        spk::io::DecodedRaw decoded;
        const bool ok = spk::io::decode_raw_compatible(path, custom_wb ? &mul : nullptr, decoded, error);
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
        SessionPtr session(open_pixels(engine, rgb, w, h, str(env, params_j)));
        if (!session) return 0;
        make_before(session.get(), rgb, w, h, /*linear_prophoto=*/true);
        // The sensor's size, not the binned decode's, in display orientation.
        if (sensor_w && sensor_h && binned) {
            session->source_width = sensor_turned ? sensor_h : sensor_w;
            session->source_height = sensor_turned ? sensor_w : sensor_h;
        } else {
            session->source_width = sw;
            session->source_height = sh;
        }
        session->make = decoded.metadata.make;
        session->model = decoded.metadata.model;
        session->kind = "raw";
        session->as_shot_k = as_shot_k;
        session->as_shot_tint = as_shot_tint;
        session->wb_k = custom_wb ? double(temperature_k) : 0;
        session->wb_tint = custom_wb ? double(tint) : 0;
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the RAW file; choose a smaller working size");
    } catch (const std::exception& e) {
        fail(std::string("open RAW: ") + e.what());
    } catch (...) {
        fail("open RAW: unknown failure");
    }
    return 0;
}

// Undoes the highlight shoulder of a phone's tone curve, for a JPEG that has no
// gain map: linear values up to the knee (middle grey) pass unchanged, above it
// they are expanded so encoded white lands `stops` above 1.0. It is the exact
// inverse of y = k + u / (1 + u / w), whose slope is 1 at the knee, so the
// midtones keep their exposure and only the compressed highlights get their
// range back for the film's own shoulder. Applied to the largest channel and
// scaled through all three, so hues do not shift.
struct HighlightExpand {
    float knee = 0.18f, w = 0.0f;
    explicit HighlightExpand(float stops) {
        const float d = std::exp2(stops) - knee, r = 1.0f - knee;
        w = d > r ? d * r / (d - r) : 0.0f;
    }
    bool active() const { return w > 0.0f; }
    void apply(float& r, float& g, float& b) const {
        const float m = std::max(r, std::max(g, b));
        if (m <= knee) return;
        const float u = std::min(m - knee, (1.0f - knee));
        const float denom = std::max(1e-4f, 1.0f - u / w);
        const float k = (knee + u / denom) / m;
        r *= k; g *= k; b *= k;
    }
};

// `bitmap` is ARGB_8888 in sRGB (or Display P3), already scaled by the caller.
// `headroom_stops` > 0: decode the transfer curve here and expand the
// highlights (HighlightExpand); [params_j] must then say input_cctf_decoding = false.
JNI_FN(jlong, openBitmap)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jstring params_j,
                          jfloat headroom_stops) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            fail("image must be an ARGB_8888 bitmap"); return 0;
        }
        const std::string params = str(env, params_j);
        const HighlightExpand expand(std::isfinite(headroom_stops) ? std::clamp(float(headroom_stops), 0.0f, 3.0f) : 0.0f);
        // Allocated before the pixels are locked: a bad_alloc here must not
        // leave the bitmap pinned (Kotlin's recycle could not free it then).
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        {
            PixelLock lock(env, bitmap);
            if (!lock.ok) { fail("cannot lock bitmap pixels"); return 0; }
            // Row bands on up to four threads: a 50 MP phone photo is the
            // slowest thing on the open path after the decode itself.
            const uint64_t px = uint64_t(info.width) * info.height;
            const uint32_t workers = px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
            const auto* base = static_cast<const uint8_t*>(lock.pixels);
            // The sRGB and Display P3 transfer curves are the same: a table of 256.
            float decode[256];
            for (int v = 0; v < 256; ++v) decode[v] = srgb_decode(v / 255.0f);
            run_rows(info.height, workers, [&](uint32_t y0, uint32_t y1) {
                for (uint32_t y = y0; y < y1; ++y) {
                    const auto* src = base + size_t(y) * info.stride;
                    float* dst = rgb.data() + size_t(y) * info.width * 3;
                    for (uint32_t x = 0; x < info.width; ++x) {
                        const uint32_t a = src[4 * x + 3];
                        // ARGB_8888 is premultiplied: undo it, or semi-transparent
                        // edges (PNG) print darker than they are.
                        const float k = (a == 255 || a == 0) ? 1.0f / 255.0f : 1.0f / float(a);
                        float r = std::min(1.0f, src[4 * x + 0] * k);
                        float g = std::min(1.0f, src[4 * x + 1] * k);
                        float b = std::min(1.0f, src[4 * x + 2] * k);
                        if (expand.active()) {
                            if (a == 255 || a == 0) {
                                r = decode[src[4 * x + 0]]; g = decode[src[4 * x + 1]]; b = decode[src[4 * x + 2]];
                            } else {
                                r = srgb_decode(r); g = srgb_decode(g); b = srgb_decode(b);
                            }
                            expand.apply(r, g, b);
                        }
                        dst[3 * x + 0] = r;
                        dst[3 * x + 1] = g;
                        dst[3 * x + 2] = b;
                    }
                }
            });
        }
        SessionPtr session(open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, params));
        if (!session) return 0;
        const bool p3 = params.find("\"Display P3\"") != std::string::npos;
        make_before(session.get(), rgb, info.width, info.height, /*linear_prophoto=*/false, p3, expand.active());
        session->source_width = info.width;
        session->source_height = info.height;
        session->kind = "bitmap";
        return reinterpret_cast<jlong>(session.release());
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
// Ultra HDR / ISO 21496-1: the SDR picture plus its gain map, recovered at
// full boost (weight 1) into linear light in the base's own primaries, with
// highlights above SDR white kept, so the film's shoulder and halation see
// the real highlight range instead of a clipped 1.0. `meta_j` is the Gainmap
// API's ratioMin[3], ratioMax[3], gamma[3], epsilonSdr[3], epsilonHdr[3]
// (linear ratios). The gain map is usually a quarter of the size; it is
// sampled bilinearly in log-boost space. Caller passes linear=true params.
JNI_FN(jlong, openBitmapHdr)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jobject gain,
                             jfloatArray meta_j, jstring params_j) {
    try {
        AndroidBitmapInfo info{}, ginfo{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) { fail("image must be an ARGB_8888 bitmap"); return 0; }
        if (!gain || AndroidBitmap_getInfo(env, gain, &ginfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            !ginfo.width || !ginfo.height ||
            (ginfo.format != ANDROID_BITMAP_FORMAT_A_8 && ginfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888)) {
            fail("unsupported gain map"); return 0;
        }
        if (!meta_j || env->GetArrayLength(meta_j) < 15) { fail("gain map metadata missing"); return 0; }
        float meta[15];
        env->GetFloatArrayRegion(meta_j, 0, 15, meta);
        const std::string params = str(env, params_j);

        // Per channel, a log2-boost table over the 256 gain codes.
        const uint32_t gc = ginfo.format == ANDROID_BITMAP_FORMAT_A_8 ? 1 : 3;
        float boost[3][256];
        float eps_sdr[3], eps_hdr[3];
        for (int c = 0; c < 3; ++c) {
            const float lo = std::log2(std::max(1e-6f, meta[c]));
            const float hi = std::log2(std::max(1e-6f, meta[3 + c]));
            const float gamma = meta[6 + c] > 0 ? meta[6 + c] : 1.0f;
            eps_sdr[c] = meta[9 + c];
            eps_hdr[c] = meta[12 + c];
            for (int v = 0; v < 256; ++v) {
                const float gp = std::pow(v / 255.0f, 1.0f / gamma);
                boost[c][v] = lo * (1 - gp) + hi * gp;
            }
        }
        float decode[256];
        for (int v = 0; v < 256; ++v) decode[v] = srgb_decode(v / 255.0f);

        const uint32_t gw = ginfo.width, gh = ginfo.height;
        std::vector<uint8_t> g(size_t(gw) * gh * gc);
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        {
            PixelLock lock(env, gain);
            if (!lock.ok) { fail("cannot lock gain map pixels"); return 0; }
            for (uint32_t y = 0; y < gh; ++y) {
                const auto* src = static_cast<const uint8_t*>(lock.pixels) + size_t(y) * ginfo.stride;
                uint8_t* dst = g.data() + size_t(y) * gw * gc;
                if (gc == 1) std::memcpy(dst, src, gw);
                else for (uint32_t x = 0; x < gw; ++x) {
                    dst[3 * x] = src[4 * x]; dst[3 * x + 1] = src[4 * x + 1]; dst[3 * x + 2] = src[4 * x + 2];
                }
            }
        }
        {
            PixelLock lock(env, bitmap);
            if (!lock.ok) { fail("cannot lock bitmap pixels"); return 0; }
            const uint32_t W = info.width, H = info.height;
            const float sx = float(gw) / W, sy = float(gh) / H;
            const auto rows = [&](uint32_t y0, uint32_t y1) {
                for (uint32_t y = y0; y < y1; ++y) {
                    const auto* src = static_cast<const uint8_t*>(lock.pixels) + size_t(y) * info.stride;
                    float* dst = rgb.data() + size_t(y) * W * 3;
                    const float fy = std::min(float(gh - 1), std::max(0.0f, (y + 0.5f) * sy - 0.5f));
                    const uint32_t y0g = uint32_t(fy), y1g = std::min(gh - 1, y0g + 1);
                    const float ty = fy - y0g;
                    for (uint32_t x = 0; x < W; ++x) {
                        const float fx = std::min(float(gw - 1), std::max(0.0f, (x + 0.5f) * sx - 0.5f));
                        const uint32_t x0g = uint32_t(fx), x1g = std::min(gw - 1, x0g + 1);
                        const float tx = fx - x0g;
                        const uint32_t a = src[4 * x + 3];
                        for (int c = 0; c < 3; ++c) {
                            uint32_t code = src[4 * x + c];
                            if (a != 255 && a != 0) code = std::min(255u, (code * 255u + a / 2) / a);
                            const int gch = gc == 1 ? 0 : c;
                            const auto at = [&](uint32_t gx, uint32_t gy) {
                                return boost[c][g[(size_t(gy) * gw + gx) * gc + gch]];
                            };
                            const float lb = (at(x0g, y0g) * (1 - tx) + at(x1g, y0g) * tx) * (1 - ty) +
                                             (at(x0g, y1g) * (1 - tx) + at(x1g, y1g) * tx) * ty;
                            const float v = (decode[code] + eps_sdr[c]) * std::exp2(lb) - eps_hdr[c];
                            dst[3 * x + c] = std::isfinite(v) ? std::max(0.0f, v) : 0.0f;
                        }
                    }
                }
            };
            const uint64_t px = uint64_t(W) * H;
            run_rows(H, px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency())), rows);
        }
        SessionPtr session(open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, params));
        if (!session) return 0;
        const bool p3 = params.find("\"Display P3\"") != std::string::npos;
        make_before(session.get(), rgb, info.width, info.height, /*linear_prophoto=*/false, p3, /*linear=*/true);
        session->source_width = info.width;
        session->source_height = info.height;
        session->kind = "bitmap";
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the HDR image");
    } catch (const std::exception& e) {
        fail(std::string("open HDR image: ") + e.what());
    } catch (...) {
        fail("open HDR image: unknown failure");
    }
    return 0;
}

JNI_FN(jobject, beforeBitmap)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->before.empty()) { fail("no original thumbnail"); return nullptr; }
    try {
        jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
        jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
        if (!bitmap_class || !config_class) { env->ExceptionClear(); fail("Bitmap classes not found"); return nullptr; }
        jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (!argb_field || !create) { env->ExceptionClear(); fail("Bitmap API not found"); return nullptr; }
        jobject argb = env->GetStaticObjectField(config_class, argb_field);
        jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(s->before_w), jint(s->before_h), argb);
        if (env->ExceptionCheck() || !bitmap) { env->ExceptionClear(); fail("cannot allocate original bitmap"); return nullptr; }
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            fail("cannot read bitmap info"); return nullptr;
        }
        PixelLock lock(env, bitmap);
        if (!lock.ok) { fail("cannot lock bitmap pixels"); return nullptr; }
        for (uint32_t y = 0; y < s->before_h; ++y)
            std::memcpy(static_cast<uint8_t*>(lock.pixels) + size_t(y) * info.stride,
                        s->before.data() + size_t(y) * s->before_w * 4, size_t(s->before_w) * 4);
        return bitmap;
    } catch (const std::exception& e) {
        fail(std::string("original: ") + e.what());
    } catch (...) {
        fail("original: unknown failure");
    }
    return nullptr;
}

// A film card: the current photo, 384 px, through `params_j` (complete opening
// params, the card's film and paper). A throwaway session on the small copy
// kept at open, so the user's session and its cached negative are untouched.
JNI_FN(jobject, filmPreview)(JNIEnv* env, jobject, jlong handle, jstring params_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->small_rgb.empty()) { fail("no preview source"); return nullptr; }
    try {
        const spk_image input{s->small_rgb.data(), s->small_w, s->small_h, 3};
        const std::string params = str(env, params_j);
        std::unique_ptr<spk_session, decltype(&spk_session_release)> card(
            spk_open(s->engine, &input, params.c_str(), nullptr), &spk_session_release);
        if (!card) { fail(engine_error("film preview")); return nullptr; }
        ResultGuard result;
        if (spk_render(card.get(), "full", &result.r) != SPK_OK || !result.r.rgba16) {
            fail(engine_error("film preview render"));
            return nullptr;
        }
        return to_bitmap(env, result.r);
    } catch (const std::exception& e) {
        fail(std::string("film preview: ") + e.what());
    } catch (...) {
        fail("film preview: unknown failure");
    }
    return nullptr;
}

JNI_FN(jstring, sourceInfo)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return jstr(env, "{}");
    try {
        const auto num = [](double v) { return std::to_string(std::isfinite(v) ? v : 0.0); };
        std::string out = "{\"width\":" + std::to_string(s->width) + ",\"height\":" + std::to_string(s->height) +
            ",\"sourceWidth\":" + std::to_string(s->source_width) +
            ",\"sourceHeight\":" + std::to_string(s->source_height) +
            ",\"kind\":\"" + s->kind + "\",\"make\":\"" + json_escape(utf8_clean(s->make)) +
            "\",\"model\":\"" + json_escape(utf8_clean(s->model)) + "\"" +
            ",\"asShotK\":" + num(s->as_shot_k) + ",\"asShotTint\":" + num(s->as_shot_tint) +
            ",\"wbK\":" + num(s->wb_k) + ",\"wbTint\":" + num(s->wb_tint) + "}";
        return jstr(env, out);
    } catch (...) {
        fail("source info: out of memory");
        return jstr(env, "{}");
    }
}

JNI_FN(jstring, setParams)(JNIEnv* env, jobject, jlong handle, jstring delta_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        const std::string delta = str(env, delta_j);
        char* reply = nullptr;
        const spk_status st = spk_set_params(s->session, delta.c_str(), &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("set params")); return nullptr; }
        return jstr(env, reply ? reply : "{}");
    } catch (const std::exception& e) {
        fail(std::string("set params: ") + e.what());
    } catch (...) {
        fail("set params: unknown failure");
    }
    return nullptr;
}

JNI_FN(jstring, getParams)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        char* reply = nullptr;
        const spk_status st = spk_get_params(s->session, &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("get params")); return nullptr; }
        return jstr(env, reply ? reply : "{}");
    } catch (const std::exception& e) {
        fail(std::string("get params: ") + e.what());
    } catch (...) {
        fail("get params: unknown failure");
    }
    return nullptr;
}

JNI_FN(jobject, renderBitmap)(JNIEnv* env, jobject, jlong handle, jstring tier_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r)) return nullptr;
        return to_bitmap(env, result.r);
    } catch (const std::exception& e) {
        fail(std::string("render: ") + e.what());
    } catch (...) {
        fail("render: unknown failure");
    }
    return nullptr;
}

// Renders `tier` into `reuse` when it is the same size, so a pooled preview
// allocates nothing; otherwise into a new Bitmap from the same result, so a
// size change (first preview, crop, rotation, film edge) renders once, not
// twice. Null on failure (lastError), and `reuse` is then untouched.
JNI_FN(jobject, renderReuse)(JNIEnv* env, jobject, jlong handle, jstring tier_j, jobject reuse) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r)) return nullptr;
        AndroidBitmapInfo info{};
        if (reuse && AndroidBitmap_getInfo(env, reuse, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            info.width == result.r.width && info.height == result.r.height) {
            PixelLock lock(env, reuse);
            if (lock.ok) {
                pack_rgba8(result.r, static_cast<uint8_t*>(lock.pixels), info.stride);
                return reuse;
            }
        }
        return to_bitmap(env, result.r);
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
        // A TIFF without its profile would be read as some other space: refuse.
        std::vector<uint8_t> icc;
        const std::string icc_path = str(env, icc_j);
        std::ifstream file(icc_path, std::ios::binary);
        if (icc_path.empty() || !file) { fail("TIFF: sRGB profile missing from engine resources"); return JNI_FALSE; }
        icc.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
        if (icc.empty()) { fail("TIFF: sRGB profile is empty"); return JNI_FALSE; }
        const std::string path = str(env, path_j);
        const std::string tier = tier_j ? str(env, tier_j) : std::string("full");
        ResultGuard result;
        if (!render(s, tier, result.r)) return JNI_FALSE;
        std::string error;
        if (!spk::android::write_tiff16(path, result.r.rgba16, result.r.width, result.r.height,
                                        result.r.row_stride_px, icc, error)) {
            fail("TIFF: " + error);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        fail("TIFF: out of memory; choose a smaller working size");
    } catch (const std::exception& e) {
        fail(std::string("TIFF: ") + e.what());
    } catch (...) {
        fail("TIFF: unknown failure");
    }
    return JNI_FALSE;
}

// Safe from another thread while a render is in flight on the engine thread:
// the render unwinds at its next node and returns SPK_ERR_CANCELLED. The caller
// guarantees the session outlives this call (EditorViewModel.cancelLock).
JNI_FN(void, cancel)(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s && s->session) spk_cancel(s->session, nullptr);
}

// The fixed per-stock setup (tc_lut, C_max table, compute pipelines) paid
// before the first photo, so the first preview does not carry it.
JNI_FN(jboolean, warmUp)(JNIEnv* env, jobject, jlong engine, jstring film_j, jstring paper_j) {
    char* reply = nullptr;
    const std::string film = str(env, film_j), paper = str(env, paper_j);
    const spk_status st = spk_warm_up(reinterpret_cast<spk_engine*>(engine), film.c_str(), paper.c_str(), &reply);
    if (reply) spk_string_free(reply);
    if (st != SPK_OK) { fail(engine_error("warm up")); return JNI_FALSE; }
    return JNI_TRUE;
}

// Returns the bytes the GPU buffer pool gave back (onTrimMemory).
JNI_FN(jlong, trimMemory)(JNIEnv*, jobject, jlong engine) {
    return jlong(spk::view::trim_idle_memory(reinterpret_cast<spk_engine*>(engine)));
}

// True when the last failure on this thread was a cancellation.
JNI_FN(jboolean, lastWasCancelled)(JNIEnv*, jobject) {
    return t_error.find("render cancelled") != std::string::npos ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, release)(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    spk_session_release(s->session);
    delete s;
}
