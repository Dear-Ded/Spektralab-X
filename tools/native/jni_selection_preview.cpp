// Co-created with GPT (OpenAI), 2026-11-13. Actual JNI and engine, synthetic pixels only.
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <filesystem>

static jsize JNICALL float_length(JNIEnv*, jarray array) {
    return jsize(reinterpret_cast<std::vector<float>*>(array)->size());
}
static void JNICALL float_region(JNIEnv*, jfloatArray array, jsize start, jsize count, jfloat* out) {
    const auto& values = *reinterpret_cast<std::vector<float>*>(array);
    require(start >= 0 && count >= 0 && size_t(start) + size_t(count) <= values.size(), "float array bounds");
    std::copy_n(values.data() + start, count, out);
}
static jfloatArray floats(std::vector<float>& values) { return reinterpret_cast<jfloatArray>(&values); }
static PrecisionBitmap* picture(jobject bitmap) {
    require(bitmap != nullptr, "render returned pixels");
    auto* result = reinterpret_cast<PrecisionBitmap*>(bitmap);
    require(result->locks == 0, "output Bitmap unlocked");
    return result;
}
static void ppm(const PrecisionBitmap& bitmap, const std::string& path) {
    std::ofstream file(path, std::ios::binary);
    file << "P6\n" << bitmap.info.width << ' ' << bitmap.info.height << "\n255\n";
    for (uint32_t y = 0; y < bitmap.info.height; ++y) for (uint32_t x = 0; x < bitmap.info.width; ++x)
        file.write(reinterpret_cast<const char*>(bitmap.pixels.data() + y * bitmap.info.stride + x * 4), 3);
    require(bool(file), "write synthetic visual evidence");
}

int main(int argc, char** argv) try {
    require(argc == 3, "resources and isolated evidence directory required");
    std::filesystem::create_directories(argv[2]);
    JNINativeInterface_ table{};
    table.GetStringUTFChars = get_string; table.ReleaseStringUTFChars = release_string; table.NewStringUTF = new_string;
    table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear; table.FindClass = find_class;
    table.GetStaticFieldID = get_field; table.GetStaticMethodID = get_method; table.GetStaticObjectField = get_object;
    table.CallStaticObjectMethodV = allocate_bitmap;
    table.GetArrayLength = float_length; table.GetFloatArrayRegion = float_region;
    JNIEnv env{&table};
    const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[2]));
    require(engine != 0, "create production engine");
    const std::string opening = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
    constexpr uint32_t w = 768, h = 512;
    PrecisionBitmap input{{w, h, w * 4, ANDROID_BITMAP_FORMAT_RGBA_8888, 0}, std::vector<uint8_t>(size_t(w) * h * 4)};
    for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
        auto* p = input.pixels.data() + y * input.info.stride + x * 4;
        p[0] = uint8_t(x * 255 / (w - 1)); p[1] = uint8_t(y * 255 / (h - 1));
        p[2] = uint8_t(((x / 96 + y / 64) % 8) * 255 / 7); p[3] = 255;
    }
    const jlong source_handle = CALL(openBitmap)(&env, nullptr, engine, reinterpret_cast<jobject>(&input),
        text("sRGB"), text(opening), 0, w, h);
    require(source_handle != 0 && input.locks == 0, "open synthetic source");
    auto* source = reinterpret_cast<Session*>(source_handle);
    require(source->small_w == 384 && source->small_h == 256, "bounded preview source geometry");
    const auto source_pixels = source->small_rgb;
    const auto original_params = *reinterpret_cast<std::string*>(CALL(getParams)(&env, nullptr, source_handle));
    const auto original_render = picture(CALL(renderReuse)(&env, nullptr, source_handle, text("live"), nullptr))->pixels;

    std::vector<float> look(50, 0.0f); look[0] = 18.0f; look[3] = 12.0f; look[5] = -15.0f; look[13] = 50.0f;
    std::vector<float> lut;
    for (int b = 0; b < 2; ++b) for (int g = 0; g < 2; ++g) for (int r = 0; r < 2; ++r) {
        lut.push_back(.1f + .8f * r); lut.push_back(.03f + .9f * g); lut.push_back(.8f * b);
    }
    int cases = 0;
    for (const std::string output : {"sRGB", "Display P3"}) for (int mode = 0; mode < 4; ++mode) {
        auto params = spk::Json::object(); std::string error;
        require(spk::Json::parse(opening, params, error), "parse base params");
        if (mode >= 2) {
            params.set("x_no_film", spk::Json(false));
            params.set("film_stock", spk::Json(std::string("kodak_portra_400")));
            params.set("print_stock", spk::Json(std::string("kodak_supra_endura")));
            params.set("grain_active", spk::Json(false));
            params.set("halation_active", spk::Json(false));
            params.set("glare_active", spk::Json(false));
        }
        if (mode == 3) params.set("digital_intermediate", spk::Json(true));
        const std::string recipe = params.dump();
        const jlong child_handle = CALL(openPreviewV1)(&env, nullptr, source_handle, text(recipe));
        require(child_handle != 0, "open independent preview session");
        auto* child = reinterpret_cast<Session*>(child_handle);
        require(child->session != source->session && child->width == 384 && child->height == 256,
            "preview owns an independent bounded engine session");
        require(CALL(setOutput)(&env, nullptr, child_handle, text(output), JNI_FALSE), "preview display output");
        auto* ungraded = picture(CALL(renderBitmap)(&env, nullptr, child_handle, text("full")));
        require(CALL(setLook)(&env, nullptr, child_handle, floats(look)), "complete finish");
        if (mode != 0) require(CALL(setLut)(&env, nullptr, child_handle, floats(lut), 2, .7f), "imported LUT and strength");
        auto* actual = picture(CALL(renderBitmap)(&env, nullptr, child_handle, text("full")));
        require(actual->pixels != ungraded->pixels, "finish changes preview pixels even on the same film");

        // Ordinary open/render of the same small input is the main pipeline at this resolution.
        auto reference_pixels = source_pixels;
        SessionPtr reference(open_pixels(source->engine, reference_pixels, 384, 256, recipe));
        require(bool(reference), "ordinary independent open");
        const auto reference_handle = reinterpret_cast<jlong>(reference.get());
        require(CALL(setOutput)(&env, nullptr, reference_handle, text(output), JNI_FALSE), "reference display output");
        require(CALL(setLook)(&env, nullptr, reference_handle, floats(look)), "reference finish");
        if (mode != 0) require(CALL(setLut)(&env, nullptr, reference_handle, floats(lut), 2, .7f), "reference LUT");
        const auto* expected = picture(CALL(renderBitmap)(&env, nullptr, reference_handle, text("full")));
        require(actual->info.width == expected->info.width && actual->info.height == expected->info.height &&
            actual->pixels == expected->pixels, "preview equals ordinary main pipeline at identical input resolution");
        require(source->small_rgb == source_pixels && !source->active_look(), "source pixels and look untouched");
        require(*reinterpret_cast<std::string*>(CALL(getParams)(&env, nullptr, source_handle)) == original_params,
            "source params untouched");
        require(picture(CALL(repack)(&env, nullptr, source_handle, nullptr))->pixels == original_render,
            "source retained render/repack untouched");
        if (output == "sRGB") ppm(*actual, std::string(argv[2]) + "/candidate-" + std::to_string(mode) + ".ppm");
        CALL(release)(&env, nullptr, child_handle);
        std::cout << "PASS candidate=" << mode << " output=" << output << " exact_bytes=" << actual->pixels.size()
                  << " main_state_preserved=true\n";
        ++cases;
    }

    const int count = g_sessions[reinterpret_cast<spk_engine*>(engine)];
    source->kind = "raw";
    auto options = opening;
    options.replace(options.find("neutral"), 7, "standard_v1");
    require(CALL(openPreviewV1)(&env, nullptr, source_handle, text(options)) == 0,
        "refuse unavailable full-source RAW development statistics");
    require(g_sessions[reinterpret_cast<spk_engine*>(engine)] == count, "refused preview releases session");
    source->raw_stats = spk::android::raw_development_stats(source_pixels.data(), 384, 384, 256);
    source->raw_stats_ready = true;
    const jlong raw_child = CALL(openPreviewV1)(&env, nullptr, source_handle, text(options));
    require(raw_child != 0 && reinterpret_cast<Session*>(raw_child)->development(), "RAW development metadata and stats inherited");
    require(CALL(renderBitmap)(&env, nullptr, raw_child, text("full")) != nullptr, "develop inherited RAW candidate");
    CALL(release)(&env, nullptr, source_handle);
    require(CALL(renderBitmap)(&env, nullptr, raw_child, text("full")) != nullptr, "preview owns its input after parent release");
    CALL(release)(&env, nullptr, raw_child);
    require(g_sessions[reinterpret_cast<spk_engine*>(engine)] == 0, "all preview and parent sessions released");
    CALL(destroy)(&env, nullptr, engine);
    std::cout << "PASS " << cases << " exact small-input pipeline comparisons; RAW metadata/refusal; parent isolation; ownership cleanup\n";
} catch (const std::exception& error) {
    std::cerr << "FAIL " << error.what() << '\n'; return 1;
}
