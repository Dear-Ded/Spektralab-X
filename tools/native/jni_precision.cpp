// Synthetic production-JNI integration. The Android allocation APIs alone are shimmed.
// Co-created with GPT (OpenAI), 2026-11-10. Not physical-device acceptance.
#include <jni.h>
#include <android/bitmap.h>
#include <cstdarg>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

struct PrecisionBitmap {
    AndroidBitmapInfo info;
    std::vector<uint8_t> pixels;
    int locks = 0;
};
static std::vector<std::unique_ptr<PrecisionBitmap>> bitmap_owners;
static std::vector<std::unique_ptr<std::string>> string_owners;
static jstring text(const std::string& value) {
    string_owners.push_back(std::make_unique<std::string>(value));
    return reinterpret_cast<jstring>(string_owners.back().get());
}
static const char* JNICALL get_string(JNIEnv*, jstring value, jboolean*) { return reinterpret_cast<std::string*>(value)->c_str(); }
static void JNICALL release_string(JNIEnv*, jstring, const char*) {}
static jstring JNICALL new_string(JNIEnv*, const char* value) { return text(value); }
static jboolean JNICALL exception_check(JNIEnv*) { return JNI_FALSE; }
static void JNICALL exception_clear(JNIEnv*) {}
static jclass JNICALL find_class(JNIEnv*, const char*) { return reinterpret_cast<jclass>(1); }
static jfieldID JNICALL get_field(JNIEnv*, jclass, const char*, const char*) { return reinterpret_cast<jfieldID>(1); }
static jmethodID JNICALL get_method(JNIEnv*, jclass, const char*, const char*) { return reinterpret_cast<jmethodID>(1); }
static jobject JNICALL get_object(JNIEnv*, jclass, jfieldID) { return reinterpret_cast<jobject>(1); }
static jobject JNICALL allocate_bitmap(JNIEnv*, jclass, jmethodID, va_list arguments) {
    const uint32_t width = va_arg(arguments, jint), height = va_arg(arguments, jint);
    auto bitmap = std::make_unique<PrecisionBitmap>();
    bitmap->info = {width, height, width * 4, ANDROID_BITMAP_FORMAT_RGBA_8888, 0};
    bitmap->pixels.resize(size_t(width) * height * 4);
    const auto handle = reinterpret_cast<jobject>(bitmap.get());
    bitmap_owners.push_back(std::move(bitmap));
    return handle;
}
int AndroidBitmap_getInfo(JNIEnv*, jobject object, AndroidBitmapInfo* info) {
    *info = reinterpret_cast<PrecisionBitmap*>(object)->info; return 0;
}
int AndroidBitmap_lockPixels(JNIEnv*, jobject object, void** pixels) {
    auto& bitmap = *reinterpret_cast<PrecisionBitmap*>(object);
    ++bitmap.locks; *pixels = bitmap.pixels.data(); return 0;
}
int AndroidBitmap_unlockPixels(JNIEnv*, jobject object) { --reinterpret_cast<PrecisionBitmap*>(object)->locks; return 0; }
extern "C" void kmp_set_blocktime(int) {}
#include "spektralab_jni.cpp"
#define CALL(name) Java_com_dearded_spektralab_NativeEngine_##name

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(std::string(message) + ": " + t_error);
}

#ifndef SPK_JNI_PLATFORM_SHIM_ONLY
int main(int argc, char** argv) {
    try {
        require(argc == 4, "resources, temp directory, supported float DNG required");
        JNINativeInterface_ table{};
        table.GetStringUTFChars = get_string; table.ReleaseStringUTFChars = release_string; table.NewStringUTF = new_string;
        table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear; table.FindClass = find_class;
        table.GetStaticFieldID = get_field; table.GetStaticMethodID = get_method; table.GetStaticObjectField = get_object;
        table.CallStaticObjectMethodV = allocate_bitmap;
        JNIEnv env{&table};
        const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[2]));
        require(engine != 0, "create production engine");
        const std::string opening = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
        PrecisionBitmap input{{17, 3, 17 * 8 + 8, ANDROID_BITMAP_FORMAT_RGBA_F16, 0}, std::vector<uint8_t>((17 * 8 + 8) * 3, 0xa5)};
        for (uint32_t y = 0; y < input.info.height; ++y) for (uint32_t x = 0; x < input.info.width; ++x) {
            const uint16_t words[] = {uint16_t(0x3800 + x + y), 0x3400, 0x3a00, 0x3c00};
            std::memcpy(input.pixels.data() + y * input.info.stride + x * 8, words, 8);
        }
        const auto original = input.pixels;
        for (const std::string space : {"sRGB", "Display P3"}) {
            const jlong handle = CALL(openBitmapF16V1)(&env, nullptr, engine, reinterpret_cast<jobject>(&input),
                text(space), text(opening), 0, 0, 170, 30);
            require(handle != 0, "open actual F16 JNI entry");
            auto* session = reinterpret_cast<Session*>(handle);
            require(input.locks == 0 && input.pixels == original, "F16 input unlocked and unchanged");
            require(session->source_width == 170 && session->source_height == 30 &&
                    session->input_precision == "f16_sdr_v1", "F16 provenance and dimensions");
            const spk::android::InputTransform transform(space);
            std::vector<float> expected;
            for (uint32_t y = 0; y < input.info.height; ++y) for (uint32_t x = 0; x < input.info.width; ++x) {
                float rgb[] = {float(0.5 + (x + y) / 2048.0), 0.25f, 0.75f};
                for (int c = 0; c < 3; ++c) rgb[c] = transform.decode(rgb[c], c);
                transform.to_prophoto(rgb);
                expected.insert(expected.end(), rgb, rgb + 3);
            }
            require(session->small_rgb == expected, "actual F16 JNI source equals independent literal-half reference");
            require(CALL(renderReuse)(&env, nullptr, handle, text("live"), nullptr) != nullptr, "F16 no-film render");
            require(CALL(repack)(&env, nullptr, handle, nullptr) != nullptr, "F16 cached repack");
            const auto info = CALL(sourceInfo)(&env, nullptr, handle);
            require(reinterpret_cast<std::string*>(info)->find("f16_sdr_v1") != std::string::npos, "F16 sourceInfo");
            CALL(release)(&env, nullptr, handle);
        }
        for (uint16_t invalid : {uint16_t(0xbc00), uint16_t(0x3c01), uint16_t(0x7c00), uint16_t(0x7e00)}) {
            std::memcpy(input.pixels.data(), &invalid, 2);
            require(CALL(openBitmapF16V1)(&env, nullptr, engine, reinterpret_cast<jobject>(&input), text("sRGB"),
                text(opening), 0, 0, 0, 0) == 0, "invalid F16 must fail before opening session");
            require(input.locks == 0, "invalid F16 unlocked after worker failure");
        }
        PrecisionBitmap parallel{{1024, 1024, 1024 * 8, ANDROID_BITMAP_FORMAT_RGBA_F16, 0},
                                std::vector<uint8_t>(size_t(1024) * 1024 * 8, 0)};
        const uint16_t nan = 0x7e00;
        std::memcpy(parallel.pixels.data() + (size_t(1024) * 1024 - 1) * 8, &nan, 2);
        const auto parallel_before = parallel.pixels;
        require(CALL(openBitmapF16V1)(&env, nullptr, engine, reinterpret_cast<jobject>(&parallel), text("sRGB"),
            text(opening), 0, 0, 0, 0) == 0 && parallel.locks == 0 && parallel.pixels == parallel_before,
                "parallel late-row failure joins/unlocks and preserves source");
        const jlong raw_handle = CALL(openRaw)(&env, nullptr, engine, text(argv[3]), 0, 0, text(opening), 0, 0);
        require(raw_handle != 0, "actual float RAW JNI entry");
        auto* raw = reinterpret_cast<Session*>(raw_handle);
        require(raw->input_precision == "float_linear_v1" && !raw->raw_stats_binned &&
                !raw->raw_stats_ready && !raw->development(), "precise RAW stays unbinned neutral");
        spk::android::FloatDngLinear reference;
        std::string error;
        require(spk::android::decode_float_linear_dng(argv[3], spk::android::raw_float_linear_limits, reference, error),
                "float source reference decode");
        require(raw->small_rgb == reference.rgb, "JNI forwards full-precision RAW values unchanged");
        require(CALL(renderReuse)(&env, nullptr, raw_handle, text("live"), nullptr) != nullptr, "precise RAW no-film render");
        require(CALL(repack)(&env, nullptr, raw_handle, nullptr) != nullptr, "precise RAW cached repack");
        const auto raw_info = CALL(sourceInfo)(&env, nullptr, raw_handle);
        const auto& json = *reinterpret_cast<std::string*>(raw_info);
        require(json.find("float_linear_v1") != std::string::npos && json.find("\"rawFloatSource\":{") != std::string::npos,
                "RAW original floating metadata is disclosed");
        CALL(release)(&env, nullptr, raw_handle);
        require(CALL(openRaw)(&env, nullptr, engine, text(std::string(argv[2]) + "/missing-source.dng"),
            0, 0, text(opening), 0, 0) == 0, "unreadable RAW fails");
        require(t_error.find("invalid float LinearRaw precision plan") == std::string::npos &&
                t_error.find("RAW decode:") != std::string::npos, "unreadable RAW retains legacy open error");
        CALL(destroy)(&env, nullptr, engine);
        std::cout << "PASS production JNI: F16 sRGB/P3 source/render/repack; domain rejection/unlock; float LinearRaw source/render/repack/provenance\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
#endif
