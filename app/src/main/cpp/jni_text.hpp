#pragma once

// Co-created with GPT (OpenAI). Metadata uses UTF-8; JNI NewStringUTF uses MUTF-8.
#include <cstdint>
#include <string>
#include <string_view>

namespace spk::android {

template <typename Emit>
inline void utf8_codepoints(std::string_view text, Emit emit) {
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<uint8_t>(text[i]);
        size_t count = first < 0x80 ? 1 : first >= 0xc2 && first <= 0xdf ? 2 :
            first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
        uint32_t cp = count == 1 ? first : first & (0x7f >> count);
        bool valid = count != 0 && count <= text.size() - i;
        for (size_t j = 1; valid && j < count; ++j) {
            const auto next = static_cast<uint8_t>(text[i + j]);
            valid = (next & 0xc0) == 0x80;
            cp = (cp << 6) | (next & 0x3f);
        }
        const uint32_t minimum[] = {0, 0, 0x80, 0x800, 0x10000};
        valid = valid && cp >= minimum[count] && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff);
        emit(valid ? cp : uint32_t('?'));
        i += valid ? count : 1;
    }
}

inline void append_utf8_unit(std::string& out, uint32_t cp) {
    if (cp < 0x80) out += char(cp);
    else if (cp < 0x800) {
        out += char(0xc0 | (cp >> 6));
        out += char(0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
        out += char(0xe0 | (cp >> 12));
        out += char(0x80 | ((cp >> 6) & 0x3f));
        out += char(0x80 | (cp & 0x3f));
    } else {
        out += char(0xf0 | (cp >> 18));
        out += char(0x80 | ((cp >> 12) & 0x3f));
        out += char(0x80 | ((cp >> 6) & 0x3f));
        out += char(0x80 | (cp & 0x3f));
    }
}

inline std::string sanitized_utf8(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    utf8_codepoints(text, [&](uint32_t cp) { append_utf8_unit(out, cp); });
    return out;
}

inline std::string jni_modified_utf8(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    utf8_codepoints(text, [&](uint32_t cp) {
        if (cp == 0) out.append("\xc0\x80", 2);
        else if (cp <= 0xffff) append_utf8_unit(out, cp);
        else {
            cp -= 0x10000;
            append_utf8_unit(out, 0xd800 | (cp >> 10));
            append_utf8_unit(out, 0xdc00 | (cp & 0x3ff));
        }
    });
    return out;
}

} // namespace spk::android
