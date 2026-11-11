// Actual JVM symbol resolution over the production JNI TU, with host platform shims.
// Co-created with GPT (OpenAI), 2026-11-11.
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"

extern "C" JNIEXPORT jlong JNICALL Java_TiffJniContract_open(JNIEnv* env, jclass, jlong engine) {
    const std::string params = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})";
    std::vector<float> rgb(17 * 7 * 3);
    for (size_t p=0; p<rgb.size()/3; ++p) {
        rgb[p*3]=float(p%17+1)/20; rgb[p*3+1]=.1f; rgb[p*3+2]=.8f;
    }
    auto* session = open_pixels(reinterpret_cast<spk_engine*>(engine), rgb,17,7,params);
    if (!session) return 0;
    session->small_w=17; session->small_h=7; session->small_rgb=std::move(rgb);
    return reinterpret_cast<jlong>(session);
}
extern "C" JNIEXPORT jint JNICALL Java_TiffJniContract_descriptor(JNIEnv* env, jclass, jstring path) {
    return open(str(env,path).c_str(),O_CREAT|O_TRUNC|O_RDWR,0600);
}
extern "C" JNIEXPORT jboolean JNICALL Java_TiffJniContract_closeDescriptor(JNIEnv*, jclass, jint fd) {
    const bool valid = fcntl(fd,F_GETFD)>=0;
    if(valid) close(fd);
    return valid;
}
