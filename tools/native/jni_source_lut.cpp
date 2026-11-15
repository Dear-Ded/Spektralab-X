// Actual JNI source-LUT state transitions. Co-created with GPT (OpenAI).
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"

static jsize JNICALL source_length(JNIEnv*, jarray array) { return jsize(reinterpret_cast<std::vector<float>*>(array)->size()); }
static void JNICALL source_region(JNIEnv*, jfloatArray array, jsize start, jsize count, jfloat* out) {
    const auto& values = *reinterpret_cast<std::vector<float>*>(array);
    require(start >= 0 && count >= 0 && size_t(start) + size_t(count) <= values.size(), "array bounds");
    std::copy_n(values.data() + start, count, out);
}
static jfloatArray source_array(std::vector<float>& values) { return reinterpret_cast<jfloatArray>(&values); }

int main() try {
    JNINativeInterface_ table{};
    table.GetArrayLength = source_length; table.GetFloatArrayRegion = source_region;
    table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear;
    JNIEnv env{&table};
    Session session;
    const auto handle = reinterpret_cast<jlong>(&session);
    std::vector<float> shaper{0, 0, 0, .25f, .25f, .25f, 1, 1, 1};
    std::vector<float> domains{0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1};
    for (const float strength : {0.0f, .37f, 1.0f}) {
        require(CALL(setSourceLutV1)(&env, nullptr, handle, source_array(shaper), nullptr, 0,
            source_array(domains), JNI_FALSE, strength), "set original 1D chain");
        std::vector<float> neutral(50, 0);
        require(CALL(setLook)(&env, nullptr, handle, source_array(neutral)), "setLook retains original chain");
        float r = .5f, g = .5f, b = .5f;
        if (session.active_look()) session.active_look()->apply(r, g, b, .5f, .5f);
        require(std::abs(r - (.5f - .25f * strength)) < 1e-6f, "source strength before sliders");
    }
    const auto* previous = session.active_look();
    domains[3] = 0;
    require(!CALL(setSourceLutV1)(&env, nullptr, handle, source_array(shaper), nullptr, 0,
        source_array(domains), JNI_FALSE, 1), "invalid domain refused");
    require(session.active_look() == previous, "refusal retains source state");
    domains[3] = 1;
    shaper[0] = std::numeric_limits<float>::quiet_NaN();
    require(!CALL(setSourceLutV1)(&env, nullptr, handle, source_array(shaper), nullptr, 0,
        source_array(domains), JNI_FALSE, 1), "nonfinite source refused");
    require(session.active_look() == previous, "nonfinite refusal retains source state");
    require(!CALL(setSourceLutV1)(&env, nullptr, handle, source_array(shaper), nullptr, 130,
        source_array(domains), JNI_FALSE, 1), "oversized side refused before allocation");
    require(CALL(setLut)(&env, nullptr, handle, nullptr, 0, 0), "existing LUT clear clears original chain");
    require(!session.active_look(), "neutral state after clear");
    std::cout << "PASS actual JNI: original 1D chain, strength 0/.37/1, setLook retention, domain/NaN/side refusal, atomic state and legacy clear\n";
    return 0;
} catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
