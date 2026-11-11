// Production JNI and TIFF writer; synthetic source only, Android allocation shim.
// Co-created with GPT (OpenAI), 2026-11-11.
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <filesystem>

static std::vector<uint8_t> read_bytes(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    require(bool(file), "read test output");
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

int main(int argc, char** argv) {
    try {
        require(argc == 3, "resources and isolated test directory required");
        std::filesystem::create_directories(argv[2]);
        JNINativeInterface_ table{};
        table.GetStringUTFChars = get_string; table.ReleaseStringUTFChars = release_string; table.NewStringUTF = new_string;
        table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear;
        JNIEnv env{&table};
        const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[2]));
        require(engine != 0, "create engine");
        const std::string params = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
        PrecisionBitmap input{{17, 7, 17 * 8, ANDROID_BITMAP_FORMAT_RGBA_F16, 0}, std::vector<uint8_t>(17 * 7 * 8)};
        for (uint32_t y = 0; y < 7; ++y) for (uint32_t x = 0; x < 17; ++x) {
            const uint16_t half[] = {uint16_t(0x3400 + x * 23), uint16_t(0x3800 + y * 31), 0x3a00, 0x3c00};
            std::memcpy(input.pixels.data() + (y * 17 + x) * 8, half, 8);
        }
        const jlong handle = CALL(openBitmapF16V1)(&env, nullptr, engine, reinterpret_cast<jobject>(&input),
            text("sRGB"), text(params), 0, 0, 0, 0);
        require(handle != 0, "open synthetic session");
        auto* session = reinterpret_cast<Session*>(handle);
        // Include out-of-sRGB and HDR values so preview clipping cannot pass as
        // the file intent. This source never comes from a private photograph.
        for (size_t p = 0; p < session->small_rgb.size() / 3; ++p) {
            session->small_rgb[p * 3] = float((p % 17) + 1) / 10;
            session->small_rgb[p * 3 + 1] = float((p % 7) + 1) / 35;
            session->small_rgb[p * 3 + 2] = float((p % 11) + 1) / 20;
        }
        const std::string iccPath = std::string(argv[1]) + "/io/sRGB.icc";
        const auto icc = read_bytes(iccPath);
        size_t cases = 0;
        for (bool film : {false, true}) {
            const char* mode = film
                ? R"({"x_no_film":false,"digital_intermediate":false,"scan_film":false,"grain_active":false,"glare_active":false,"halation_active":false,"dir_couplers_active":false,"auto_exposure":false})"
                : R"({"x_no_film":true})";
            require(CALL(setParams)(&env, nullptr, handle, text(mode)) != nullptr, "set film mode");
        for (const char* target : {"sRGB", "Display P3", "ProPhoto RGB"}) for (bool grade : {false, true}) {
            std::vector<float> values(50, 0);
            values[spk::android::kExposure] = grade ? .35f : 0.f;
            session->look.set_values(values);
            require(CALL(setOutput)(&env, nullptr, handle, text("Display P3"), JNI_FALSE), "set preview intent");
            const spk::android::OutputCancel cancel(session->cancel_generation);
            ResultGuard reference;
            require(render(session, "full", reference.r, reference.float_rgb, cancel), "reference render");
            spk::android::OutputTransform output;
            std::string error;
            require(output.fetch(session->engine, target, true, error), "reference file transform");
            require(film ? reference.r.rgba16 != nullptr : !reference.float_rgb.empty(), "wrong render storage for mode");
            std::unique_ptr<spk::android::Rgba16Rows> rows;
            if (film) rows = std::make_unique<spk::android::Rgba16Rows>(reference.r.rgba16, reference.r.row_stride_px,
                reference.r.width, reference.r.height, session->active_look(), &output);
            else rows = std::make_unique<spk::android::Rgba16Rows>(reference.float_rgb.data(), reference.r.row_stride_px,
                reference.r.width, reference.r.height, session->active_look(), &output);
            const auto expected = std::string(argv[2]) + "/expected.tif";
            const auto actual = std::string(argv[2]) + "/actual.tif";
            // ICC bytes here are an opaque writer fixture. Profile/colorant CMM
            // validity is tested separately in tools/jvm and on the second host.
            require(spk::android::write_tiff16(expected, reference.r.width, reference.r.height, icc,
                [&](uint32_t y, uint32_t n, std::vector<uint16_t>& scratch) {
                    rows->pack(y, n, scratch);
                    return spk::android::TiffRowView{scratch.data(), reference.r.width};
                }, error), "write reference TIFF");
            require(export_tiff(&env, session, actual, -1, text(iccPath), text(target), text("full")), "production TIFF");
            require(session->out.target() == "Display P3" && !session->out.exporting(), "TIFF changed the preview intent");
            require(read_bytes(actual) == read_bytes(expected), "TIFF differs from explicit file-intent RGB16");
#ifndef SPK_TIFF_BEFORE_FIX
            int fd = open(actual.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
            require(fd >= 0, "open test descriptor");
            require(CALL(exportTiffFdV1)(&env, nullptr, handle, fd, text(iccPath), text(target), text("full")), "versioned TIFF JNI");
            require(fcntl(fd, F_GETFD) >= 0, "JNI closed caller descriptor");
            close(fd);
            require(read_bytes(actual) == read_bytes(expected), "descriptor TIFF differs");
            if (std::string(target) == "sRGB") {
                fd = open(actual.c_str(), O_TRUNC | O_WRONLY);
                require(CALL(exportTiffFd)(&env, nullptr, handle, fd, text(iccPath), text("full")), "legacy four-argument TIFF JNI");
                close(fd);
                require(read_bytes(actual) == read_bytes(expected), "legacy TIFF differs");
            }
#endif
            ++cases;
        }
        }
        require(!export_tiff(&env, session, std::string(argv[2]) + "/invalid.tif", -1, text(iccPath), text("unknown-space"), text("full")), "invalid target accepted");
        require(session->out.target() == "Display P3" && !session->out.exporting(), "failed TIFF changed preview");
        CALL(release)(&env, nullptr, handle);
        CALL(destroy)(&env, nullptr, engine);
        std::cout << "PASS production TIFF: " << cases << " film/nofilm target/look cases; file-intent bytes; preview preserved; versioned/legacy JNI; descriptor ownership; invalid target\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n'; return 1;
    }
}
