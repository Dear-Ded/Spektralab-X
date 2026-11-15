// Actual built-in recipe wires through production JNI. Co-created with GPT (OpenAI).
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <filesystem>
#include <fstream>
#include <iterator>

static jsize JNICALL float_length(JNIEnv*, jarray a) {
    return jsize(reinterpret_cast<std::vector<float>*>(a)->size());
}
static void JNICALL float_region(JNIEnv*, jfloatArray a, jsize start, jsize count, jfloat* out) {
    const auto& v = *reinterpret_cast<std::vector<float>*>(a);
    require(start >= 0 && count >= 0 && size_t(start) + size_t(count) <= v.size(), "float bounds");
    std::copy_n(v.data() + start, count, out);
}

int main(int argc, char** argv) try {
    require(argc == 4, "resources, fixture directory and output directory required");
    std::filesystem::create_directories(argv[3]);
    JNINativeInterface_ api{};
    api.GetStringUTFChars = get_string; api.ReleaseStringUTFChars = release_string; api.NewStringUTF = new_string;
    api.ExceptionCheck = exception_check; api.ExceptionClear = exception_clear; api.FindClass = find_class;
    api.GetStaticFieldID = get_field; api.GetStaticMethodID = get_method; api.GetStaticObjectField = get_object;
    api.CallStaticObjectMethodV = allocate_bitmap; api.GetArrayLength = float_length; api.GetFloatArrayRegion = float_region;
    JNIEnv env{&api};
    const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[3]));
    require(engine != 0, "create engine");
    constexpr uint32_t w = 96, h = 64;
    PrecisionBitmap input{{w,h,w*4,ANDROID_BITMAP_FORMAT_RGBA_8888,0}, std::vector<uint8_t>(w*h*4)};
    for (uint32_t y=0; y<h; ++y) for (uint32_t x=0; x<w; ++x) {
        auto* p = input.pixels.data() + (y*w+x)*4;
        p[0]=uint8_t(x*255/(w-1)); p[1]=uint8_t(y*255/(h-1)); p[2]=uint8_t(((x/12+y/8)%8)*255/7); p[3]=255;
    }
    const std::string opening = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
    const jlong parent = CALL(openBitmap)(&env,nullptr,engine,reinterpret_cast<jobject>(&input),text("sRGB"),text(opening),0,w,h);
    require(parent != 0, "open synthetic source");
    auto* source = reinterpret_cast<Session*>(parent);
    const auto parentParams = *reinterpret_cast<std::string*>(CALL(getParams)(&env,nullptr,parent));
    const auto parentPixels = source->small_rgb;
    std::vector<std::filesystem::path> fixtures;
    for (const auto& file : std::filesystem::directory_iterator(argv[2]))
        if (file.path().extension() == ".json") fixtures.push_back(file.path());
    std::sort(fixtures.begin(),fixtures.end());
    require(fixtures.size()==87,"all 87 built-in fixtures required");
    std::ofstream report(std::string(argv[3])+"/results.csv");
    report << "id,output,bytes,checksum,exact\n";
    unsigned cases=0;
    for (const auto& file : fixtures) {
        std::ifstream stream(file);
        const std::string data{std::istreambuf_iterator<char>(stream),std::istreambuf_iterator<char>()};
        spk::Json row; std::string error;
        require(spk::Json::parse(data,row,error),"parse Kotlin fixture");
        auto params=row.at("params");
        params.set("input_color_space",spk::Json(std::string("ProPhoto RGB")));
        params.set("input_cctf_decoding",spk::Json(false));
        params.set("output_color_space",spk::Json(std::string("ProPhoto RGB")));
        params.set("output_cctf_encoding",spk::Json(true));
        params.set("product_defaults",spk::Json(true));
        const auto recipe=params.dump();
        std::vector<float> look;
        for (const auto& v : row.at("look").items()) look.push_back(float(v.as_double()));
        for (const std::string output : {"sRGB","Display P3"}) {
            const jlong preview=CALL(openPreviewV1)(&env,nullptr,parent,text(recipe));
            require(preview!=0,(file.stem().string()+" preview open").c_str());
            require(CALL(setLook)(&env,nullptr,preview,reinterpret_cast<jfloatArray>(&look)),"complete grade");
            require(CALL(setOutput)(&env,nullptr,preview,text(output),JNI_FALSE),"preview output");
            auto* actual=reinterpret_cast<PrecisionBitmap*>(CALL(renderBitmap)(&env,nullptr,preview,text("full")));
            require(actual && !actual->pixels.empty(),"preview pixels");
            auto pixels=parentPixels;
            SessionPtr reference(open_pixels(source->engine,pixels,w,h,recipe));
            require(bool(reference),"ordinary session");
            const auto handle=reinterpret_cast<jlong>(reference.get());
            require(CALL(setLook)(&env,nullptr,handle,reinterpret_cast<jfloatArray>(&look)),"reference grade");
            require(CALL(setOutput)(&env,nullptr,handle,text(output),JNI_FALSE),"reference output");
            auto* expected=reinterpret_cast<PrecisionBitmap*>(CALL(renderBitmap)(&env,nullptr,handle,text("full")));
            require(expected && actual->pixels==expected->pixels,(file.stem().string()+" preview/main byte mismatch").c_str());
            uint64_t checksum=14695981039346656037ull;
            for (uint8_t v : actual->pixels) { checksum^=v; checksum*=1099511628211ull; }
            report << file.stem().string() << ',' << output << ',' << actual->pixels.size() << ',' << checksum << ",true\n";
            require(source->small_rgb==parentPixels,"parent source retained");
            require(*reinterpret_cast<std::string*>(CALL(getParams)(&env,nullptr,parent))==parentParams,"parent recipe retained");
            CALL(release)(&env,nullptr,preview);
            ++cases;
        }
        std::cout << "PASS " << file.stem().string() << " both outputs\n";
    }
    CALL(release)(&env,nullptr,parent);
    require(g_sessions[reinterpret_cast<spk_engine*>(engine)]==0,"all sessions released");
    CALL(destroy)(&env,nullptr,engine);
    require(bool(report),"write evidence");
    std::cout << "PASS " << cases << " built-in preview/main comparisons; complete recipes and parent isolation\n";
} catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
