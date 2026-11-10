#pragma once

// Co-created with GPT (OpenAI). Bounded float LinearRaw input, not a general DNG decoder.
#include "io/raw_camera.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace spk::android {

struct FloatDngLimits {
    std::uint64_t max_pixels = 0;
    // Conservative historical input+output payload admission, retained even
    // when rows avoid a complete input float3. This is not a process RSS budget.
    std::uint64_t max_float_payload_bytes = 0;
};

struct FloatDngMetadata {
    std::uint32_t width = 0, height = 0, orientation = 1;
    std::array<double, 3> black{}, white{}, neutral{};
};

struct FloatDngLinear {
    std::uint32_t width = 0, height = 0;
    FloatDngMetadata source;
    std::array<float, 4> camera_whitebalance{}, applied_whitebalance{};
    std::array<float, 12> camera_to_prophoto{};
    std::array<float, 9> camera_from_xyz{};
    std::string make, model, decoder_name;
    // Oriented, scene-linear ProPhoto D50; finite signed/above-white values are
    // retained. No demosaic, display curve, clipping or automatic exposure.
    std::vector<float> rgb;
};

using FloatDngCancel = std::function<bool()>;

enum class FloatDngSupport { NotFloat, Supported, UnsupportedFloat };

// Cancellation must never be mistaken for an unsupported file and retried by
// the outer dispatcher through its legacy, integer-converting decoder.
class FloatDngCancelled final : public std::runtime_error {
public:
    FloatDngCancelled() : std::runtime_error("float DNG decode cancelled") {}
};

namespace float_dng_detail {
inline void require(bool valid, const char* message) {
    if (!valid) throw std::runtime_error(message);
}
inline void check_cancel(const FloatDngCancel& cancelled) {
    if (!cancelled) return;
    try { if (!cancelled()) return; }
    catch (...) { throw FloatDngCancelled(); }
    throw FloatDngCancelled();
}
inline unsigned word(const unsigned char* p) {
    return unsigned(p[0]) | (unsigned(p[1]) << 8);
}
inline std::uint32_t dword(const unsigned char* p) {
    return std::uint32_t(word(p)) | (std::uint32_t(word(p + 2)) << 16);
}
struct Tag {
    unsigned type = 0;
    std::uint32_t count = 0;
    std::vector<unsigned char> data;
};

struct StripInput {
    std::ifstream file;
    std::uint64_t offset = 0;
};

inline FloatDngMetadata metadata(const std::filesystem::path& path, const FloatDngLimits& limits,
                                 const FloatDngCancel& cancelled, StripInput* input = nullptr) {
    require(limits.max_pixels && limits.max_float_payload_bytes, "float DNG requires explicit payload limits");
    check_cancel(cancelled);
    std::ifstream file(path, std::ios::binary);
    require(bool(file), "cannot open float DNG metadata");
    file.seekg(0, std::ios::end);
    const auto end = file.tellg();
    require(end >= 8, "invalid float DNG file length");
    const auto bytes = std::uint64_t(end);
    std::vector<std::pair<std::uint64_t, std::uint64_t>> metadata_ranges{{0, 8}};
    auto read = [&](std::uint64_t offset, std::uint32_t length) {
        check_cancel(cancelled);
        require(offset <= bytes && length <= bytes - offset, "float DNG metadata outside file");
        std::vector<unsigned char> data(length);
        file.seekg(std::streamoff(offset));
        file.read(reinterpret_cast<char*>(data.data()), length);
        require(bool(file), "float DNG metadata read failed");
        return data;
    };
    const auto header = read(0, 8);
    require(header[0] == 'I' && header[1] == 'I' && word(header.data() + 2) == 42,
            "unsupported float DNG: requires little-endian classic TIFF");
    const auto offset = dword(header.data() + 4);
    require(offset >= 8, "invalid float DNG directory offset");
    const auto count_bytes = read(offset, 2);
    const unsigned count = word(count_bytes.data());
    require(count > 0 && count <= 128, "unsupported float DNG directory size");
    const auto entries = read(std::uint64_t(offset) + 2, count * 12 + 4);
    require(dword(entries.data() + count * 12) == 0, "unsupported float DNG: requires one IFD");
    metadata_ranges.emplace_back(offset, std::uint64_t(offset) + 2 + count * 12 + 4);
    std::map<unsigned, Tag> tags;
    constexpr unsigned allowed[] = {254,256,257,258,259,262,273,274,277,278,279,284,339,
        271,272,50706,50707,50708,50713,50714,50717,50721,50728,50778,50829,50964};
    for (unsigned i = 0; i < count; ++i) {
        const auto* p = entries.data() + i * 12;
        const unsigned id = word(p), type = word(p + 2);
        const auto n = dword(p + 4);
        require(std::find(std::begin(allowed), std::end(allowed), id) != std::end(allowed),
                "unsupported float DNG tag: crop, calibration, opcode or profile is not supported");
        const unsigned size = type == 1 || type == 2 || type == 7 ? 1 : type == 3 ? 2 :
            type == 4 ? 4 : type == 5 || type == 10 ? 8 : 0;
        require(size && n && std::uint64_t(n) * size <= 4096, "unsupported float DNG tag encoding or size");
        require(!tags.count(id), "duplicate float DNG tag");
        const auto length = n * size;
        auto data = length <= 4 ? std::vector<unsigned char>(p + 8, p + 8 + length) : read(dword(p + 8), length);
        if (length > 4) metadata_ranges.emplace_back(dword(p + 8), std::uint64_t(dword(p + 8)) + length);
        tags.emplace(id, Tag{type, n, std::move(data)});
    }
    auto tag = [&](unsigned id) -> const Tag& {
        const auto found = tags.find(id);
        require(found != tags.end(), "required float DNG tag missing");
        return found->second;
    };
    auto values = [&](unsigned id) {
        const auto& t = tag(id);
        require(t.type == 1 || t.type == 3 || t.type == 4 || t.type == 5 || t.type == 10,
                "unsupported float DNG numeric tag");
        const unsigned size = t.type == 1 ? 1 : t.type == 3 ? 2 : t.type == 4 ? 4 : 8;
        std::vector<double> result;
        result.reserve(t.count);
        for (unsigned i = 0; i < t.count; ++i) {
            const auto* p = t.data.data() + i * size;
            if (t.type == 5 || t.type == 10) {
                const double numerator = t.type == 5 ? double(dword(p)) : double(std::int32_t(dword(p)));
                const double denominator = t.type == 5 ? double(dword(p + 4)) : double(std::int32_t(dword(p + 4)));
                require(denominator != 0, "invalid float DNG rational denominator");
                result.push_back(numerator / denominator);
            } else result.push_back(t.type == 1 ? p[0] : t.type == 3 ? word(p) : dword(p));
        }
        return result;
    };
    auto scalar = [&](unsigned id, double expected) {
        const auto v = values(id);
        require(v.size() == 1 && v[0] == expected, "unsupported float DNG sample layout");
    };
    require(tag(254).type == 4, "invalid float DNG subfile type encoding");
    for (const unsigned id : {258u,259u,262u,274u,277u,284u,339u,50713u})
        require(tag(id).type == 3, "invalid float DNG SHORT tag encoding");
    for (const unsigned id : {256u,257u,278u})
        require(tag(id).type == 3 || tag(id).type == 4, "invalid float DNG dimension encoding");
    require(tag(50829).type == 4, "invalid float DNG ActiveArea encoding");
    for (const unsigned id : {271u,272u,50708u})
        if (tags.count(id)) require(tag(id).type == 2, "invalid float DNG camera text encoding");
    scalar(254, 0); scalar(259, 1); scalar(262, 34892); scalar(277, 3); scalar(284, 1);
    require(values(258) == std::vector<double>({32,32,32}) && values(339) == std::vector<double>({3,3,3}),
            "unsupported float DNG: requires float32 RGB samples");
    for (const unsigned id : {50706u, 50707u})
        require(tag(id).type == 1 && tag(id).count == 4, "invalid float DNG version encoding");
    require(tag(50706).data >= std::vector<unsigned char>({1,4,0,0}) &&
            tag(50707).data == std::vector<unsigned char>({1,4,0,0}),
            "unsupported float DNG backward version");
    FloatDngMetadata m;
    const auto w = values(256), h = values(257), orientation = values(274);
    require(w.size() == 1 && h.size() == 1 && w[0] >= 1 && h[0] >= 1 && w[0] <= 65535 && h[0] <= 65535 &&
            std::floor(w[0]) == w[0] && std::floor(h[0]) == h[0], "invalid float DNG dimensions");
    m.width = std::uint32_t(w[0]); m.height = std::uint32_t(h[0]);
    const auto pixels = std::uint64_t(m.width) * m.height;
    require(pixels <= limits.max_pixels && pixels <= limits.max_float_payload_bytes / 24 &&
            pixels <= std::numeric_limits<std::size_t>::max() / (3 * sizeof(float)),
            "float DNG input/output payload exceeds budget");
    require(orientation.size() == 1 && orientation[0] >= 1 && orientation[0] <= 8 &&
            std::floor(orientation[0]) == orientation[0], "invalid float DNG orientation");
    m.orientation = std::uint32_t(orientation[0]);
    require(values(50829) == std::vector<double>({0,0,double(m.height),double(m.width)}),
            "unsupported float DNG: requires full ActiveArea");
    require(values(50713) == std::vector<double>({1,1}), "unsupported float DNG: requires constant black");
    scalar(278, m.height);
    const auto strip = values(273), strip_bytes = values(279);
    require(tag(273).type == 4 && tag(279).type == 4 && strip.size() == 1 && strip_bytes.size() == 1 &&
            strip_bytes[0] == double(pixels * 12), "unsupported float DNG strip layout");
    const auto begin_pixels = std::uint64_t(strip[0]), end_pixels = begin_pixels + pixels * 12;
    require(begin_pixels <= bytes && pixels * 12 <= bytes - begin_pixels, "float DNG strip outside file");
    for (const auto& range : metadata_ranges)
        require(end_pixels <= range.first || begin_pixels >= range.second, "float DNG pixels overlap metadata");
    for (const unsigned id : {50721u, 50964u}) {
        require(tag(id).type == 10 && tag(id).count == 9, "unsupported float DNG: requires single CM/FM");
        (void)values(id); // Validate every SRATIONAL before LibRaw can treat a zero denominator as a default.
    }
    require(tag(50778).type == 3 && tag(50778).count == 1, "invalid float DNG calibration illuminant");
    require(tag(50714).type == 5 && (tag(50717).type == 3 || tag(50717).type == 4) &&
            tag(50728).type == 5 && tag(50728).count == 3, "unsupported float DNG black, white or neutral encoding");
    const auto black = values(50714), white = values(50717), neutral = values(50728);
    require((black.size() == 1 || black.size() == 3) && white.size() == 3,
            "unsupported float DNG: requires constant per-plane black/white");
    for (unsigned c = 0; c < 3; ++c) {
        m.black[c] = black[black.size() == 1 ? 0 : c]; m.white[c] = white[c]; m.neutral[c] = neutral[c];
        require(std::isfinite(m.black[c]) && std::isfinite(m.white[c]) && m.white[c] > m.black[c],
                "invalid float DNG black/white range");
        require(std::isfinite(m.neutral[c]) && m.neutral[c] > 0, "invalid float DNG AsShotNeutral");
    }
    const double maximum = *std::max_element(m.neutral.begin(), m.neutral.end());
    for (double v : m.neutral) require(v / maximum >= .001, "float DNG neutral outside supported FM domain");
    check_cancel(cancelled);
    if (input) {
        input->offset = begin_pixels;
        input->file = std::move(file);
    }
    return m;
}

struct Progress {
    const FloatDngCancel& cancelled;
    bool stopped = false;
    static int poll(void* context, enum LibRaw_progress, int, int) noexcept {
        auto& self = *static_cast<Progress*>(context);
        try { self.stopped = self.stopped || (self.cancelled && self.cancelled()); }
        catch (...) { self.stopped = true; }
        return self.stopped ? 1 : 0;
    }
};
} // namespace float_dng_detail

// The already-open decoder is inspected without mutation. Integer RAW returns
// before opening path, polling cancellation or touching either output argument.
// Supported describes the metadata/layout contract; matrix and sample validity
// are checked during decode. Metadata is replaced only on Supported.
inline FloatDngSupport inspect_float_linear_dng(const std::filesystem::path& path, LibRaw& opened,
                                               const FloatDngLimits& limits, FloatDngMetadata& result,
                                               std::string& reason, const FloatDngCancel& cancelled = {}) {
    if (!opened.is_floating_point()) return FloatDngSupport::NotFloat;
    try {
        float_dng_detail::check_cancel(cancelled);
        float_dng_detail::require(opened.imgdata.idata.dng_version && !opened.imgdata.idata.filters &&
            opened.imgdata.idata.colors == 3 && !opened.imgdata.color.as_shot_wb_applied,
            "unsupported float DNG camera layout or preapplied WB");
        auto candidate = float_dng_detail::metadata(path, limits, cancelled);
        result = candidate;
        reason.clear();
        return FloatDngSupport::Supported;
    } catch (const FloatDngCancelled&) {
        throw;
    } catch (const std::exception& failure) {
        reason = std::string("float LinearRaw: ") + failure.what();
        return FloatDngSupport::UnsupportedFloat;
    }
}

// Custom WB uses the same normalized-camera domain as original AsShotNeutral:
// positive relative RGB multipliers, before any per-plane black/white scaling.
// A nonzero temperature must accompany custom WB and selects its calibration.
// Unsupported float input may retain the outer dispatcher's explicitly labelled legacy
// quantized route; never describe that fallback as preserving float precision.
// No LibRaw global/default option is changed. Failure preserves result; a
// cancelled request throws FloatDngCancelled instead of suggesting a fallback.
inline bool decode_float_linear_dng(const std::filesystem::path& path, const FloatDngLimits& limits,
                                    const std::array<float, 4>* user_mul, double white_temperature,
                                    FloatDngLinear& result, std::string& error,
                                    const FloatDngCancel& cancelled = {}) {
    using namespace float_dng_detail;
    error.clear();
    try {
        require(std::isfinite(white_temperature) && (white_temperature == 0 ||
                (user_mul && white_temperature >= 1700 && white_temperature <= 50000)),
                "invalid float DNG white temperature");
        if (user_mul) {
            for (float value : *user_mul)
                require(value > 0 && std::isfinite(value), "invalid float DNG custom white balance");
            const double maximum = *std::max_element(user_mul->begin(), user_mul->begin() + 3);
            for (unsigned c = 0; c < 3; ++c)
                require((*user_mul)[c] / maximum >= .001, "float DNG custom white balance outside supported FM domain");
        }
        FloatDngLinear candidate;
        StripInput input;
        candidate.source = metadata(path, limits, cancelled, &input);
        const auto& m = candidate.source;
        auto raw = std::make_unique<io::detail::CameraLibRaw>();
        Progress progress{cancelled};
        raw->set_progress_handler(&Progress::poll, &progress);
        raw->imgdata.params.use_camera_matrix = 1;
        raw->imgdata.params.output_color = 4;
        raw->imgdata.rawparams.options &= ~LIBRAW_RAWOPTIONS_CONVERTFLOAT_TO_INT;
        const int opened = raw->open_file(path.c_str());
        check_cancel(cancelled);
        if (progress.stopped) throw FloatDngCancelled();
        require(opened == LIBRAW_SUCCESS, "float DNG LibRaw open failed");
        require(raw->is_floating_point() && raw->imgdata.idata.dng_version &&
                !raw->imgdata.idata.filters && raw->imgdata.idata.colors == 3 &&
                !raw->imgdata.color.as_shot_wb_applied, "unsupported float DNG camera layout or preapplied WB");
        // open.cpp has already folded integer plane ranges into cam_mul. Replace
        // it with original ASN so independent float plane normalization is applied once.
        for (unsigned c = 0; c < 3; ++c)
            candidate.camera_whitebalance[c] = raw->imgdata.color.cam_mul[c] = float(1 / m.neutral[c]);
        candidate.camera_whitebalance[3] = raw->imgdata.color.cam_mul[3] = raw->imgdata.color.cam_mul[1];
        require(raw->interpolate_dng_matrix(user_mul, white_temperature), "float DNG prepared matrix unavailable");
        const auto& selected_whitebalance = user_mul ? *user_mul : candidate.camera_whitebalance;
        const double minimum = *std::min_element(selected_whitebalance.begin(), selected_whitebalance.begin() + 3);
        require(minimum > 0 && std::isfinite(minimum), "invalid float DNG source WB");
        for (unsigned c = 0; c < 3; ++c)
            candidate.applied_whitebalance[c] = raw->imgdata.color.pre_mul[c] = float(selected_whitebalance[c] / minimum);
        candidate.applied_whitebalance[3] = raw->imgdata.color.pre_mul[3] = raw->imgdata.color.pre_mul[1];
        float matrix[3][4];
        for (auto& row : matrix) for (float& value : row) value = std::numeric_limits<float>::quiet_NaN();
        raw->apply_dng_forward_matrix(matrix);
        for (unsigned r = 0; r < 3; ++r) for (unsigned c = 0; c < 4; ++c) {
            require(std::isfinite(matrix[r][c]), "float DNG prepared FM unavailable");
            candidate.camera_to_prophoto[r * 4 + c] = matrix[r][c];
        }
        for (unsigned r = 0; r < 3; ++r) for (unsigned c = 0; c < 3; ++c)
            candidate.camera_from_xyz[r * 3 + c] = raw->imgdata.color.cam_xyz[r][c];
        check_cancel(cancelled);
        const auto& data = raw->imgdata;
        require(raw->error_count() == 0, "float DNG metadata read failed");
        require(data.sizes.raw_width == m.width && data.sizes.raw_height == m.height &&
                data.sizes.width == m.width && data.sizes.height == m.height &&
                data.sizes.top_margin == 0 && data.sizes.left_margin == 0 && data.sizes.pixel_aspect == 1,
                "unsupported float DNG source geometry");
        require(raw->unpack_function_name() &&
                std::string(raw->unpack_function_name()) == "uncompressed_fp_dng_load_raw()",
                "unsupported float DNG decoder");
        candidate.width = m.orientation >= 5 ? m.height : m.width;
        candidate.height = m.orientation >= 5 ? m.width : m.height;
        candidate.make = data.idata.make; candidate.model = data.idata.model;
        if (const char* name = raw->unpack_function_name()) candidate.decoder_name = name;
        check_cancel(cancelled);
        candidate.rgb.resize(std::size_t(m.width) * m.height * 3);
        // The validated v1 strip is LE float32 chunky RGB without a predictor.
        // LibRaw's matching unpacker only copies these words and scans fmaximum;
        // the scene-linear path does not use that maximum or convertFloatToInt.
        static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559);
        std::vector<unsigned char> row(std::size_t(m.width) * 12);
        input.file.seekg(std::streamoff(input.offset));
        require(bool(input.file), "float DNG strip seek failed");
        for (unsigned y = 0; y < m.height; ++y) {
            check_cancel(cancelled);
            input.file.read(reinterpret_cast<char*>(row.data()), std::streamsize(row.size()));
            require(bool(input.file), "float DNG strip row read failed");
            for (unsigned x = 0; x < m.width; ++x) {
                unsigned dx = x, dy = y;
                switch (m.orientation) {
                    case 2: dx = m.width - 1 - x; break;
                    case 3: dx = m.width - 1 - x; dy = m.height - 1 - y; break;
                    case 4: dy = m.height - 1 - y; break;
                    case 5: dx = y; dy = x; break;
                    case 6: dx = m.height - 1 - y; dy = x; break;
                    case 7: dx = m.height - 1 - y; dy = m.width - 1 - x; break;
                    case 8: dx = y; dy = m.width - 1 - x; break;
                }
                std::array<double, 3> balanced{};
                for (unsigned c = 0; c < 3; ++c) {
                    const auto bits = dword(row.data() + std::size_t(x) * 12 + c * 4);
                    float stored;
                    std::memcpy(&stored, &bits, sizeof stored);
                    const double sample = stored;
                    require(std::isfinite(sample), "nonfinite float DNG sample");
                    balanced[c] = (sample - m.black[c]) / (m.white[c] - m.black[c]) * candidate.applied_whitebalance[c];
                }
                for (unsigned r = 0; r < 3; ++r) {
                    double value = 0;
                    for (unsigned c = 0; c < 3; ++c) value += matrix[r][c] * balanced[c];
                    require(std::isfinite(value) && std::fabs(value) <= std::numeric_limits<float>::max(),
                            "float DNG output exceeds finite float range");
                    candidate.rgb[(std::size_t(dy) * candidate.width + dx) * 3 + r] = float(value);
                }
            }
        }
        check_cancel(cancelled);
        result = std::move(candidate);
        return true;
    } catch (const FloatDngCancelled&) {
        throw;
    } catch (const std::exception& failure) {
        error = std::string("float LinearRaw: ") + failure.what();
        return false;
    }
}

// Preserve the original as-shot API and arithmetic for existing callers.
inline bool decode_float_linear_dng(const std::filesystem::path& path, const FloatDngLimits& limits,
                                    FloatDngLinear& result, std::string& error,
                                    const FloatDngCancel& cancelled = {}) {
    return decode_float_linear_dng(path, limits, nullptr, 0, result, error, cancelled);
}
} // namespace spk::android
