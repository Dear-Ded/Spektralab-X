// Co-created with GPT (OpenAI). Exhaustive Unicode scalar and malformed metadata regression.
#include "jni_text.hpp"
#include <cstdio>
#include <cstdlib>
#include <vector>
#ifdef SPK_REAL_JNI
#include <jni.h>
#endif

static void require(bool value) { if (!value) std::abort(); }

int main() {
    using namespace spk::android;
    require(jni_modified_utf8("Camera ASCII") == "Camera ASCII");
    require(jni_modified_utf8(std::string("A\0B", 3)) == std::string("A\xc0\x80" "B", 4));
    require(jni_modified_utf8("\xf0\x9f\x93\xb7") == "\xed\xa0\xbd\xed\xb3\xb7");
    const std::vector<std::string> damaged = {
        "\x80", "\xbf", "\xc0\xaf", "\xc1\xbf", "\xe0\x80\xaf", "\xed\xa0\x80",
        "\xed\xbf\xbf", "\xf0\x80\x80\xaf", "\xf4\x90\x80\x80", "\xf5\x80\x80\x80",
        "\xff", "\xc2", "\xe2\x82", "\xf0\x9f\x93", "\xe2\x28\xa1"
    };
    for (const auto& text : damaged) {
        const auto clean = sanitized_utf8(text);
        require(clean.find_first_not_of("?(") == std::string::npos);
        require(jni_modified_utf8(text) == clean);
    }
    std::string all;
    std::vector<uint16_t> expected;
    for (uint32_t cp = 0; cp <= 0x10ffff; ++cp) {
        if (cp >= 0xd800 && cp <= 0xdfff) continue;
        append_utf8_unit(all, cp);
        if (cp <= 0xffff) expected.push_back(uint16_t(cp));
        else {
            expected.push_back(uint16_t(0xd800 | ((cp - 0x10000) >> 10)));
            expected.push_back(uint16_t(0xdc00 | ((cp - 0x10000) & 0x3ff)));
        }
    }
    require(sanitized_utf8(all) == all);
    const auto mutf8 = jni_modified_utf8(all);
    size_t decoded = 0;
    for (size_t i = 0; i < mutf8.size();) {
        const auto c = uint8_t(mutf8[i++]);
        require(c > 0 && c < 0xf0);
        uint32_t unit = c;
        if (c >= 0xc0) {
            const int count = c >= 0xe0 ? 3 : 2;
            unit = c & (0x7f >> count);
            for (int j = 1; j < count; ++j) {
                require(i < mutf8.size() && (uint8_t(mutf8[i]) & 0xc0) == 0x80);
                unit = (unit << 6) | (uint8_t(mutf8[i++]) & 0x3f);
            }
        }
        require(decoded < expected.size() && unit == expected[decoded++]);
    }
    require(decoded == expected.size());
#ifdef SPK_REAL_JNI
    JavaVMOption option{const_cast<char*>("-Xcheck:jni"), nullptr};
    JavaVMInitArgs args{JNI_VERSION_1_8, 1, &option, JNI_FALSE};
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    require(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) == JNI_OK);
    const auto value = env->NewStringUTF(mutf8.c_str());
    require(value && !env->ExceptionCheck());
    require(size_t(env->GetStringLength(value)) == expected.size());
    const auto* chars = env->GetStringChars(value, nullptr);
    require(chars != nullptr);
    for (size_t i = 0; i < expected.size(); ++i) require(chars[i] == expected[i]);
    env->ReleaseStringChars(value, chars);
    env->DeleteLocalRef(value);
    require(vm->DestroyJavaVM() == JNI_OK);
#endif
    std::printf("JNI text: 1112064 Unicode scalars, embedded NUL, 15 malformed sequences passed\n");
}
