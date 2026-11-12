// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Synthetic regression for full film stages under forced Vulkan dispatch chunks.
#include "pipeline/pipeline.hpp"
#include "vulkan_test_hooks.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <vector>
#ifdef _WIN32
static int unsetenv(const char* name) { return _putenv_s(name, ""); }
#endif

namespace {
using Pixels = std::vector<float>;
using Gpu = spk::gpu::Gpu;
std::string error;
void check(bool ok, const std::string& why) {
    if (!ok) throw std::runtime_error(why.empty() ? error : why);
}
struct Frame {
    std::string stage;
    uint32_t w, h;
    Pixels values;
};
using Trace = std::vector<Frame>;
void snapshot(Trace& trace, Gpu* gpu, const spk::Image& image, const char* stage) {
    check(image.c == 3 && image.w && image.h && bool(image.buf), "invalid stage image");
    Frame frame{stage, image.w, image.h, Pixels(image.elements())};
    check(gpu->read(image.buf.get(), 0, frame.values.data(), image.bytes(), error), error);
    for (float value : frame.values)
        check(std::isfinite(value), std::string("nonfinite stage: ") + stage);
    trace.push_back(std::move(frame));
}
Trace render(Gpu* gpu, const spk::Colour& colour, const spk::Blob& blob,
             spk::SetupCache& cache, const spk::Params& params,
             const spk::Image& source, unsigned full_factor, bool staged) {
    spk::Pipeline pipeline(gpu, &colour, &blob, &cache);
    check(pipeline.build(params, error), error);
    pipeline.set_source_shape(source.w, source.h, source.w * full_factor, source.h * full_factor);
    // Exact grain alone does not pin glare: reset the complete RNG history too.
    pipeline.rng_state_ = 0x243F6A8885A308D3ull;
    spk::Progress progress;
    progress.detailed = true;
    Trace trace;
    if (!staged) {
        spk::Image negative, output;
        check(pipeline.run_film(source, negative, &progress, error), error);
        snapshot(trace, gpu, negative, "negative");
        check(pipeline.run_print(negative, output, &progress, error), error);
        snapshot(trace, gpu, output, "output");
        check(progress.done && !pipeline.progress_, "public pipeline completion contract");
        return trace;
    }
    pipeline.progress_ = &progress;
    spk::Pipeline::Chain chain;
    check(pipeline.film_prefix(source, spk::FrameShape{source.h, source.w}, chain.cur, error), error);
    snapshot(trace, gpu, chain.cur, "prefix");
    for (size_t i = 0; i < pipeline.kFilmStageCount; ++i) {
        spk::Pipeline::Chain next;
        check((pipeline.*pipeline.kFilmStages[i].run)(std::move(chain), next, error), error);
        chain = std::move(next);
        snapshot(trace, gpu, chain.cur, pipeline.kFilmStages[i].name);
        if (chain.log_e_film.buf) snapshot(trace, gpu, chain.log_e_film, "film_log_e");
    }
    check(pipeline.print_prefix(chain.cur, false, error) &&
          pipeline.prepare_contrast_mask(chain.cur, error), error);
    for (size_t i = 0; i < pipeline.kPrintStageCount; ++i) {
        spk::Pipeline::Chain next;
        check((pipeline.*pipeline.kPrintStages[i].run)(std::move(chain), next, error), error);
        chain = std::move(next);
        snapshot(trace, gpu, chain.cur, pipeline.kPrintStages[i].name);
    }
    pipeline.release_contrast_mask();
    pipeline.progress_ = nullptr;
    return trace;
}
size_t compared_values = 0, compared_frames = 0;
void compare(const Frame& expected, const Frame& actual, const std::string& context,
             unsigned cap, std::ofstream& csv) {
    check(expected.w == actual.w && expected.h == actual.h &&
          expected.values.size() == actual.values.size(), context + ": shape mismatch");
    size_t different = 0, first = 0;
    double max_abs = 0;
    for (size_t i = 0; i < expected.values.size(); ++i) {
        if (std::memcmp(&expected.values[i], &actual.values[i], sizeof(float))) {
            if (!different) first = i;
            ++different;
        }
        max_abs = std::max(max_abs, std::abs(double(expected.values[i]) - actual.values[i]));
    }
    csv << context << ',' << expected.stage << ',' << cap << ',' << actual.w << ',' << actual.h
        << ',' << actual.values.size() << ',' << different << ',' << max_abs << '\n';
    csv.flush();
    check(bool(csv), "comparison evidence write failed");
    check(!different, context + ": first changed stage " + expected.stage +
          " at float " + std::to_string(first) + ", cap=" + std::to_string(cap));
    compared_values += actual.values.size();
    ++compared_frames;
}
void compare_public(const Trace& staged, const Trace& ordinary, const std::string& scene,
                    unsigned cap, std::ofstream& csv) {
    const auto negative = std::find_if(staged.begin(), staged.end(),
        [](const Frame& frame) { return frame.stage == "film_grain"; });
    check(negative != staged.end() && ordinary.size() == 2, "missing public comparison frames");
    compare(*negative, ordinary[0], scene + "_public_film", cap, csv);
    compare(staged.back(), ordinary[1], scene + "_public_print", cap, csv);
}
Pixels input_image(unsigned w, unsigned h) {
    Pixels pixels(size_t(w) * h * 3);
    for (unsigned y = 0; y < h; ++y) for (unsigned x = 0; x < w; ++x)
        for (unsigned c = 0; c < 3; ++c) {
            float value = 0.002f + 0.35f * float(x) / float(std::max(1u, w - 1)) +
                          0.2f * float(y) / float(std::max(1u, h - 1));
            if ((x / 13 + y / 7) % 2) value *= 0.08f;
            if (x < 3 || x + 3 >= w) value += c == 0 ? 1.5f : 0.2f;
            if (y < 2 || y + 2 >= h) value += c == 2 ? 1.0f : 0.1f;
            pixels[(size_t(y) * w + x) * 3 + c] = value;
        }
    return pixels;
}
} // namespace

int main(int argc, char** argv) try {
    check(argc == 4, "usage: feedback_dispatch SHADERS RESOURCES OUTPUT_DIR");
    const std::filesystem::path dir = argv[3];
    std::filesystem::create_directories(dir);
    unsetenv("SPK_VULKAN_FUSION");
    unsetenv("SPK_BOUNDED_PRINT_TAIL");
    unsetenv("SPK_IIR_BOUNDARY");
    std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
    check(bool(gpu), error);
    std::string math;
    check(gpu->check_math_mode(math), math);
    std::cerr << "device=" << gpu->device_name() << " math=" << math << '\n';
    spk::Blob blob;
    spk::Colour colour;
    spk::SetupCache cache;
    check(blob.open(std::string(argv[2]) + "/spektrafilm_constants.bin", error) &&
          colour.init(blob, error), error);
    std::ifstream filter_file(std::string(argv[2]) + "/neutral_print_filters.json");
    const std::string filter_text{std::istreambuf_iterator<char>(filter_file), {}};
    spk::Json filters;
    check(spk::Json::parse(filter_text, filters, error), error);
    std::ofstream csv(dir / "comparisons.csv");
    csv.precision(17);
    csv << "scene,stage,group_cap,w,h,values,different_values,max_abs\n";
    struct Scene { const char* name; unsigned w, h, factor; bool simple, spatial; };
    const std::array<Scene, 5> scenes{{
        {"ultramax_sublayers", 257, 129, 1, false, true},
        {"ultramax_simple", 769, 33, 1, true, true},
        {"ultramax_preview", 257, 129, 7, false, true},
        {"thin_horizontal", 257, 1, 1, false, true},
        {"spatial_off", 65, 33, 1, false, false},
    }};
    for (const auto& scene : scenes) {
        spk::Params params;
        check(spk::init_params(argv[2], "kodak_ultramax_400", "kodak_ultra_endura",
                               filters, params, error), error);
        params.io.input_cctf_decoding = false;
        params.camera.auto_exposure = true;
        params.camera.auto_exposure_method = "balanced";
        params.camera.exposure_compensation_ev = 0.5;
        params.camera.film_format_mm = 36;
        params.settings.grain_sampler = "exact";
        params.film_render.grain.sublayers_active = !scene.simple;
        if (!scene.spatial) {
            params.film_render.grain.active = false;
            params.film_render.halation.active = false;
            params.film_render.dir_couplers.active = false;
            params.print_render.glare.active = false;
            params.scanner.lens_blur = 0;
            params.scanner.unsharp_mask[1] = 0;
        }
        spk::Image source;
        source.w = scene.w; source.h = scene.h; source.c = 3;
        const Pixels pixels = input_image(scene.w, scene.h);
        source.buf = gpu->upload(pixels.data(), source.bytes(), error);
        check(bool(source.buf), error);
        check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), 0, error), error);
        const Trace baseline = render(gpu.get(), colour, blob, cache, params, source, scene.factor, true);
        compare_public(baseline, render(gpu.get(), colour, blob, cache, params, source,
                                       scene.factor, false), scene.name, 0, csv);
        for (unsigned cap : {1u, 7u}) {
            check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), cap, error), error);
            const Trace chunked = render(gpu.get(), colour, blob, cache, params, source, scene.factor, true);
            check(baseline.size() == chunked.size(), "stage count changed");
            for (size_t i = 0; i < baseline.size(); ++i) {
                check(baseline[i].stage == chunked[i].stage, "stage order changed");
                compare(baseline[i], chunked[i], scene.name, cap, csv);
            }
            compare_public(chunked, render(gpu.get(), colour, blob, cache, params, source,
                                          scene.factor, false), scene.name, cap, csv);
        }
        std::cerr << "completed=" << scene.name << '\n';
    }
    check(spk::gpu::test_limit_dispatch_groups_x(gpu.get(), 0, error), error);
    const auto audit = gpu->pool_stats().audit;
    check(!audit.over_releases && !audit.pending_held && !audit.reclaim_while_encoding &&
          !audit.live_underflows, "GPU ownership audit failed");
    std::cout << "PASS scenes=" << scenes.size() << " frames=" << compared_frames
              << " float32_values=" << compared_values << " different_values=0 nonfinite=0\n";
    return 0;
} catch (const std::exception& exception) {
    std::cerr << exception.what() << '\n';
    return 1;
}
