// output_transform.hpp -- leaving the engine's working space, as upstream does.
//
// The engine renders encoded ProPhoto RGB (spk_open's convention, RFC-018).
// Upstream's hosts convert once per destination, and so does this file:
//
//   display  matrix and clip into sRGB or Display P3 (upstream's view path,
//            `display_transform`, gamut compression off);
//   export   the same matrix, then CAM16-UCS chroma compression into the
//            file's space with the lightness shoulder off -- the film print
//            already applied it (engine.cpp's export branch, the Mac's
//            ColourManagement.swift).
//
// Every number comes from spk_output_transform. The per-pixel arithmetic is
// upstream's own (engine/host/output.cpp), copied in at configure time
// (CMakeLists.txt), so nothing here is a re-derivation.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "spektrafilm/spk_engine.h"

namespace spk::android {

class Layer2;

class OutputTransform {
public:
    // `target` is "sRGB" or "Display P3"; `exporting` picks the export path.
    bool fetch(spk_engine* engine, const std::string& target, bool exporting, std::string& error);
    bool ready() const { return ready_; }
    const std::string& target() const { return dst_; }
    bool exporting() const { return cam16_; }
    // Exact transfer curves everywhere, not the display tables (parity tests).
    void set_exact(bool on) { exact_ = on; }

    // Encoded ProPhoto in, encoded target out (unclamped), in place.
    void apply(float c[3]) const;
    // The same from 16-bit engine codes, through an exact table.
    void apply16(const uint16_t* s, float c[3]) const;

private:
    float decode(float v) const;
    float encode(float v) const;
    void finish(const float v[3], float c[3]) const;

    bool ready_ = false, cam16_ = false, lightness_ = false, exact_ = false;
    std::string dst_;
    uint32_t src_mode_ = 1, dst_mode_ = 0;
    std::array<float, 9> matrix_{}, m2x_{}, m2r_{};
    std::array<float, 22> k_{};
    uint32_t nl_ = 0, nh_ = 0;
    const float* cmax_ = nullptr;
    std::vector<float> decode16_;   // every 16-bit code, exact
    std::vector<float> decode_lut_, encode_lut_;
};

// Engine RGBA16 (encoded ProPhoto) -> Layer 2 (optional) -> `xf` -> RGBA8.
void pack_rgba8(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf);
// The same into RGBA16 with a row stride of `w` pixels (TIFF).
void pack_rgba16(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                 std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf);

}  // namespace spk::android
