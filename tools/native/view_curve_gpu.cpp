// Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
#include "pipeline/view_process.hpp"
#include "vulkan_test_hooks.hpp"
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>

namespace {
void require(bool ok, const std::string& why) {
    if (!ok) throw std::runtime_error(why);
}
spk::Json curve(unsigned kind, unsigned channel) {
    auto points = spk::Json::array();
    const auto add = [&](double x, double y) {
        auto p = spk::Json::array();
        p.push(spk::Json(x)); p.push(spk::Json(y)); points.push(std::move(p));
    };
    if (kind == 0) { add(0, 0); add(1, 1); }
    if (kind == 1) { add(0, 1); add(1, 0); }
    if (kind == 2) { add(0, .2); add(.25, .2); add(.75, .8); add(1, .8); }
    if (kind == 3) { add(.1, .2); add(.2, .9); add(.3, .1); add(.7, .8); add(.9, .4); }
    if (kind == 4) { add(0, 0); add(1e-12, 1); add(2e-12, 0); add(.5, 1); add(1, 0); }
    if (kind == 5) for (unsigned i = 0; i < 256; ++i)
        add(double(i) / 255, double((i * 73 + channel * 19) % 257) / 256);
    auto out = spk::Json::object(); out.set("points", std::move(points)); return out;
}
uint64_t hash(const std::vector<uint8_t>& bytes) {
    uint64_t h = 14695981039346656037ull;
    for (auto b : bytes) h = (h ^ b) * 1099511628211ull;
    return h;
}
}

int main(int argc, char** argv) try {
    require(argc == 3, "usage: view_curve_gpu SHADERS OUTPUT_BYTES");
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
    require(bool(gpu), error);
    std::ofstream payload(argv[2], std::ios::binary);
    require(bool(payload), "cannot open output bytes");
    auto transform = spk::Json::object();
    transform.set("source_color_space", spk::Json(std::string("ProPhoto RGB")));
    transform.set("target_color_space", spk::Json(std::string("ProPhoto RGB")));
    const auto proof = transform; transform.set("proof", proof);
    std::cout << "cap,input,curve,mode,width,height,bytes,hash\n";
    unsigned cases = 0;
    for (unsigned cap : {0u, 1u, 7u}) {
        require(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), cap, error), error);
        for (unsigned input : {2u, 3u}) {
            spk::Image image; image.w = 321; image.h = 239; image.c = input;
            if (input == 3) {
                std::vector<float> pixels(image.elements());
                for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = float(int(i % 257) - 32) / 128.f;
                pixels[0] = -16; pixels[1] = 16; pixels[2] = -0.0f;
                image.buf = gpu->upload(pixels.data(), image.bytes(), error);
            } else {
                std::vector<uint16_t> pixels(size_t(image.w) * image.h * 4);
                for (size_t i = 0; i < pixels.size(); ++i)
                    pixels[i] = i % 4 == 3 ? 65535 : uint16_t((i * 73) % 65536);
                image.buf = gpu->upload(pixels.data(), image.bytes(), error);
            }
            require(bool(image.buf), error);
            for (unsigned kind = 0; kind < 6; ++kind) for (unsigned mode = 0; mode < 3; ++mode) {
                spk::view::Request request;
                request.adjustments = spk::Json::object();
                request.adjustments.set("enabled", spk::Json(true));
                auto curves = spk::Json::object();
                const char* names[] = {"rgb", "luma", "red", "green", "blue"};
                for (unsigned c = 0; c < 5; ++c) curves.set(names[c], curve(kind, c));
                request.adjustments.set("curves", std::move(curves));
                request.geometry = spk::Json::object();
                request.output_channels = mode == 1 ? 3 : 4;
                request.rgba16 = mode == 1;
                request.target_space = "ProPhoto RGB";
                if (mode == 1) {
                    request.geometry.set("quarterTurns", spk::Json(1.0));
                    request.long_edge = 257;
                }
                if (mode == 2) { request.export_intent = true; request.proof_long_edge = 127; }
                spk::view::Result result;
                require(spk::view::process(gpu.get(), image, request, transform, nullptr, 0, result, error), error);
                require(!result.pixels.empty() && result.width && result.height, "empty GPU view");
                payload.write(reinterpret_cast<const char*>(result.pixels.data()), result.pixels.size());
                require(bool(payload), "cannot write output bytes");
                std::cout << cap << ',' << input << ',' << kind << ',' << mode << ',' << result.width << ','
                    << result.height << ',' << result.pixels.size() << ',' << hash(result.pixels) << '\n';
                ++cases;
            }
        }
    }
    require(cases == 108, "incomplete GPU curve matrix");
    std::cerr << "PASS " << cases << " real Vulkan view curve cases\n";
} catch (const std::exception& e) {
    std::cerr << "FAIL " << e.what() << '\n'; return 1;
}
