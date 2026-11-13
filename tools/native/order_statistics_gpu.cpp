// Co-created with GPT (OpenAI). Meter changes through real film/print rendering.
#include "pipeline/pipeline.hpp"
#include <bit>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>

namespace {
void require(bool value, const std::string& error) {
    if (!value) throw std::runtime_error(error);
}
void save(spk::gpu::Gpu* gpu, const spk::Image& image, std::ofstream& stream, std::string& error) {
    std::vector<float> pixels(image.elements());
    require(gpu->read(image.buf.get(), 0, pixels.data(), image.bytes(), error), error);
    for (float value : pixels) require(std::isfinite(value), "nonfinite render");
    stream.write(reinterpret_cast<const char*>(pixels.data()), image.bytes());
    require(bool(stream), "output payload write");
}
}
int main(int argc, char** argv) try {
    require(argc == 4, "usage: order_statistics_gpu SHADERS RESOURCES PAYLOAD");
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
    require(bool(gpu), error);
    spk::Blob blob; spk::Colour colour; spk::SetupCache cache;
    require(blob.open(std::string(argv[2]) + "/spektrafilm_constants.bin", error) && colour.init(blob, error), error);
    std::ifstream file(std::string(argv[2]) + "/neutral_print_filters.json");
    const std::string text{std::istreambuf_iterator<char>(file), {}};
    spk::Json filters; require(spk::Json::parse(text, filters, error), error);
    std::ofstream payload(argv[3], std::ios::binary);
    require(bool(payload), "output payload open");
    std::cout << "scene,method,width,height,meter_stride,meter_full,applied_ev,negative_values,output_values\n";
    unsigned cases = 0;
    for (unsigned scene = 0; scene < 3; ++scene) {
        spk::Image input; input.w = scene == 1 ? 321 : 97; input.h = scene == 1 ? 239 : 65; input.c = 3;
        std::vector<float> pixels(input.elements());
        for (size_t i = 0; i < pixels.size(); ++i) {
            if (scene == 0) pixels[i] = 0.0f;
            else if (scene == 1) pixels[i] = float(int((i * 73) % 1025) - 32) / 128.0f;
            else pixels[i] = i % 1009 < 9 ? 16.0f : (i % 11 ? 0.184f : 0.0f);
        }
        input.buf = gpu->upload(pixels.data(), input.bytes(), error); require(bool(input.buf), error);
        for (const char* method : {"average", "median", "center_weighted", "balanced", "center",
                                   "protect_highlights", "protect_shadows", "off"}) {
            spk::Params params;
            require(spk::init_params(argv[2], "kodak_portra_400", "kodak_portra_endura", filters, params, error), error);
            params.camera.auto_exposure = std::string(method) != "off";
            params.camera.auto_exposure_method = std::string(method) == "off" ? "balanced" : method;
            params.io.input_cctf_decoding = false;
            params.film_render.grain.active = false; params.print_render.glare.active = false;
            params.film_render.halation.active = false; params.film_render.dir_couplers.active = false;
            params.scanner.lens_blur = 0; params.scanner.unsharp_mask[1] = 0;
            spk::Pipeline pipeline(gpu.get(), &colour, &blob, &cache);
            require(pipeline.build(params, error), error);
            pipeline.set_source_shape(input.w, input.h, input.w, input.h);
            double stride_ev = 0.0, full_ev = 0.0;
            require(pipeline.measure_exposure_ev(input, stride_ev, error, true), error);
            require(pipeline.measure_exposure_ev(input, full_ev, error, false), error);
            spk::Progress progress; spk::Image negative, output;
            require(pipeline.run_film(input, negative, &progress, error), error);
            const auto applied = pipeline.last_auto_exposure_ev();
            require(applied.has_value() == params.camera.auto_exposure, "auto exposure report");
            save(gpu.get(), negative, payload, error);
            require(pipeline.run_print(negative, output, &progress, error), error);
            save(gpu.get(), output, payload, error);
            require(progress.done, "render completion");
            std::cout << scene << ',' << method << ',' << input.w << ',' << input.h << ',' << std::hex
                << std::bit_cast<uint64_t>(stride_ev) << ',' << std::bit_cast<uint64_t>(full_ev) << ','
                << std::bit_cast<uint64_t>(applied.value_or(0.0)) << std::dec << ','
                << negative.elements() << ',' << output.elements() << '\n';
            ++cases;
        }
    }
    require(cases == 24, "incomplete render matrix");
    std::cerr << "PASS actual Vulkan: cases=" << cases << "; seven meters; AE-off control; stride/full; negative/final bytes\n";
    return 0;
} catch (const std::exception& error) {
    std::cerr << "FAIL " << error.what() << '\n'; return 1;
}
