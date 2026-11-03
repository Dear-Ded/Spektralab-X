#include "output_transform.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <thread>

#include "core/json.hpp"
#include "layer2.hpp"

namespace spk::android {
namespace upstream {
// spow, cctf_decode_mode, cctf_encode_mode and cam16ucs_into, verbatim.
#include "upstream_output_math.inc"
}  // namespace upstream

namespace {

constexpr int kDecodeN = 4096;    // encoded ProPhoto, 0..1
constexpr int kEncodeN = 16384;   // linear target, 0..1

template <typename F>
void rows(uint32_t h, uint64_t px, F&& body) {
    const unsigned workers = px < 500'000 ? 1u : std::min(8u, std::max(1u, std::thread::hardware_concurrency()));
    if (workers == 1) { body(0u, h); return; }
    const uint32_t chunk = (h + workers - 1) / workers;
    std::vector<std::thread> threads;
    uint32_t done = std::min(h, chunk);
    try {
        for (uint32_t y = chunk; y < h; y += chunk) {
            const uint32_t y1 = std::min(h, y + chunk);
            threads.emplace_back(body, y, y1);
            done = y1;
        }
    } catch (...) {
        // Whatever was not spawned runs here.
    }
    body(0u, std::min(h, chunk));
    if (done < h) body(done, h);
    for (auto& t : threads) t.join();
}

inline float lerp_lut(const std::vector<float>& t, int n, float v) {
    const float p = v * n;
    const int i = std::min(n - 1, int(p));
    return t[i] + (t[i + 1] - t[i]) * (p - i);
}

inline float saturate(float x) { return std::min(1.0f, std::max(0.0f, x)); }

}  // namespace

bool OutputTransform::fetch(spk_engine* engine, const std::string& target, bool exporting, std::string& error) {
    ready_ = false;
    char* json = nullptr;
    const float* cmax = nullptr;
    uint32_t count = 0;
    const char* spec = exporting ? "{\"lightness_compression_active\":false}" : "{\"algorithm\":\"off\"}";
    if (spk_output_transform(engine, "ProPhoto RGB", target.c_str(), spec, &json, &cmax, &count) != SPK_OK) {
        const char* e = spk_last_error(engine);
        error = e && *e ? e : "output transform failed";
        return false;
    }
    spk::Json reply;
    const bool parsed = spk::Json::parse(json, reply, error);
    spk_string_free(json);
    if (!parsed) return false;
    dst_ = target;
    src_mode_ = uint32_t(reply.at("source_cctf_mode").as_int());
    dst_mode_ = uint32_t(reply.at("target_cctf_mode").as_int());
    const auto& m = reply.at("matrix").items();
    if (m.size() != 9) { error = "output transform: no matrix"; return false; }
    for (size_t i = 0; i < 9; ++i) matrix_[i] = float(m[i].as_double());
    const spk::Json& g = reply.at("gamut_compress");
    cam16_ = g.at("algorithm").as_string() == "cam16ucs";
    lightness_ = g.at("lightness_compression_active").as_bool();
    if (cam16_) {
        const auto& mx = g.at("m_to_xyz").items();
        const auto& mr = g.at("m_to_rgb").items();
        const auto& k = g.at("k").items();
        if (mx.size() != 9 || mr.size() != 9 || k.size() != 22) { error = "output transform: bad CAM16 block"; return false; }
        for (size_t i = 0; i < 9; ++i) { m2x_[i] = float(mx[i].as_double()); m2r_[i] = float(mr[i].as_double()); }
        for (size_t i = 0; i < 22; ++i) k_[i] = float(k[i].as_double());
        nl_ = uint32_t(g.at("cmax_rows").as_int());
        nh_ = uint32_t(g.at("cmax_cols").as_int());
        cmax_ = cmax;
        if (!cmax_ || nl_ < 2 || nh_ < 2 || size_t(nl_) * nh_ != count) {
            error = "output transform: C_max table size mismatch";
            return false;
        }
    }
    decode16_.resize(65536);
    for (uint32_t i = 0; i < 65536; ++i) decode16_[i] = upstream::cctf_decode_mode(float(i) / 65535.0f, src_mode_);
    decode_lut_.resize(kDecodeN + 1);
    for (int i = 0; i <= kDecodeN; ++i) decode_lut_[i] = upstream::cctf_decode_mode(float(i) / kDecodeN, src_mode_);
    encode_lut_.resize(kEncodeN + 1);
    for (int i = 0; i <= kEncodeN; ++i) encode_lut_[i] = upstream::cctf_encode_mode(float(i) / kEncodeN, dst_mode_);
    ready_ = true;
    return true;
}

// Tables for the display, where the result is 8 bits; the exact curves for a
// file, and for anything outside 0..1 (out-of-gamut and Layer 2 overshoot).
float OutputTransform::decode(float v) const {
    if (cam16_ || exact_ || v < 0.0f || v > 1.0f) return upstream::cctf_decode_mode(v, src_mode_);
    return lerp_lut(decode_lut_, kDecodeN, v);
}

float OutputTransform::encode(float v) const {
    if (cam16_ || exact_ || v < 0.0f || v > 1.0f) return upstream::cctf_encode_mode(v, dst_mode_);
    return lerp_lut(encode_lut_, kEncodeN, v);
}

void OutputTransform::finish(const float v[3], float c[3]) const {
    float lin[3];
    for (int i = 0; i < 3; ++i)
        lin[i] = matrix_[i * 3] * v[0] + matrix_[i * 3 + 1] * v[1] + matrix_[i * 3 + 2] * v[2];
    if (cam16_) upstream::cam16ucs_into(lin, m2x_, m2r_, k_, cmax_, nl_, nh_, lightness_);
    for (int i = 0; i < 3; ++i) c[i] = encode(lin[i]);
}

void OutputTransform::apply(float c[3]) const {
    const float v[3] = {decode(c[0]), decode(c[1]), decode(c[2])};
    finish(v, c);
}

void OutputTransform::apply16(const uint16_t* s, float c[3]) const {
    const float v[3] = {decode16_[s[0]], decode16_[s[1]], decode16_[s[2]]};
    finish(v, c);
}

namespace {
template <typename Write>
void convert(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
             const Layer2* look, const OutputTransform& xf, Write&& write) {
    const float iw = 1.0f / std::max(1u, w), ih = 1.0f / std::max(1u, h);
    rows(h, uint64_t(w) * h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            const uint16_t* s = src + size_t(y) * src_stride_px * 4;
            const float v = (y + 0.5f) * ih;
            for (uint32_t x = 0; x < w; ++x) {
                float c[3];
                if (look) {
                    c[0] = s[4 * x] / 65535.0f; c[1] = s[4 * x + 1] / 65535.0f; c[2] = s[4 * x + 2] / 65535.0f;
                    look->apply(c[0], c[1], c[2], (x + 0.5f) * iw, v);
                    xf.apply(c);
                } else {
                    xf.apply16(s + 4 * x, c);
                }
                write(y, x, c);
            }
        }
    });
}
}  // namespace

void pack_rgba8(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf) {
    convert(src, src_stride_px, w, h, look, xf, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint8_t* d = out + size_t(y) * out_stride + 4 * size_t(x);
        d[0] = uint8_t(std::lround(saturate(c[0]) * 255.0f));
        d[1] = uint8_t(std::lround(saturate(c[1]) * 255.0f));
        d[2] = uint8_t(std::lround(saturate(c[2]) * 255.0f));
        d[3] = 255;
    });
}

void pack_rgba16(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                 std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf) {
    out.assign(size_t(w) * h * 4, 65535);
    convert(src, src_stride_px, w, h, look, xf, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint16_t* d = out.data() + (size_t(y) * w + x) * 4;
        d[0] = uint16_t(std::lround(saturate(c[0]) * 65535.0f));
        d[1] = uint16_t(std::lround(saturate(c[1]) * 65535.0f));
        d[2] = uint16_t(std::lround(saturate(c[2]) * 65535.0f));
    });
}

}  // namespace spk::android
