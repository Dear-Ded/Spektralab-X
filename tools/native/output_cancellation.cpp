// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "passthrough.hpp"
#include "output_transform.hpp"
#include "tiff16.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <thread>

using namespace spk::android;
void check(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
template<class F> void cancelled(F&& body) {
    try { body(); }
    catch (const std::runtime_error& e) {
        check(std::string(e.what()) == "render cancelled", e.what());
        return;
    }
    throw std::runtime_error("cancelled operation succeeded");
}
std::vector<uint8_t> bytes(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    check(bool(in), "missing test output");
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}
void view_stages(spk_engine* engine, bool floating, bool di = false) {
    constexpr uint32_t w = 97, h = 73;
    std::vector<float> input(size_t(w) * h * 3);
    for (size_t i = 0; i < input.size(); ++i) input[i] = float(i % 577) / 127.f - .4f;
    const spk_image image{input.data(), w, h, 3};
    const char* params = di
        ? R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"auto_exposure":false,"digital_intermediate":true,"grain_active":false,"glare_active":false,"halation_active":false,"dir_couplers_active":false,"scanner_unsharp_amount":0,"product_defaults":true})"
        : R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"auto_exposure":false})";
    std::unique_ptr<spk_session, decltype(&spk_session_release)> session(
        spk_open(engine, &image, params, nullptr), spk_session_release);
    check(bool(session), spk_last_error(engine));
    spk::view::Request request;
    std::string error;
    check(view_request(session.get(), "full", !di, request, error), error);
    request.ungraded_float = floating; request.rgba16 = !floating;
    request.output_channels = floating ? 3 : 4;
    request.long_edge = 31;
    check(spk::Json::parse(R"({"crop":{"x":0.1,"y":0.1,"width":0.7,"height":0.8},"quarterTurns":1,"flipH":true})", request.geometry, error), error);
    spk::view::Result baseline;
    check(spk::view::render(session.get(), request, baseline, error), error);
    size_t polls = 0;
    request.cancelled = [&]() noexcept { ++polls; return false; };
    spk::view::Result unchanged;
    check(spk::view::render(session.get(), request, unchanged, error), error);
    check(unchanged.float_pixels == baseline.float_pixels && unchanged.pixels == baseline.pixels && unchanged.width == baseline.width &&
          unchanged.height == baseline.height, "active noncancelled token changed view pixels");
    check(polls == (floating ? 8u : di ? 9u : 10u), "unexpected cancellation stage count: " + std::to_string(polls));
    for (size_t stop = 1; stop <= polls; ++stop) {
        size_t current = 0;
        request.cancelled = [&]() noexcept { return ++current == stop; };
        spk::view::Result result;
        check(!spk::view::render(session.get(), request, result, error) && error == "render cancelled", "stage cancellation failed");
        check(current == stop, "work continued past cancelled stage");
        const bool empty = floating ? result.float_pixels.empty() : result.pixels.empty();
        if (stop < polls) check(empty, "cancelled stage allocated CPU readback");
        else check(!empty, "last-stage test missed readback completion");
        request.cancelled = {};
        spk::view::Result recovered;
        check(spk::view::render(session.get(), request, recovered, error), error);
        check(recovered.float_pixels == baseline.float_pixels && recovered.pixels == baseline.pixels && recovered.width == baseline.width &&
              recovered.height == baseline.height, "cancelled view changed resident source/recovery");
    }
    if (!floating) {
        std::atomic<uint64_t> generation{0}; const OutputCancel token(generation);
        spk_result plain{}, guarded{};
        check(render_view(session.get(), "full", !di, plain, error), error);
        check(render_view(session.get(), "full", !di, guarded, error, token), error);
        check(plain.width == guarded.width && plain.height == guarded.height &&
              std::memcmp(plain.rgba16, guarded.rgba16, size_t(plain.width)*plain.height*8) == 0,
              "integer view adapter token changed pixels");
        spk_result_free(&plain); spk_result_free(&guarded);
        generation.fetch_add(1, std::memory_order_relaxed);
        cancelled([&] { render_view(session.get(), "full", !di, guarded, error, token); });
        check(!guarded.texture && !guarded.rgba16, "pre-cancelled adapter allocated a result");
    }
    std::cout << "view PASS float=" << floating << " di=" << di << " stages=" << polls << " recovered=" << polls
              << " cpu_readback_avoided=" << polls-1 << '\n';
}
void legacy_look_cancellation() {
    constexpr uint32_t w = 1031, h = 1025, stride = w + 3;
    std::vector<uint16_t> src(size_t(stride) * h * 4);
    for (size_t i = 0; i < src.size(); ++i) src[i] = uint16_t(i * 63001u + i / 7);
    std::vector<float> settings(kLookSize, 0);
    settings[kExposure] = -.7f; settings[kBrightness] = 13; settings[kVignette] = -37;
    const Layer2 look(settings);
    const auto frame = look.frame(w, h);
    std::vector<uint8_t> reference(size_t(stride) * h * 4, 7), guarded(reference.size(), 7);
    const float iw = 1.f / w, ih = 1.f / h;
    for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
        const auto at = (size_t(y) * stride + x) * 4;
        float r = src[at] / 65535.f, g = src[at+1] / 65535.f, b = src[at+2] / 65535.f;
        look.apply(r, g, b, (x+.5f)*iw, (y+.5f)*ih, frame);
        const float rgb[] = {r, g, b};
        for (unsigned c = 0; c < 3; ++c)
            reference[at+c] = uint8_t(std::lround(std::min(1.f, std::max(0.f, rgb[c]))*255.f));
        reference[at+3] = 255;
    }
    std::atomic<uint64_t> generation{0}; const OutputCancel token(generation);
    pack_rgba8_look(look, src.data(), stride, w, h, guarded.data(), stride*4, token);
    check(guarded == reference, "legacy look bytes or row padding changed");
    generation.fetch_add(1, std::memory_order_relaxed);
    cancelled([&] { pack_rgba8_look(look, src.data(), stride, w, h, guarded.data(), stride*4, token); });
    check(guarded == reference, "pre-cancelled legacy look changed destination");
    struct StopLut final : ColourTable {
        std::atomic<uint64_t>& generation;
        mutable std::atomic<size_t> calls{0};
        explicit StopLut(std::atomic<uint64_t>& g) : generation(g) {}
        void apply(float&, float&, float&) const override {
            if (calls.fetch_add(1, std::memory_order_relaxed) == 3*w)
                generation.fetch_add(1, std::memory_order_relaxed);
        }
    };
    Layer2 interrupted(settings);
    auto lut = std::make_shared<StopLut>(generation);
    interrupted.set_lut(lut, 1);
    const OutputCancel fresh(generation);
    std::fill(guarded.begin(), guarded.end(), 7);
    cancelled([&] { pack_rgba8_look(interrupted, src.data(), stride, w, h, guarded.data(), stride*4, fresh); });
    size_t written = 0;
    for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x)
        written += guarded[(size_t(y)*stride+x)*4+3] == 255;
    check(written && written < size_t(w)*h, "legacy test missed active cancellation");
    const auto after = guarded;
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    check(guarded == after, "legacy workers wrote after cancelled return");
    std::cout << "legacy look PASS parity/pre-cancel/active-cancel joined written=" << written << '\n';
}
void cpu_conversion(spk_engine* engine) {
    OutputTransform xf; std::string error;
    check(xf.fetch(engine, "sRGB", true, error), error);
    constexpr uint32_t w = 4097, h = 1025;
    std::vector<float> floating(size_t(w) * h * 3);
    std::vector<uint16_t> integer(size_t(w) * h * 4);
    for (size_t i = 0; i < floating.size(); ++i) floating[i] = float(i % 577) / 127.f - .4f;
    for (size_t i = 0; i < integer.size(); ++i) integer[i] = uint16_t(i * 63001u + i / 7);
    std::vector<float> settings(kLookSize, 0);
    settings[kExposure] = -1; settings[kVignette] = -37; settings[kVignetteMid] = 43;
    const Layer2 look(settings);
    for (bool fp : {false, true}) {
        std::vector<uint8_t> out(size_t(w) * h * 4, 7);
        std::atomic<uint64_t> generation{0};
        const OutputCancel token(generation);
        std::thread stop([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            generation.fetch_add(1, std::memory_order_relaxed);
        });
        bool caught = false;
        try {
            if (fp) pack_float_rgba8(floating.data(), w, w, h, out.data(), w * 4, &look, xf, token);
            else pack_rgba8(integer.data(), w, w, h, out.data(), w * 4, &look, xf, token);
        } catch (const std::runtime_error& e) {
            caught = std::string(e.what()) == "render cancelled";
        }
        stop.join();
        check(caught, "parallel conversion missed cancellation");
        size_t written = 0;
        for (size_t i = 3; i < out.size(); i += 4) written += out[i] == 255;
        check(written && written < size_t(w) * h, "test did not cancel an active conversion");
        const auto completed = out;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        check(out == completed, "conversion wrote after cancellation returned");
        std::cout << "cpu PASS float=" << fp << " written_pixels=" << written << " total=" << size_t(w) * h << '\n';
    }
    // Small fixtures exercise both full-frame and serial-strip APIs unchanged.
    constexpr uint32_t sw = 73, sh = 97;
    for (bool fp : {false, true}) {
        std::atomic<uint64_t> generation{0}; const OutputCancel token(generation);
        std::vector<uint8_t> plain(size_t(sw) * sh * 4), guarded(plain.size()), strip8;
        std::vector<uint16_t> plain16, guarded16, strip16;
        if (fp) {
            pack_float_rgba8(floating.data(), sw, sw, sh, plain.data(), sw * 4, &look, xf);
            pack_float_rgba8(floating.data(), sw, sw, sh, guarded.data(), sw * 4, &look, xf, token);
            pack_float_rgba16(floating.data(), sw, sw, sh, plain16, &look, xf);
            pack_float_rgba16(floating.data(), sw, sw, sh, guarded16, &look, xf, token);
        } else {
            pack_rgba8(integer.data(), sw, sw, sh, plain.data(), sw * 4, &look, xf);
            pack_rgba8(integer.data(), sw, sw, sh, guarded.data(), sw * 4, &look, xf, token);
            pack_rgba16(integer.data(), sw, sw, sh, plain16, &look, xf);
            pack_rgba16(integer.data(), sw, sw, sh, guarded16, &look, xf, token);
        }
        check(plain == guarded && plain16 == guarded16, "token changed CPU output bytes");
        auto rows8 = fp ? Rgba8Rows(floating.data(), sw, sw, sh, &look, &xf) : Rgba8Rows(integer.data(), sw, sw, sh, &look, &xf);
        auto rows16 = fp ? Rgba16Rows(floating.data(), sw, sw, sh, &look, &xf) : Rgba16Rows(integer.data(), sw, sw, sh, &look, &xf);
        rows8.pack(0, sh, strip8, token); rows16.pack(0, sh, strip16, token);
        check(strip8 == plain && strip16 == plain16, "token changed strip bytes/coordinates");
        generation.fetch_add(1, std::memory_order_relaxed);
        cancelled([&] { rows8.pack(0, sh, strip8, token); });
        cancelled([&] { rows16.pack(0, sh, strip16, token); });
        check(strip8 == plain && strip16 == plain16, "pre-cancelled strip changed output storage");
        cancelled([&] { pack_float_rgba16(floating.data(), sw, sw, sh, guarded16, &look, xf, token); });
        check(guarded16 == plain16, "pre-cancelled conversion changed output storage");
    }
    std::cout << "cpu parity PASS float/integer full/strip rgba8/rgba16\n";
}
void tiff_cleanup(spk_engine* engine, const std::string& dir) {
    constexpr uint32_t w = 73, h = 257;
    std::vector<float> src(size_t(w) * h * 3);
    for (size_t i = 0; i < src.size(); ++i) src[i] = float(i % 577) / 127.f - .4f;
    OutputTransform xf; std::string error;
    check(xf.fetch(engine, "sRGB", true, error), error);
    const Rgba16Rows rows(src.data(), w, w, h, nullptr, &xf);
    const std::string path = dir + "/output-cancel.part", plain = dir + "/output-plain.tif";
    const std::vector<uint8_t> icc(128, 42);
    std::atomic<uint64_t> generation{0}; const OutputCancel token(generation);
    std::atomic<unsigned> calls{0}, active{0};
    const TiffRowSource source = [&](uint32_t y, uint32_t n, std::vector<uint16_t>& scratch) {
        active.fetch_add(1);
        struct Exit { std::atomic<unsigned>& n; ~Exit() { n.fetch_sub(1); } } exit{active};
        if (calls.fetch_add(1) == 1) generation.fetch_add(1, std::memory_order_relaxed);
        rows.pack(y, n, scratch, token);
        return TiffRowView{scratch.data(), w};
    };
    check(!write_tiff16(path, w, h, icc, source, error), "cancelled TIFF succeeded");
    check(active.load() == 0 && calls.load() >= 2, "TIFF returned with active source workers");
    check(!std::filesystem::exists(path), "cancelled TIFF left partial output");
    // JNI performs this after the writer returns and owned FILE has closed.
    cancelled([&] { token.check(); });
    const auto uncancelled_source = [&](uint32_t y, uint32_t n, std::vector<uint16_t>& scratch) {
        rows.pack(y, n, scratch);
        return TiffRowView{scratch.data(), w};
    };
    check(write_tiff16(plain, w, h, icc, uncancelled_source, error), error);
    const OutputCancel fresh(generation);
    check(write_tiff16(path, w, h, icc, [&](uint32_t y, uint32_t n, std::vector<uint16_t>& scratch) {
        rows.pack(y, n, scratch, fresh); return TiffRowView{scratch.data(), w};
    }, error), error);
    check(bytes(plain) == bytes(path), "token changed complete TIFF bytes");
    std::filesystem::remove(plain); std::filesystem::remove(path);
    std::cout << "TIFF PASS cancelled_cleanup joined_workers cancel_classification complete_file_parity\n";
}
int main(int argc, char** argv) {
    try {
        check(argc == 3, "resources/output directory required");
        std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        check(bool(engine), spk_last_error(nullptr));
        view_stages(engine.get(), true); view_stages(engine.get(), false); view_stages(engine.get(), false, true);
        legacy_look_cancellation(); cpu_conversion(engine.get()); tiff_cleanup(engine.get(), argv[2]);
        std::cout << "output cancellation PASS\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
