// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Include the real C ABI implementation to exercise its private materialise
// boundary. The archive does not pull a second engine.cpp translation unit.
#ifndef SPK_ENGINE_IMPLEMENTATION
#define SPK_ENGINE_IMPLEMENTATION "pipeline/engine.cpp"
#endif
#include SPK_ENGINE_IMPLEMENTATION
#include "vulkan_test_hooks.hpp"
#include "rgba16_frame_check.hpp"
#include <iostream>
#include <stdexcept>

namespace {
void require(bool ok, const std::string& why) {
    if (!ok) throw std::runtime_error(why);
}
uint64_t hash(const void* data, size_t bytes) {
    auto* p = static_cast<const uint8_t*>(data);
    uint64_t h = 14695981039346656037ull;
    for (size_t i = 0; i < bytes; ++i) h = (h ^ p[i]) * 1099511628211ull;
    return h;
}
}

int main(int argc, char** argv) try {
    require(argc == 3, "usage: finite_output SHADERS baseline|candidate");
    const bool candidate = std::string(argv[2]) == "candidate";
    std::string error;
    spk_engine engine;
    engine.gpu = spk::gpu::Gpu::create_vulkan(argv[1], error);
    auto* gpu = engine.gpu;
    require(gpu != nullptr, error);
    spk_session session;
    session.engine = &engine;
    spk::Image image;
    image.w = 773; image.h = 1031; image.c = 3;
    std::vector<float> clean(image.elements());
    for (size_t i = 0; i < clean.size(); ++i) clean[i] = float(int(i % 257) - 32) / 128.f;
    clean[0] = -16.0f;
    clean[1] = 16.0f;
    clean[2] = -0.0f;
    unsigned rejected = 0, accepted = 0;
    std::cout << "case,cap,status,width,height,hash\n";
    for (unsigned cap : {0u, 1u, 7u}) {
        require(spk::gpu::test_limit_dispatch_groups_x(gpu, cap, error), error);
        for (unsigned fault = 0; fault < 7; ++fault) {
            auto pixels = clean;
            size_t first = 3 * (size_t(image.w) + 1) + 2;
            if (fault == 1) pixels[first] = std::numeric_limits<float>::quiet_NaN();
            if (fault == 2) pixels[first] = std::numeric_limits<float>::infinity();
            if (fault == 3) pixels[first] = -std::numeric_limits<float>::infinity();
            if (fault == 4) {
                first = pixels.size() - 1;
                pixels[first] = std::numeric_limits<float>::quiet_NaN();
            }
            if (fault == 5) {
                first = size_t(image.w) * 3 * 15;
                std::fill(pixels.begin() + first, pixels.end(), std::numeric_limits<float>::quiet_NaN());
            }
            if (fault == 6) {
                first = (size_t(300) * image.w + 200) * 3 + 1;
                for (unsigned y = 300; y < 700; ++y) for (unsigned x = 200; x < 500; ++x)
                    pixels[(size_t(y) * image.w + x) * 3 + 1] = std::numeric_limits<float>::quiet_NaN();
            }
            image.buf = gpu->upload(pixels.data(), image.bytes(), error);
            require(bool(image.buf), error);
            error.clear();
            spk_result result{};
            const bool ok = materialise(&session, image, &result, error);
            if (candidate && fault) {
                require(!ok, "corrupt RGB materialised successfully");
                const size_t pixel = first / 3;
                const std::string expected = "render integrity: non-finite RGB at (" +
                    std::to_string(pixel % image.w) + ", " + std::to_string(pixel / image.w) +
                    "), channel " + std::to_string(first % 3);
                require(error == expected, "wrong corruption location: " + error);
                require(!result.texture && !result.rgba16 && !result.width && !result.height,
                    "failed materialisation published an output");
                ++rejected;
                std::cout << fault << ',' << cap << ",rejected,0,0,0\n";
            } else {
                require(ok && result.rgba16, error);
                require(spk::android::check_rgba16_frame(result.rgba16, result.width,
                    result.height, result.row_stride_px).empty(),
                    "baseline fault was already detected by the existing JNI check");
                for (uint32_t y = 0; y < result.height; ++y)
                    for (uint32_t x = 0; x < result.width; ++x)
                        require(result.rgba16[(size_t(y) * result.row_stride_px + x) * 4 + 3] == 65535,
                            "baseline did not conceal the fault behind opaque alpha");
                ++accepted;
                std::cout << fault << ',' << cap << ",accepted," << result.width << ',' << result.height
                    << ',' << hash(result.rgba16, size_t(result.row_stride_px) * result.height * 8) << '\n';
            }
            spk_result_free(&result);
            spk::view::Request request;
            request.adjustments.set("enabled", spk::Json(false));
            request.target_space = "ProPhoto RGB";
            request.rgba16 = true;
            spk::Json transform = spk::Json::object();
            transform.set("source_color_space", spk::Json("ProPhoto RGB"));
            transform.set("target_color_space", spk::Json("ProPhoto RGB"));
            spk::view::Result viewed;
            error.clear();
            const bool view_ok = spk::view::process(gpu, image, request, transform,
                                                   nullptr, 0, viewed, error);
            if (candidate && fault) {
                require(!view_ok && error.starts_with("render integrity: non-finite RGB"),
                    "view quantisation concealed corrupt RGB: " + error);
                require(viewed.pixels.empty(), "failed view published pixels");
            } else {
                require(view_ok && !viewed.pixels.empty(), "view: " + error);
                if (!fault) std::cout << "0," << cap << ",view," << viewed.width << ',' << viewed.height
                    << ',' << hash(viewed.pixels.data(), viewed.pixels.size()) << '\n';
            }
            if (!fault) {
                request.export_intent = true;
                request.proof_long_edge = 385;
                const auto proof = transform;
                transform.set("proof", proof);
                spk::view::Result proofed;
                require(spk::view::process(gpu, image, request, transform, nullptr, 0, proofed, error), error);
                require(proofed.width == 289 && proofed.height == 385, "wrong proof shape");
                std::cout << "0," << cap << ",proof," << proofed.width << ',' << proofed.height
                    << ',' << hash(proofed.pixels.data(), proofed.pixels.size()) << '\n';
            }
        }
    }
#if defined(SPK_EXPECT_INTEGRITY)
    if (!std::getenv("SPK_FINITE_SKIP_LARGE")) {
        require(spk::gpu::test_limit_dispatch_groups_x(gpu, 0, error), error);
        image.w = 6709; image.h = 4475;
        std::vector<float> large(image.elements(), 0.25f);
        image.buf = gpu->upload(large.data(), image.bytes(), error);
        require(bool(image.buf), error);
        const auto start = std::chrono::steady_clock::now();
        require(spk::finite_output(gpu, image, error), error);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-start).count();
        large.back() = std::numeric_limits<float>::quiet_NaN();
        image.buf = gpu->upload(large.data(), image.bytes(), error);
        require(bool(image.buf), error);
        require(!spk::finite_output(gpu, image, error) &&
            error == "render integrity: non-finite RGB at (6708, 4474), channel 2",
            "30 MP final sample was skipped: " + error);
        std::cerr << "30MP_scan_ms=" << ms << " summary_bytes=8\n";
    }
#endif
    require(candidate ? rejected == 18 && accepted == 3 : accepted == 21 && rejected == 0,
        "fault matrix incomplete");
    std::cerr << "PASS " << argv[2] << " materialise_accepted=" << accepted
        << " materialise_rejected=" << rejected << " view_cases=21 proof_cases=3\n";
} catch (const std::exception& e) {
    std::cerr << e.what() << '\n';
    return 1;
}
