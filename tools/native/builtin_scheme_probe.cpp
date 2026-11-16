// Actual built-in recipe wires through production JNI. Co-created with GPT (OpenAI).
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <filesystem>
#include <fstream>
#include <iterator>
#include <iomanip>
#include <cstdlib>

static PrecisionBitmap read_photo(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    std::string magic; uint32_t width = 0, height = 0, maximum = 0;
    stream >> magic >> width >> height >> maximum;
    require(magic == "P6" && width && height && width <= 1024 && height <= 1024 && maximum == 255,
            "bounded RGB8 P6 photo required");
    require(stream.get() == '\n', "P6 header terminator");
    PrecisionBitmap image{{width,height,width*4,ANDROID_BITMAP_FORMAT_RGBA_8888,0},
                          std::vector<uint8_t>(size_t(width)*height*4)};
    for (size_t p = 0; p < size_t(width)*height; ++p) {
        stream.read(reinterpret_cast<char*>(image.pixels.data()+p*4),3);
        image.pixels[p*4+3]=255;
    }
    require(bool(stream) && stream.peek() == EOF,"complete P6 photo");
    return image;
}

static void write_photo(const std::filesystem::path& path, const PrecisionBitmap& bitmap) {
    std::ofstream stream(path,std::ios::binary);
    stream << "P6\n" << bitmap.info.width << ' ' << bitmap.info.height << "\n255\n";
    for (size_t p=0; p<bitmap.pixels.size(); p+=4)
        stream.write(reinterpret_cast<const char*>(bitmap.pixels.data()+p),3);
    require(bool(stream),"write RGB proof");
}

static jsize JNICALL float_length(JNIEnv*, jarray a) {
    return jsize(reinterpret_cast<std::vector<float>*>(a)->size());
}
static void JNICALL float_region(JNIEnv*, jfloatArray a, jsize start, jsize count, jfloat* out) {
    const auto& v = *reinterpret_cast<std::vector<float>*>(a);
    require(start >= 0 && count >= 0 && size_t(start) + size_t(count) <= v.size(), "float bounds");
    std::copy_n(v.data() + start, count, out);
}

int main(int argc, char** argv) try {
    require(argc == 4 || argc == 5, "resources, fixtures, output directory and optional sRGB P6 photo required");
    std::filesystem::create_directories(argv[3]);
    JNINativeInterface_ api{};
    api.GetStringUTFChars = get_string; api.ReleaseStringUTFChars = release_string; api.NewStringUTF = new_string;
    api.ExceptionCheck = exception_check; api.ExceptionClear = exception_clear; api.FindClass = find_class;
    api.GetStaticFieldID = get_field; api.GetStaticMethodID = get_method; api.GetStaticObjectField = get_object;
    api.CallStaticObjectMethodV = allocate_bitmap; api.GetArrayLength = float_length; api.GetFloatArrayRegion = float_region;
    JNIEnv env{&api};
    const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[3]));
    require(engine != 0, "create engine");
    uint32_t w = 96, h = 64;
    PrecisionBitmap input{{w,h,w*4,ANDROID_BITMAP_FORMAT_RGBA_8888,0}, std::vector<uint8_t>(w*h*4)};
    for (uint32_t y=0; y<h; ++y) for (uint32_t x=0; x<w; ++x) {
        auto* p = input.pixels.data() + (y*w+x)*4;
        p[0]=uint8_t(x*255/(w-1)); p[1]=uint8_t(y*255/(h-1)); p[2]=uint8_t(((x/12+y/8)%8)*255/7); p[3]=255;
    }
    const std::string opening = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
    if (argc == 5) { input=read_photo(argv[4]); w=input.info.width; h=input.info.height; }
    const auto originalInput=input.pixels;
    const jlong parent = CALL(openBitmap)(&env,nullptr,engine,reinterpret_cast<jobject>(&input),text("sRGB"),text(opening),0,w,h);
    require(parent != 0, "open source");
    auto* source = reinterpret_cast<Session*>(parent);
    const auto parentParams = *reinterpret_cast<std::string*>(CALL(getParams)(&env,nullptr,parent));
    const auto parentPixels = source->small_rgb;
    if (std::getenv("SPK_METER_ONLY")) {
        require(spk_set_params(source->session, R"({"auto_exposure_method":"balanced"})", nullptr) == SPK_OK,
                "application default meter intent");
        char* reply = nullptr;
        const auto status = spk_solve(source->session, "exposure", &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        require(status == SPK_OK && reply != nullptr, "actual session exposure solve");
        std::ofstream meter(std::filesystem::path(argv[3]) / "meter.json");
        meter << reply << '\n';
        require(bool(meter), "write measured exposure");
        std::ofstream linear(std::filesystem::path(argv[3]) / "input.f32", std::ios::binary);
        linear.write(reinterpret_cast<const char*>(parentPixels.data()), parentPixels.size() * sizeof(float));
        require(bool(linear), "write metered linear source");
        require(source->small_rgb == parentPixels && input.pixels == originalInput && input.locks == 0,
                "meter retained source and released bitmap");
        CALL(release)(&env,nullptr,parent);
        require(g_sessions[reinterpret_cast<spk_engine*>(engine)] == 0, "meter session released");
        CALL(destroy)(&env,nullptr,engine);
        std::cout << "PASS actual session exposure solve; source retained and session released\n";
        return 0;
    }
    std::vector<std::filesystem::path> fixtures;
    for (const auto& file : std::filesystem::directory_iterator(argv[2]))
        if (file.path().extension() == ".json") fixtures.push_back(file.path());
    std::sort(fixtures.begin(),fixtures.end());
    require(fixtures.size()==87,"all 87 built-in fixtures required");
    // Isolate a reproduced photographic issue without rerunning unrelated styles.
    const char* recipeFilter=std::getenv("SPK_RECIPE_FILTER");
    if (recipeFilter) {
        const std::string allowed=","+std::string(recipeFilter)+",";
        std::erase_if(fixtures,[&](const auto& file) { return allowed.find(","+file.stem().string()+",")==std::string::npos; });
        require(!fixtures.empty(),"matching diagnostic recipes required");
        require(CALL(setOutput)(&env,nullptr,parent,text("sRGB"),JNI_FALSE),"neutral source output");
        auto* neutral=reinterpret_cast<PrecisionBitmap*>(CALL(exportBitmap)(&env,nullptr,parent,text("full")));
        require(neutral!=nullptr,"neutral source render");
        write_photo(std::filesystem::path(argv[3])/"neutral.ppm",*neutral);
        bitmap_owners.clear();
    }
    std::ofstream report(std::string(argv[3])+"/results.csv");
    report << "id,output,width,height,bytes,checksum,exact,mean_r,mean_g,mean_b,zero_channels,full_channels\n";
    report << std::setprecision(9);
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
            auto* actual=reinterpret_cast<PrecisionBitmap*>(CALL(exportBitmap)(&env,nullptr,preview,text("full")));
            require(actual && !actual->pixels.empty(),"preview pixels");
            auto pixels=parentPixels;
            SessionPtr reference(open_pixels(source->engine,pixels,source->small_w,source->small_h,recipe));
            require(bool(reference),"ordinary session");
            const auto handle=reinterpret_cast<jlong>(reference.get());
            require(CALL(setLook)(&env,nullptr,handle,reinterpret_cast<jfloatArray>(&look)),"reference grade");
            require(CALL(setOutput)(&env,nullptr,handle,text(output),JNI_FALSE),"reference output");
            auto* expected=reinterpret_cast<PrecisionBitmap*>(CALL(exportBitmap)(&env,nullptr,handle,text("full")));
            require(expected && actual->pixels==expected->pixels,(file.stem().string()+" preview/main byte mismatch").c_str());
            require(actual->info.width==source->small_w && actual->info.height==source->small_h &&
                    expected->info.width==actual->info.width && expected->info.height==actual->info.height,
                    "preview/reference dimensions");
            require(actual->locks==0 && expected->locks==0 && input.locks==0,"all bitmap locks released");
            uint64_t checksum=14695981039346656037ull;
            for (uint8_t v : actual->pixels) { checksum^=v; checksum*=1099511628211ull; }
            uint64_t sum[3]={0,0,0}, zero=0, full=0;
            for (size_t p=0;p<actual->pixels.size();p+=4) {
                require(actual->pixels[p+3]==255,"opaque rendered photograph");
                for (unsigned c=0;c<3;++c) { const auto v=actual->pixels[p+c]; sum[c]+=v; zero+=v==0; full+=v==255; }
            }
            const double count=double(actual->info.width)*actual->info.height;
            report << file.stem().string() << ',' << output << ',' << actual->info.width << ',' << actual->info.height
                   << ',' << actual->pixels.size() << ',' << checksum << ",true," << sum[0]/count << ',' << sum[1]/count
                   << ',' << sum[2]/count << ',' << zero << ',' << full << '\n';
            const std::string id=file.stem().string();
            if (argc==5 && output=="sRGB" && (recipeFilter || id=="neg_portrait" || id=="neg_classic" || id=="neg_night" ||
                id=="slide_velvia" || id=="slide_positive" || id=="bw_dynamic" || id=="ph_authentic" || id=="ph_xmage"))
                write_photo(std::filesystem::path(argv[3])/(id+".ppm"),*actual);
            require(source->small_rgb==parentPixels,"parent source retained");
            require(input.pixels==originalInput,"input bitmap retained");
            require(*reinterpret_cast<std::string*>(CALL(getParams)(&env,nullptr,parent))==parentParams,"parent recipe retained");
            CALL(release)(&env,nullptr,preview);
            reference.reset();
            bitmap_owners.clear();
            string_owners.clear();
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
