// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Actual session tiers, RGBA16 readback and cached print edits; inputs stay local.
#include "spektrafilm/spk_engine.h"
#include "resize.hpp"
#include "rgba16_frame_check.hpp"
#include "pipeline/view.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
void check(bool ok, const std::string& why) { if (!ok) throw std::runtime_error(why); }
using Engine = std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)>;
using Session = std::unique_ptr<spk_session, decltype(&spk_session_release)>;
struct Result {
    spk_result value{};
    ~Result() { spk_result_free(&value); }
};
std::vector<uint16_t> render(spk_engine* engine, spk_session* session, const char* tier,
                           const char* name, const std::filesystem::path& dir,
                           std::ofstream& csv, bool must_reuse = false) {
    Result result;
    const spk_status status = spk_reprint(session, tier, &result.value);
    check(status == SPK_OK, std::string("render ") + tier + " status=" +
          std::to_string(status) + ": " + spk_last_error(engine));
    const auto& r = result.value;
    check(r.rgba16 && r.width && r.height && r.row_stride_px >= r.width, "invalid result layout");
    const std::string frame_error = spk::android::check_rgba16_frame(r.rgba16, r.width, r.height, r.row_stride_px);
    check(frame_error.empty(), "export check: " + frame_error);
    check(!must_reuse || r.negative_was_cached, "print edit unexpectedly discarded negative");
    std::vector<uint16_t> pixels(size_t(r.width) * r.height * 4);
    const unsigned step = std::max(1u, r.width / 512);
    const unsigned sw = (r.width + step - 1) / step, sh = (r.height + step - 1) / step;
    std::ofstream sample(dir / (std::string(name) + ".f32"), std::ios::binary);
    uint64_t hash = 14695981039346656037ull;
    size_t black_rows = 0, white_rows = 0, alpha_errors = 0;
    for (unsigned y = 0; y < r.height; ++y) {
        const uint16_t* row = r.rgba16 + size_t(y) * r.row_stride_px * 4;
        std::copy_n(row, size_t(r.width) * 4, pixels.data() + size_t(y) * r.width * 4);
        unsigned lo = 65535, hi = 0;
        for (unsigned x = 0; x < r.width; ++x) {
            for (unsigned c = 0; c < 4; ++c) hash = (hash ^ row[x * 4 + c]) * 1099511628211ull;
            alpha_errors += row[x * 4 + 3] != 65535;
            for (unsigned c = 0; c < 3; ++c) {
                lo = std::min(lo, unsigned(row[x * 4 + c]));
                hi = std::max(hi, unsigned(row[x * 4 + c]));
            }
            if (y % step == 0 && x % step == 0) {
                const float rgb[3] = {row[x * 4] / 65535.f, row[x * 4 + 1] / 65535.f,
                                      row[x * 4 + 2] / 65535.f};
                sample.write(reinterpret_cast<const char*>(rgb), sizeof(rgb));
            }
        }
        black_rows += hi <= 16;
        white_rows += lo >= 65535 - 16;
    }
    csv << name << ',' << r.width << ',' << r.height << ',' << sw << ',' << sh << ','
        << r.row_stride_px << ',' << r.negative_was_cached << ',' << black_rows << ','
        << white_rows << ',' << alpha_errors << ',' << hash << '\n';
    csv.flush();
    check(bool(sample) && bool(csv), "session evidence write failed");
    check(!alpha_errors, "RGBA16 alpha corruption");
    std::cerr << "tier=" << name << " shape=" << r.width << 'x' << r.height
              << " cached=" << r.negative_was_cached << " black_rows=" << black_rows << '\n';
    return pixels;
}
}

int main(int argc, char** argv) try {
    check(argc >= 7 && argc <= 9,
          "usage: feedback_session RESOURCES INPUT_F32|--synthetic WIDTH HEIGHT OUTPUT_DIR EDGE [--first-pass] [--warm-engine]");
    bool first_pass = false, warm = false;
    for (int i = 7; i < argc; ++i) {
        const std::string option = argv[i];
        if (option == "--first-pass") first_pass = true;
        else if (option == "--warm-engine") warm = true;
        else check(false, "unknown option: " + option);
    }
    const unsigned w = std::stoul(argv[3]), h = std::stoul(argv[4]), edge = std::stoul(argv[6]);
    check(w && h && uint64_t(w) * h <= 100000000, "input dimensions out of range");
    const std::filesystem::path dir = argv[5];
    std::filesystem::create_directories(dir);
    std::vector<float> input(size_t(w) * h * 3);
    if (std::string(argv[2]) == "--synthetic") {
        for (unsigned y = 0; y < h; ++y) for (unsigned x = 0; x < w; ++x)
            for (unsigned c = 0; c < 3; ++c)
                input[(size_t(y) * w + x) * 3 + c] = 0.002f +
                    0.3f * x / std::max(1u, w - 1) + 0.2f * y / std::max(1u, h - 1) +
                    float((x / 17 + y / 11 + c) % 3) * 0.06f;
    } else {
        std::ifstream file(argv[2], std::ios::binary);
        file.read(reinterpret_cast<char*>(input.data()), input.size() * sizeof(float));
        check(bool(file) && file.peek() == std::ifstream::traits_type::eof(), "input payload size mismatch");
    }
    for (float value : input) check(std::isfinite(value), "nonfinite input");
    unsigned rw = w, rh = h;
    if (edge && std::max(w, h) > edge) {
        const double ratio = double(edge) / std::max(w, h);
        rw = std::max(1u, unsigned(std::floor(w * ratio)));
        rh = std::max(1u, unsigned(std::floor(h * ratio)));
        spk::android::resize_area_rgb(input, w, h, rw, rh, input);
    }
    Engine engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
    check(bool(engine), std::string("engine: ") + spk_last_error(nullptr));
    const auto save_capabilities = [&] {
        std::ofstream file(dir / "capabilities.json");
        file << spk_capabilities(engine.get()) << '\n';
        check(bool(file), "capabilities write failed");
    };
    save_capabilities();
    const spk_image image{input.data(), rw, rh, 3};
    const char* params = R"({"film_stock":"kodak_ultramax_400","print_stock":"kodak_ultra_endura","input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"auto_exposure":true,"auto_exposure_method":"balanced","exposure_compensation_ev":0.5,"film_format_mm":36,"print_exposure":1,"preview_long_edge":1024})";
    std::ofstream csv(dir / "tiers.csv");
    csv << "tier,w,h,sample_w,sample_h,stride,cached,black_rows,white_rows,alpha_errors,fnv64_words\n";
    const auto report = [&](const char* phase) {
        std::ofstream file(dir / (std::string(phase) + "-memory.json"));
        file << spk_memory_report(engine.get()) << '\n';
        check(bool(file), "memory report write failed");
    };
    if (warm) {
        constexpr unsigned pw = 4096, ph = 3072;
        std::vector<float> previous(size_t(pw) * ph * 3);
        for (unsigned y = 0; y < ph; ++y) for (unsigned x = 0; x < pw; ++x)
            for (unsigned c = 0; c < 3; ++c)
                previous[(size_t(y) * pw + x) * 3 + c] =
                    0.01f + 0.3f * x / pw + 0.15f * y / ph + 0.02f * c;
        const spk_image prior{previous.data(), pw, ph, 3};
        Session old(spk_open(engine.get(), &prior, params, nullptr), spk_session_release);
        check(bool(old), "warm previous open");
        previous.clear(); previous.shrink_to_fit();
        render(engine.get(), old.get(), "full", "previous-export", dir, csv);
        old.reset();
        std::cerr << "trim_after_previous=" << spk::view::trim_idle_memory(engine.get()) << '\n';
    }
    Session session(spk_open(engine.get(), &image, params, nullptr), spk_session_release);
    check(bool(session), std::string("open: ") + spk_last_error(engine.get()));
    Session editor(nullptr, spk_session_release);
    if (warm) {
        render(engine.get(), session.get(), "live", "editor-live", dir, csv);
        render(engine.get(), session.get(), "preview", "editor-preview", dir, csv);
        const unsigned cw = std::min(384u, rw), ch = std::max(1u, unsigned(uint64_t(rh) * cw / rw));
        std::vector<float> small;
        spk::android::resize_area_rgb(input, rw, rh, cw, ch, small);
        const spk_image card_image{small.data(), cw, ch, 3};
        std::vector<std::filesystem::path> profiles;
        for (const auto& item : std::filesystem::directory_iterator(std::filesystem::path(argv[1]) / "profiles"))
            if (item.path().extension() == ".json") profiles.push_back(item.path());
        std::sort(profiles.begin(), profiles.end());
        std::map<std::string, spk::Json> roots;
        for (const auto& file : profiles) {
            std::ifstream source(file);
            const std::string text{std::istreambuf_iterator<char>(source), {}};
            spk::Json root; std::string error;
            check(spk::Json::parse(text, root, error), "profile: " + error);
            roots.emplace(file.stem().string(), std::move(root));
        }
        unsigned cards = 0;
        for (const auto& [stock, root] : roots) {
            auto info = root.at("info");
            const auto& base = root.at("derived_from").as_string();
            if (!base.empty()) {
                auto merged = roots.at(base).at("info");
                for (const auto& [key, value] : info.fields()) merged.set(key, value);
                info = std::move(merged);
            }
            if (info.at("stage").as_string() != "filming") continue;
            spk::Json settings; std::string error;
            check(spk::Json::parse(params, settings, error), error);
            settings.set("film_stock", spk::Json(stock));
            settings.set("scan_film", spk::Json(info.at("type").as_string() == "positive"));
            if (info.at("channel_model").as_string() == "bw" && info.at("target_print").is_string())
                settings.set("print_stock", info.at("target_print"));
            const std::string opening = settings.dump();
            Session card(spk_open(engine.get(), &card_image, opening.c_str(), nullptr), spk_session_release);
            check(bool(card), "card open: " + stock);
            render(engine.get(), card.get(), "full", ("card-" + stock).c_str(), dir, csv);
            ++cards;
        }
        check(cards > 0, "no film cards exercised");
        std::cerr << "film_cards=" << cards << '\n';
        report("before-trim");
        std::cerr << "trim_with_editor=" << spk::view::trim_idle_memory(engine.get()) << '\n';
        report("after-trim");
        editor = std::move(session);
        session.reset(spk_open(engine.get(), &image, params, nullptr));
        check(bool(session), std::string("export open: ") + spk_last_error(engine.get()));
    }
    input.clear(); input.shrink_to_fit();
    for (const char* tier : {"live", "preview", "full"})
        render(engine.get(), session.get(), tier, tier, dir, csv);
    if (warm) {
        report("after-export");
        spk::view::trim_idle_memory(engine.get());
        render(engine.get(), editor.get(), "preview", "editor-after-export", dir, csv, true);
    }
    if (first_pass) {
        save_capabilities();
        std::cout << "PASS session tiers=3 cache_checks=0 input=" << rw << 'x' << rh << '\n';
        return 0;
    }
    // Disable random print glare only for exact cache/reversion comparisons.
    check(spk_set_params(session.get(), R"({"glare_active":false})", nullptr) == SPK_OK, "disable glare");
    const auto stable = render(engine.get(), session.get(), "full", "stable", dir, csv, true);
    check(stable == render(engine.get(), session.get(), "full", "repeat", dir, csv, true), "cached repeat changed");
    check(spk_set_params(session.get(), R"({"print_exposure":1.25})", nullptr) == SPK_OK, "change print exposure");
    check(stable != render(engine.get(), session.get(), "full", "edited", dir, csv, true), "print edit had no effect");
    check(spk_set_params(session.get(), R"({"print_exposure":1})", nullptr) == SPK_OK, "restore print exposure");
    check(stable == render(engine.get(), session.get(), "full", "restored", dir, csv, true), "restored print differs");
    save_capabilities();
    std::cout << "PASS session tiers=3 cache_checks=3 input=" << rw << 'x' << rh << '\n';
    return 0;
} catch (const std::exception& exception) {
    std::cerr << exception.what() << '\n';
    return 1;
}
