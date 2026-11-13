// Standalone, synthetic RAW display-intent tests. Co-created with GPT. See NOTICE.
#include "raw_development.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <chrono>
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

void lazy_statistics() {
    RawDevelopmentStats out;
    out.valid_pixels = 99;
    // Null/invalid geometry must never reach the scanner on either bypass.
    require(!prepare_raw_development_stats({}, true, nullptr, 0, 0, 0, out) &&
            out.valid_pixels == 0 && !out.available, "neutral scanned pixels or kept stale statistics");
    out.valid_pixels = 99;
    require(!prepare_raw_development_stats({true,true,0}, false, nullptr, 0, 0, 0, out) &&
            out.valid_pixels == 0 && !out.available, "physical film scanned pixels");
    const float pixels[] = {.1f,.2f,.3f, 0,0,0};
    const auto expected = raw_development_stats(pixels, 2, 2, 1);
    require(prepare_raw_development_stats({true,false,0}, true, pixels, 2, 2, 1, out),
            "standard with AE disabled did not prepare future AE statistics");
    require(out.histogram == expected.histogram && out.log2_mean == expected.log2_mean &&
            out.valid_pixels == expected.valid_pixels && out.dark_pixels == expected.dark_pixels &&
            out.invalid_pixels == expected.invalid_pixels && out.available == expected.available,
            "lazy statistics changed scanner result");
    rejects([&] { prepare_raw_development_stats({true,true,0}, true, nullptr, 0, 0, 0, out); },
            "enabled lazy scanner accepted invalid source");
    require(out.histogram == expected.histogram && out.log2_mean == expected.log2_mean,
            "failed lazy scan destroyed previous statistics");
    std::atomic<uint64_t> generation{0};
    OutputCancel token(generation);
    ++generation;
    bool cancelled = false;
    try { prepare_raw_development_stats({true,true,0}, true, pixels, 2, 2, 1, out, token); }
    catch (const std::runtime_error& e) { cancelled = std::string(e.what()) == "render cancelled"; }
    require(cancelled && out.histogram == expected.histogram && out.log2_mean == expected.log2_mean,
            "cancelled lazy scan published partial statistics");
    const float black[] = {0,0,0};
    require(prepare_raw_development_stats({true,true,0}, true, black, 1, 1, 1, out) &&
            !out.available && out.dark_pixels == 1, "prepared all-black source confused with unscanned source");
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
    m = identity(); m.profile_dynamic_range_present = true; m.profile_dynamic_range = 1;
    rejects([&] { plan(m); }, "HDR camera profile interpreted as SDR");
    require(!RawDevelopment::build(m, {}, {}).enabled(), "neutral HDR profile stopped bypassing development");
    m.profile_dynamic_range = 0;
    const auto explicit_sdr = plan(m), implicit_sdr = plan(identity());
    float explicit_pixel[] = {.2f, .4f, .6f}, implicit_pixel[] = {.2f, .4f, .6f};
    explicit_sdr.apply_encoded_prophoto(explicit_pixel);
    implicit_sdr.apply_encoded_prophoto(implicit_pixel);
    require(std::memcmp(explicit_pixel, implicit_pixel, sizeof explicit_pixel) == 0,
            "explicit SDR changed existing development bytes");
    m.profile_dynamic_range_invalid = true;
    rejects([&] { plan(m); }, "malformed dynamic-range tag treated as absent");
    require(!RawDevelopment::build(m, {}, {}).enabled(), "neutral malformed profile changed default");
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

void parallel_histogram_parity() {
    // A serial oracle, including padded/odd rows, both sides of the size gate
    // and a large single row which cannot be partitioned by height.
    for (const auto wh : {std::array<uint32_t,2>{1001,701}, {127,4100}, {600001,1}, {17,29411}, {17,29412}}) {
        const auto [w,h] = wh;
        const size_t stride = w + 3;
        std::vector<float> source(stride*h*3, std::numeric_limits<float>::quiet_NaN());
        RawDevelopmentStats expected;
        for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
            const size_t i = size_t(y)*w+x;
            float* c = source.data()+(size_t(y)*stride+x)*3;
            c[0] = float(std::exp2(-30.0+double(i%1701)/28));
            c[1] = c[0]*.7f; c[2] = c[0]*1.8f;
            if (i%991 == 0) c[1] = std::numeric_limits<float>::infinity();
            if (i%457 == 0) c[0] = c[1] = c[2] = -.1f;
            if (!std::isfinite(c[0]) || !std::isfinite(c[1]) || !std::isfinite(c[2])) {
                ++expected.invalid_pixels; continue;
            }
            const double l = .2880402*c[0]+.7118741*c[1]+.0000857*c[2];
            if (l <= std::exp2(-24.0)) { ++expected.dark_pixels; continue; }
            ++expected.histogram[size_t(std::clamp((std::log2(l)+24)*4096/48, 0.0, 4095.0))];
            ++expected.valid_pixels;
        }
        const double lo = double(expected.valid_pixels)*.01, hi = double(expected.valid_pixels)*.99;
        uint64_t count = 0;
        double sum = 0, weights = 0;
        for (size_t i = 0; i < expected.histogram.size(); ++i) {
            const uint64_t before = count; count += expected.histogram[i];
            const double weight = std::max(0.0,std::min(double(count),hi)-std::max(double(before),lo));
            sum += weight*(-24+(double(i)+.5)*48/4096); weights += weight;
        }
        expected.log2_mean = sum/weights;
        for (int repeat = 0; repeat < 3; ++repeat) {
            const auto actual = raw_development_stats(source.data(),stride,w,h);
            require(actual.histogram == expected.histogram && actual.valid_pixels == expected.valid_pixels &&
                    actual.dark_pixels == expected.dark_pixels && actual.invalid_pixels == expected.invalid_pixels &&
                    actual.available && actual.log2_mean == expected.log2_mean, "parallel histogram differs from serial oracle");
        }
    }
}

void sigmoid_reference_parity() {
    const double log_a = std::log(std::pow(.1845,1.5)*(1/.1845-1));
    auto old_tone = [&](double x) {
        if (x <= 0) return 0.0;
        const double u = 1.5*std::log(x)-log_a, z = std::exp(-std::abs(u));
        return u >= 0 ? 1/(1+z) : z/(1+z);
    };
    uint32_t seed = 849103;
    for (float ev : {-8.f,-1.25f,0.f,2.5f,8.f}) {
        RawDevelopmentMetadata meta;
        meta.baseline_exposure_present = true; meta.baseline_exposure = .25;
        const auto p = plan(meta,ev);
        const double gain = std::exp2(double(ev)+.25);
        for (int sample = 0; sample < 65536; ++sample) {
            float c[3];
            double decoded[3];
            for (int k = 0; k < 3; ++k) {
                seed = seed*1664525+1013904223;
                const uint32_t bits = seed & 0x7f7fffff;
                std::memcpy(c+k,&bits,sizeof(float));
                if (sample%7 == 0) c[k] = -c[k];
                decoded[k] = c[k] <= 0 ? 0 : linear(c[k])*gain;
            }
            const double lo = std::min({decoded[0],decoded[1],decoded[2]});
            const double hi = std::max({decoded[0],decoded[1],decoded[2]});
            const double low = old_tone(lo), high = old_tone(hi);
            float reference[3];
            for (int k = 0; k < 3; ++k) {
                const double ratio = hi > lo ? (decoded[k]-lo)/(hi-lo) : 0;
                reference[k] = encoded(std::clamp(low+ratio*(high-low),0.0,1.0));
            }
            p.apply_encoded_prophoto(c);
            for (int k = 0; k < 3; ++k) {
                require(std::isfinite(c[k]) && c[k] >= 0 && c[k] <= 1, "sigmoid numeric range");
                near(c[k],reference[k],6e-8,"sigmoid differs from original log/exp reference");
            }
        }
    }
}

void large_scan_cancellation_preserves_previous_stats() {
    constexpr uint32_t w = 4096, h = 4096;
    std::vector<float> source(size_t(w)*h*3,.18f);
    RawDevelopmentStats previous; previous.valid_pixels = 193; previous.log2_mean = -2.7;
    std::atomic<uint64_t> generation{0};
    OutputCancel token(generation);
    std::thread canceller([&] {
        std::this_thread::sleep_for(std::chrono::milliseconds(1)); ++generation;
    });
    bool cancelled = false;
    try { prepare_raw_development_stats({true,true,0},true,source.data(),w,w,h,previous,token); }
    catch (const std::runtime_error& e) { cancelled = std::string(e.what()) == "render cancelled"; }
    canceller.join();
    require(cancelled && previous.valid_pixels == 193 && previous.log2_mean == -2.7,
            "cancelled parallel scan published partial stats or failed to join");
}
}  // namespace

int main() {
    try {
        neutral_bypass(); technical_exposure_and_hdr(); fallback_curve(); embedded_shape();
        whole_frame_statistics(); frozen_plan_strip_and_thread_parity(); lazy_statistics(); metadata_rejection_and_status(); cancellation();
        parallel_histogram_parity(); sigmoid_reference_parity(); large_scan_cancellation_preserves_previous_stats();
        std::cout << "raw_development: 12 groups passed (synthetic; no Android acceptance)\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "raw_development: " << e.what() << '\n';
        return 1;
    }
}
