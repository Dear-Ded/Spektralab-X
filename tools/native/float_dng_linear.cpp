// Co-created with GPT (OpenAI). Synthetic contract probe for the bounded helper.
#include "float_dng_linear.hpp"
#include <cstring>
#include <iomanip>
#include <iostream>

namespace {
void string(const std::string& value) {
    std::cout << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') std::cout << '\\';
        std::cout << (c < 32 ? '?' : char(c));
    }
    std::cout << '"';
}
template<class T> void array(const T& values) {
    std::cout << '[';
    bool comma = false;
    for (auto value : values) { if (comma) std::cout << ','; std::cout << value; comma = true; }
    std::cout << ']';
}
bool same(const spk::android::FloatDngMetadata& a, const spk::android::FloatDngMetadata& b) {
    return a.width == b.width && a.height == b.height && a.orientation == b.orientation &&
        a.black == b.black && a.white == b.white && a.neutral == b.neutral;
}
}

int main(int argc, char** argv) {
    using namespace spk::android;
    if (argc < 3) return 2;
    FloatDngLimits limits{16777216, 24ULL * 16777216};
    int cancel_inspect = 0, cancel_decode = 0;
    bool throwing_cancel = false;
    std::array<float,4> selected_wb{};
    bool custom_wb = false;
    double temperature = 0;
    std::string open_path = argv[1];
    for (int i = 3; i < argc; ++i) {
        const std::string option = argv[i];
        if (option == "--throw-cancel") throwing_cancel = true;
        else if (option == "--wb" && i + 4 < argc) {
            for (float& value : selected_wb) value = std::stof(argv[++i]);
            custom_wb = true;
        }
        else if (i + 1 >= argc) return 2;
        else if (option == "--temperature") temperature = std::stod(argv[++i]);
        else if (option == "--open-source") open_path = argv[++i];
        else if (option == "--cancel-inspect") cancel_inspect = std::stoi(argv[++i]);
        else if (option == "--cancel-decode") cancel_decode = std::stoi(argv[++i]);
        else if (option == "--max-pixels") limits.max_pixels = std::stoull(argv[++i]);
        else if (option == "--max-bytes") limits.max_float_payload_bytes = std::stoull(argv[++i]);
        else return 2;
    }
    auto raw = std::make_unique<LibRaw>();
    const int open_status = raw->open_file(open_path.c_str());
    if (open_status != LIBRAW_SUCCESS) {
        std::cout << "{\"open_status\":" << open_status << "}\n";
        return 0;
    }
    std::vector<unsigned char> raw_before(sizeof(raw->imgdata));
    std::memcpy(raw_before.data(), &raw->imgdata, raw_before.size());
    FloatDngMetadata inspected{77,88,8,{1,2,3},{4,5,6},{7,8,9}};
    const auto metadata_before = inspected;
    FloatDngLinear result;
    result.width = 77; result.height = 88; result.source = metadata_before;
    result.make = "unchanged"; result.rgb = {-9,-3,5};
    std::string error = "untouched";
    FloatDngSupport support = FloatDngSupport::UnsupportedFloat;
    bool cancelled = false, ok = false;
    int inspect_polls = 0, decode_polls = 0;
    auto poll = [&](int& count, int stop) {
        ++count;
        if (stop && count >= stop) {
            if (throwing_cancel) throw std::runtime_error("cancel callback exception");
            return true;
        }
        return false;
    };
    try {
        support = inspect_float_linear_dng(argv[1], *raw, limits, inspected, error,
            [&] { return poll(inspect_polls, cancel_inspect); });
        if (support == FloatDngSupport::Supported) {
            ok = decode_float_linear_dng(argv[1], limits, custom_wb ? &selected_wb : nullptr, temperature, result, error,
                [&] { return poll(decode_polls, cancel_decode); });
        }
    } catch (const FloatDngCancelled& failure) {
        cancelled = true;
        error = failure.what();
    }
    const bool raw_unchanged = std::memcmp(raw_before.data(), &raw->imgdata, raw_before.size()) == 0;
    const bool result_unchanged = result.width == 77 && result.height == 88 &&
        same(result.source, metadata_before) && result.make == "unchanged" &&
        result.rgb == std::vector<float>({-9,-3,5}) && result.model.empty() && result.decoder_name.empty() &&
        result.camera_whitebalance == std::array<float,4>{} && result.applied_whitebalance == std::array<float,4>{} &&
        result.camera_to_prophoto == std::array<float,12>{} && result.camera_from_xyz == std::array<float,9>{};
    if (ok) {
        std::ofstream output(argv[2], std::ios::binary);
        output.write(reinterpret_cast<const char*>(result.rgb.data()), result.rgb.size() * sizeof(float));
        if (!output) return 3;
    }
    std::cout << std::setprecision(17) << std::boolalpha << "{\"open_status\":" << open_status
        << ",\"support\":";
    string(support == FloatDngSupport::NotFloat ? "NotFloat" :
        support == FloatDngSupport::Supported ? "Supported" : "UnsupportedFloat");
    std::cout << ",\"ok\":" << ok << ",\"cancelled\":" << cancelled
        << ",\"opened_unchanged\":" << raw_unchanged
        << ",\"metadata_unchanged\":" << same(inspected, metadata_before)
        << ",\"result_unchanged\":" << result_unchanged
        << ",\"inspect_polls\":" << inspect_polls << ",\"decode_polls\":" << decode_polls
        << ",\"width\":" << result.width << ",\"height\":" << result.height << ",\"error\":";
    string(error);
    if (ok) {
        std::cout << ",\"source_black\":"; array(result.source.black);
        std::cout << ",\"source_white\":"; array(result.source.white);
        std::cout << ",\"source_neutral\":"; array(result.source.neutral);
        std::cout << ",\"camera_wb\":"; array(result.camera_whitebalance);
        std::cout << ",\"source_wb\":"; array(result.applied_whitebalance);
        std::cout << ",\"post_wb_matrix\":"; array(result.camera_to_prophoto);
    }
    std::cout << "}\n";
    return raw_unchanged && (ok || result_unchanged) ? 0 : 4;
}
