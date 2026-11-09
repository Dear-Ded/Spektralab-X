// Standalone, synthetic RAW display-intent tests. Co-created with GPT. See NOTICE.
#include "raw_development.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace spk::android;
namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
void near(double actual, double expected, double tolerance, const char* message) {
    require(std::abs(actual - expected) <= tolerance, message);
}
float encoded(double value) { return float(value < 1.0/512.0 ? 16.0*value : std::pow(value, 1.0/1.8)); }
double linear(float code) { return code < 16.0f/512.0f ? double(code)/16.0 : std::pow(double(code), 1.8); }
RawDevelopmentMetadata identity() {
    RawDevelopmentMetadata m;
    m.default_black_render_present = true;
    m.default_black_render = 1;
    m.profile_tone_curve_present = true;
    m.profile_tone_curve = {{{0, 0}}, {{1, 1}}};
    return m;
}
RawDevelopment plan(const RawDevelopmentMetadata& meta, float ev = 0) {
    return RawDevelopment::build(meta, {}, {true, false, ev});
}
template<class F> void rejects(F&& body, const char* message) {
    try { body(); } catch (const std::invalid_argument&) { return; }
    throw std::runtime_error(message);
}

void neutral_bypass() {
    uint32_t bits[] = {0x80000000, 0xbf800000, 0x7fc12345};
    float pixel[3];
    std::memcpy(pixel, bits, sizeof(pixel));
    RawDevelopmentMetadata invalid;
    invalid.profile_tone_curve_invalid = true;
    auto p = RawDevelopment::build(invalid, {}, {});
    p.apply_encoded_prophoto(pixel);
    require(!std::memcmp(pixel, bits, sizeof(pixel)), "neutral changed original float bits");
    require(!p.enabled() && p.status() == "neutral", "neutral plan metadata");
    const auto on = plan(identity());
    on.apply_encoded_prophoto(pixel);
    require(!std::memcmp(pixel, bits, sizeof(pixel)), "nonfinite pixel handling changed");
}

void technical_exposure_and_hdr() {
    auto m = identity();
    m.baseline_exposure_present = m.baseline_exposure_offset_present = true;
    m.baseline_exposure = 1;
    m.baseline_exposure_offset = .5;
    auto p = plan(m, -.5f);
    near(p.effective_ev(), 1, 0, "baseline/offset not applied once");
    float c[] = {encoded(.1), encoded(.2), encoded(.3)};
    p.apply_encoded_prophoto(c);
    for (int i = 0; i < 3; ++i) near(linear(c[i]), .2*(i+1), 2e-7, "technical exposure result");
    float clipped[] = {encoded(4), encoded(2), encoded(1)};
    float restored[3];
    std::memcpy(restored, clipped, sizeof(clipped));
    plan(identity()).apply_encoded_prophoto(clipped);
    plan(identity(), -3).apply_encoded_prophoto(restored);
    near(linear(clipped[0]), 1, 1e-7, "embedded SDR endpoint");
    near(linear(restored[0]), .5, 1e-7, "RAW exposure could not restore HDR before tone");
    near(linear(restored[1]), .25, 1e-7, "RAW exposure HDR color recovery");
    float negative[] = {-1, encoded(.1), encoded(.3)};
    plan(identity()).apply_encoded_prophoto(negative);
    near(negative[0], 0, 0, "display boundary negative value");
    // Literal engine CCTF fixtures, independent of the helpers above:
    // linear .0001 is encoded .0016; +2 EV gives .0004, encoded .0064.
    float toe[] = {.0016f, .0016f, .0016f};
    plan(identity(), 2).apply_encoded_prophoto(toe);
    for (float value : toe) near(value, .0064f, 1e-8, "ProPhoto linear toe +2EV fixture");
    float join[] = {.03125f, .03125f, .03125f};
    plan(identity()).apply_encoded_prophoto(join);
    for (float value : join) near(value, .03125f, 1e-8, "ProPhoto toe join fixture");
}

void fallback_curve() {
    RawDevelopmentMetadata m;
    m.default_black_render_present = true;
    m.default_black_render = 1;
    const auto p = plan(m);
    require(std::string(p.curve_name()) == "loglogistic_v1", "wrong fallback");
    float previous = -1;
    for (int i = -100; i <= 100; ++i) {
        const double value = std::exp2(i*.25);
        float c[] = {encoded(value), encoded(value), encoded(value)};
        p.apply_encoded_prophoto(c);
        require(std::isfinite(c[0]) && c[0] >= previous && c[0] >= 0 && c[0] <= 1, "fallback nonmonotone/unbounded HDR");
        require(c[0] == c[1] && c[1] == c[2], "neutral gray changed chromaticity");
        previous = c[0];
    }
    float grey[] = {encoded(.1845), encoded(.1845), encoded(.1845)};
    p.apply_encoded_prophoto(grey);
    near(linear(grey[0]), .1845, 1e-7, "fallback middle grey anchor");
    float colors[] = {encoded(.05), encoded(.2), encoded(.5)};
    p.apply_encoded_prophoto(colors);
    near((linear(colors[1])-linear(colors[0]))/(linear(colors[2])-linear(colors[0])),
         (.2-.05)/(.5-.05), 2e-7, "tone changed middle-channel hue ratio");
    float huge[] = {std::numeric_limits<float>::max(), std::numeric_limits<float>::max(), std::numeric_limits<float>::max()};
    p.apply_encoded_prophoto(huge);
    require(huge[0] == 1 && huge[1] == 1 && huge[2] == 1, "finite extreme HDR overflow");
}

void embedded_shape() {
    auto m = identity();
    m.profile_tone_curve = {{{0, 0}}, {{.15f, .03f}}, {{.4f, .3f}}, {{.7f, .8f}}, {{1, .997156f}}};
    const auto p = plan(m);
    float previous = 0;
    for (int i = 0; i <= 4096; ++i) {
        float c[] = {encoded(i/4096.0), encoded(i/4096.0), encoded(i/4096.0)};
        p.apply_encoded_prophoto(c);
        require(c[0] >= previous && std::isfinite(c[0]), "embedded monotone overshoot");
        previous = c[0];
    }
    for (const auto& xy : m.profile_tone_curve) {
        float c[] = {encoded(xy[0]), encoded(xy[0]), encoded(xy[0])};
        p.apply_encoded_prophoto(c);
        near(linear(c[0]), xy[1], 2e-7, "embedded control point changed");
    }
    float white[] = {1, 1, 1};
    p.apply_encoded_prophoto(white);
    near(linear(white[0]), .997156f, 1e-7, "embedded endpoint forced to one");
    // A legal nonmonotone profile stays within its adjacent extrema as well.
    m.profile_tone_curve = {{{0, 0}}, {{.25f, .4f}}, {{.5f, .2f}}, {{1, 1}}};
    const auto nonmonotone = plan(m);
    for (int i = 256; i <= 512; ++i) {
        float c[] = {encoded(i/1024.0), encoded(i/1024.0), encoded(i/1024.0)};
        nonmonotone.apply_encoded_prophoto(c);
        require(linear(c[0]) >= .2-1e-7 && linear(c[0]) <= .4+1e-7, "profile local extrema overshoot");
    }
}

void whole_frame_statistics() {
    constexpr unsigned w = 37, h = 29, stride = 41;
    std::vector<float> tight(w*h*3), padded(stride*h*3, std::numeric_limits<float>::quiet_NaN());
    for (size_t i = 0; i < tight.size()/3; ++i) {
        const float v = float(std::exp2(-8.0 + (i%101)/12.0));
        for (int c = 0; c < 3; ++c) tight[3*i+c] = v;
    }
    for (unsigned y = 0; y < h; ++y) std::copy_n(tight.data()+y*w*3, w*3, padded.data()+y*stride*3);
    const auto a = raw_development_stats(tight.data(), w, w, h);
    const auto b = raw_development_stats(padded.data(), stride, w, h);
    require(a.histogram == b.histogram && a.log2_mean == b.log2_mean && a.valid_pixels == w*h,
            "stride/padding changed complete-frame statistics");
    auto meta = identity();
    meta.baseline_exposure_present = true;
    meta.baseline_exposure = 1;
    const auto p = RawDevelopment::build(meta, a, {true, true, 0});
    const double expected_auto = std::clamp(std::log2(.1845)-a.log2_mean-1, -4.0, 4.0);
    near(p.auto_ev(), expected_auto, 1e-14, "baseline double-counted in AE");
    std::vector<float> black(24, 0);
    black[3] = std::numeric_limits<float>::quiet_NaN();
    const auto empty = raw_development_stats(black.data(), 4, 4, 2);
    require(!empty.available && empty.valid_pixels == 0 && empty.invalid_pixels == 1 && empty.dark_pixels == 7,
            "dark/nonfinite samples polluted AE");
    const auto no_samples = RawDevelopment::build(identity(), empty, {true,true,0});
    require(no_samples.auto_ev() == 0 && no_samples.status().find("no_samples") != std::string::npos,
            "empty histogram produced invented auto exposure");
}

void frozen_plan_strip_and_thread_parity() {
    std::vector<float> source(10001*3);
    for (size_t i = 0; i < source.size(); ++i) source[i] = float(std::exp2(-10+(i%307)/25.0));
    const auto stats = raw_development_stats(source.data(), 10001, 10001, 1);
    auto p = RawDevelopment::build(identity(), stats, {true,true,-.25f});
    for (float& v : source) v = encoded(v);
    auto full = source, strips = source;
    for (size_t i = 0; i < full.size(); i += 3) p.apply_encoded_prophoto(full.data()+i);
    std::vector<std::thread> workers;
    for (size_t y = 0; y < 10001; y += 321) {
        workers.emplace_back([&, y] {
            for (size_t i = y; i < std::min(size_t(10001), y+321); ++i)
                p.apply_encoded_prophoto(strips.data()+3*i);
        });
    }
    for (auto& thread : workers) thread.join();
    require(!std::memcmp(full.data(), strips.data(), full.size()*sizeof(float)), "strip/thread dependent development");
}

void metadata_rejection_and_status() {
    auto m = identity();
    m.extra_camera_profiles = true;
    rejects([&] { plan(m); }, "extra profile silently selected");
    m = identity(); m.profile_source_mismatch = true;
    rejects([&] { plan(m); }, "mismatched profile silently selected");
    m = identity(); m.profile_tone_curve[1][0] = 0;
    rejects([&] { plan(m); }, "duplicate curve x accepted");
    m = identity(); m.profile_tone_curve_invalid = true;
    rejects([&] { plan(m); }, "invalid curve replaced by fallback");
    rejects([&] { plan(identity(), 9); }, "unbounded user EV accepted");
    rejects([&] { plan(identity(), std::numeric_limits<float>::quiet_NaN()); }, "NaN EV accepted");
    m = identity(); m.baseline_exposure_present = true; m.baseline_exposure = 10000;
    rejects([&] { plan(m); }, "overflow baseline accepted");
    m = identity(); m.default_black_render = 0;
    require(plan(m).status().find("black_auto_not_applied") != std::string::npos, "Auto black rendering silently invented");
}

void cancellation() {
    std::atomic<uint64_t> generation{0};
    OutputCancel token(generation);
    ++generation;
    float pixel[] = {.2f,.2f,.2f};
    bool cancelled = false;
    try { raw_development_stats(pixel, 1, 1, 1, token); }
    catch (const std::runtime_error& e) { cancelled = std::string(e.what()) == "render cancelled"; }
    require(cancelled, "pre-cancelled histogram scan did work");
}
}  // namespace

int main() {
    try {
        neutral_bypass(); technical_exposure_and_hdr(); fallback_curve(); embedded_shape();
        whole_frame_statistics(); frozen_plan_strip_and_thread_parity(); metadata_rejection_and_status(); cancellation();
        std::cout << "raw_development: 8 groups passed (synthetic; no Android acceptance)\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "raw_development: " << e.what() << '\n';
        return 1;
    }
}
