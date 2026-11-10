// Co-created with GPT (OpenAI). Strict float-strip ownership/IO and RSS probe.
#include "float_dng_linear.hpp"
#include <chrono>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sys/resource.h>

static void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}
static std::uint64_t hash(const std::vector<float>& pixels) {
    std::uint64_t value = 1469598103934665603ULL;
    for (std::size_t i = 0; i < pixels.size() * sizeof(float); ++i) {
        value ^= reinterpret_cast<const unsigned char*>(pixels.data())[i];
        value *= 1099511628211ULL;
    }
    return value;
}

static void metadata(std::ostream& output, const spk::android::FloatDngLinear& value) {
    output << std::setprecision(17) << value.width << ' ' << value.height << ' '
           << value.source.width << ' ' << value.source.height << ' ' << value.source.orientation;
    const auto array = [&](const auto& values) { for (const auto item : values) output << ' ' << item; };
    array(value.source.black); array(value.source.white); array(value.source.neutral);
    array(value.camera_whitebalance); array(value.applied_whitebalance);
    array(value.camera_to_prophoto); array(value.camera_from_xyz);
    output << ' ' << std::quoted(value.make) << ' ' << std::quoted(value.model)
           << ' ' << std::quoted(value.decoder_name) << '\n';
}

int main(int argc, char** argv) {
    using namespace spk::android;
    try {
        require(argc == 3 || argc == 4, "fixture, mode [scratch path] required");
        const FloatDngLimits limits{16777216, 24ULL * 16777216};
        const std::string mode = argv[2];
        std::string error;
        FloatDngLinear reference;
        const auto start = std::chrono::steady_clock::now();
        int polls = 0;
        require(decode_float_linear_dng(argv[1], limits, reference, error,
            [&] { ++polls; return false; }), error);
        if (mode == "--metadata") {
            metadata(std::cout, reference);
            return 0;
        }
        if (mode == "--memory") {
            rusage usage{};
            require(getrusage(RUSAGE_SELF, &usage) == 0, "getrusage");
            const double elapsed = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - start).count();
            std::cout << std::setprecision(12) << "{\"width\":" << reference.width
                      << ",\"height\":" << reference.height << ",\"hash64\":\"" << std::hex
                      << hash(reference.rgb) << std::dec << "\",\"lifetime_peak_rss_kib\":"
                      << usage.ru_maxrss << ",\"decode_ms\":" << elapsed << "}\n";
            return 0;
        }
        require(argc == 4 && (mode == "--short-read" || mode == "--replace-source"), "invalid mode");
        const std::filesystem::path path(argv[3]);
        std::filesystem::copy_file(argv[1], path, std::filesystem::copy_options::overwrite_existing);
        const auto original_size = std::filesystem::file_size(path);
        // The final source-height polls are the row loop plus the final check.
        // The fixture is wide enough that reading a row bypasses stream buffering.
        require(reference.source.width >= 2048 && reference.source.height >= 4, "wide IO fixture required");
        const int change_at = polls - int(reference.source.height);
        require(change_at > 0, "row poll boundary");
        FloatDngLinear result;
        result.width = 77; result.height = 88; result.rgb = {-9,-3,5}; result.make = "unchanged";
        bool changed = false;
        int seen = 0;
        const auto retired = std::filesystem::path(path.string() + ".retired");
        bool ok = false;
        try {
            ok = decode_float_linear_dng(path, limits, result, error, [&] {
                if (++seen == change_at) {
                    changed = true;
                    if (mode == "--short-read") {
                        std::filesystem::resize_file(path, original_size / 2);
                    } else {
                        std::filesystem::rename(path, retired);
                        std::ofstream replacement(path, std::ios::binary);
                        replacement << "replacement is not a DNG";
                        require(bool(replacement), "replace source path");
                    }
                }
                return false;
            });
        } catch (...) {
            std::filesystem::remove(path);
            std::filesystem::remove(retired);
            throw;
        }
        std::filesystem::remove(path);
        std::filesystem::remove(retired);
        require(changed, "IO mutation reached before row reads");
        if (mode == "--short-read") {
            require(!ok && error.find("strip row read failed") != std::string::npos,
                    "short source must fail explicitly without legacy retry: " + error);
            require(result.width == 77 && result.height == 88 && result.make == "unchanged" &&
                    result.rgb == std::vector<float>({-9,-3,5}), "failed read replaced caller output");
        } else {
            require(ok && result.rgb.size() == reference.rgb.size() &&
                    std::memcmp(result.rgb.data(), reference.rgb.data(), result.rgb.size() * sizeof(float)) == 0,
                    "row reads must retain the descriptor validated with the metadata");
        }
        std::cout << "PASS streaming " << mode << ": source rows=" << reference.source.height
                  << ",polls=" << polls << ",mutation_at=" << change_at << '\n';
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
