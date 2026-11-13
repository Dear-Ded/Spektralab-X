// Co-created with GPT (OpenAI), 2026-11-13. Production JNI and engine, synthetic pixels only.
// Android Bitmap allocation and byte-array access use the existing host shim.
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <filesystem>

static jsize JNICALL array_length(JNIEnv*, jarray array) {
    return jsize(reinterpret_cast<std::vector<uint8_t>*>(array)->size());
}
static void JNICALL array_region(JNIEnv*, jbyteArray array, jsize start, jsize count, jbyte* out) {
    const auto& bytes = *reinterpret_cast<std::vector<uint8_t>*>(array);
    require(start >= 0 && count >= 0 && size_t(start) + size_t(count) <= bytes.size(), "byte array bounds");
    std::memcpy(out, bytes.data() + start, size_t(count));
}
static std::vector<uint8_t> read_bytes(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    require(bool(file), "read exported file");
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

int main(int argc, char** argv) try {
    require(argc == 3, "resources and isolated output directory required");
    std::filesystem::create_directories(argv[2]);
    JNINativeInterface_ table{};
    table.GetStringUTFChars = get_string; table.ReleaseStringUTFChars = release_string; table.NewStringUTF = new_string;
    table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear; table.FindClass = find_class;
    table.GetStaticFieldID = get_field; table.GetStaticMethodID = get_method; table.GetStaticObjectField = get_object;
    table.CallStaticObjectMethodV = allocate_bitmap;
    table.GetArrayLength = array_length; table.GetByteArrayRegion = array_region;
    JNIEnv env{&table};
    const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[2]));
    require(engine != 0, "create production engine");
    const auto params = text(R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})");
    const std::string profile = std::string(argv[1]) + "/io/sRGB.icc";
    auto icc = read_bytes(profile);
    constexpr uint32_t w = 1024, h = 1024;
    PrecisionBitmap input{{w, h, w * 8, ANDROID_BITMAP_FORMAT_RGBA_F16, 0}, std::vector<uint8_t>(size_t(w) * h * 8)};
    for (unsigned mode = 0; mode < 6; ++mode) {
        for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
            const bool bright = mode == 0 ? x == 1 && y == 1 :
                mode == 1 ? x == w - 1 && y == h - 1 :
                mode == 2 ? y % 4 == 1 : mode < 5 ? x % 4 == (mode == 3 ? 0u : 1u) : false;
            const uint16_t value = bright ? 0x3c00 : 0;
            const uint16_t pixel[] = {value, value, value, 0x3c00};
            std::memcpy(input.pixels.data() + (size_t(y) * w + x) * 8, pixel, sizeof(pixel));
        }
        const auto source = input.pixels;
        const jlong handle = CALL(openBitmapF16V1)(&env, nullptr, engine, reinterpret_cast<jobject>(&input),
            text("sRGB"), params, 0, 0, 0, 0);
        require(handle != 0 && input.locks == 0 && input.pixels == source, "open synthetic F16 source");
        require(CALL(setOutput)(&env, nullptr, handle, text("sRGB"), JNI_TRUE), "set output transform");
        auto* preview = reinterpret_cast<PrecisionBitmap*>(CALL(renderReuse)(&env, nullptr, handle, text("full"), nullptr));
        require(preview != nullptr, "preview render");
        auto* exported = reinterpret_cast<PrecisionBitmap*>(CALL(exportBitmap)(&env, nullptr, handle, text("full")));
        const bool varied = mode < 5;
        require((exported != nullptr) == varied, "Bitmap non-flat acceptance / true-flat refusal");
        if (varied) require(preview->pixels == exported->pixels, "every exported Bitmap byte matches preview");
        const std::string base = std::string(argv[2]) + "/pattern-" + std::to_string(mode);
        const jlong dimensions = CALL(exportJpeg444)(&env, nullptr, handle, text(base + ".jpg"), text("full"), 95,
            reinterpret_cast<jbyteArray>(&icc));
        require((dimensions != 0) == varied, "streaming JPEG non-flat acceptance / true-flat refusal");
        if (varied) {
            require(uint32_t(uint64_t(dimensions) >> 32) == w && uint32_t(dimensions) == h, "JPEG dimensions");
            const auto bytes = read_bytes(base + ".jpg");
            require(bytes.size() > 4 && bytes[0] == 0xff && bytes[1] == 0xd8 &&
                bytes[bytes.size() - 2] == 0xff && bytes.back() == 0xd9, "complete JPEG markers");
        } else require(!std::filesystem::exists(base + ".jpg"), "flat JPEG left an output file");
        const int fd = open((base + ".tif").c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
        require(fd >= 0, "open TIFF descriptor");
        const bool tiff = CALL(exportTiffFdV1)(&env, nullptr, handle, fd, text(profile), text("sRGB"), text("full"));
        const bool caller_owns_fd = fcntl(fd, F_GETFD) >= 0;
        close(fd);
        require(caller_owns_fd && tiff == varied, "TIFF non-flat acceptance / true-flat refusal / descriptor ownership");
        if (varied) {
            const auto bytes = read_bytes(base + ".tif");
            require(bytes.size() > 8 && bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 42, "TIFF header");
        }
        CALL(release)(&env, nullptr, handle);
        std::cout << "PASS pattern=" << mode << " Bitmap/JPEG/TIFF " << (varied ? "accepted" : "flat refused") << '\n';
    }
    CALL(destroy)(&env, nullptr, engine);
    std::cout << "PASS production JNI: 5 non-flat patterns x 3 exports; flat refusal x 3; exact Bitmap bytes; JPEG completion; TIFF ownership\n";
} catch (const std::exception& error) {
    std::cerr << "FAIL " << error.what() << '\n'; return 1;
}
