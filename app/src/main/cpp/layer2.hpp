// layer2.hpp -- adjustments to the finished print, applied while its pixels
// are packed for display or export. Nothing here reaches the engine.
//
// A port of upstream SpektraLab's Layer 2 (modern_UI Canvas/Shaders.metal,
// `layer2Tone`, and Model/Mask.swift `Layer2Uniforms.tone`), the adjustments a
// scan of a print gets afterwards, in the same order and with the same
// constants:
//
//   white balance -> exposure/contrast/brightness -> highlights/shadows
//   -> black/white point -> saturation (+ vibrance) -> colour balance
//   -> HSL (8 bands) -> fade -> vignette
//
// Vibrance, the HSL bands and fade are this port's additions; everything else
// follows upstream's arithmetic. Upstream pivots on mid-grey in its ProPhoto
// working space; these values are encoded sRGB or Display P3 (the same curve),
// so the pivot is sRGB's encoded 0.18, upstream's own `srgbMidGrey`.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace spk::android {

// Index of each value in the float array Kotlin sends (Look.kt, same order).
enum LookIndex : int {
    kTemperature = 0, kTint, kExposure, kContrast, kBrightness, kSaturation, kVibrance,
    kHighlights, kShadows, kBlackPoint, kWhitePoint, kFade, kVignette, kVignetteMid,
    kCbMaster = 14,          // hue, saturation, luminance; then shadows, midtones, highlights
    kHsl = 26,               // 8 bands x (hue, saturation, luminance)
    kLookSize = 50,
};

class Layer2 {
public:
    // `values` in UI units (Look.kt); fewer than kLookSize, or all neutral, is identity.
    explicit Layer2(const std::vector<float>& values);
    bool identity() const { return identity_; }

    // One pixel, encoded, in place. (u, v) is its position in the frame, 0..1.
    void apply(float& r, float& g, float& b, float u, float v) const;

private:
    bool identity_ = true;
    float wb_[3] = {1, 1, 1};
    float exposure_gain_ = 1;   // in the encoded domain: (2^ev)^(1/2.2)
    float contrast_ = 0, brightness_ = 0, saturation_ = 1, vibrance_ = 0;
    float highlights_ = 0, shadows_ = 0, black_ = 0, white_ = 0, fade_ = 0;
    float vignette_ = 0, vignette_mid_ = 0.5f;
    float cb_[4][3] = {}, cb_lum_[4] = {};
    bool cb_active_ = false;
    float hsl_[8][3] = {};
    bool hsl_active_ = false;
    std::vector<float> bright_lut_;   // c^(1/(1+0.8 b)) over [0, 2]
};

// RGBA16 rows to RGBA8 (display, JPEG) or back into RGBA16 (TIFF), through
// `look`, on up to eight threads.
void pack_rgba8_look(const Layer2& look, const uint16_t* src, size_t src_stride_px,
                     uint32_t w, uint32_t h, uint8_t* out, size_t out_stride);
void apply_rgba16_in_place(const Layer2& look, uint16_t* px, size_t stride_px, uint32_t w, uint32_t h);

}  // namespace spk::android
