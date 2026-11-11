// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Rejected rebuilds preserve the accepted C ABI session and cached negative.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "spektrafilm/spk_engine.h"
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
unsigned checks = 0, failures = 0;
void check(bool ok, const std::string& name) {
    ++checks;
    if (!ok) { ++failures; std::cerr << "FAIL " << name << '\n'; }
}
void require(bool ok, const std::string& name) {
    if (!ok) throw std::runtime_error(name);
}
using Engine = std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)>;
using Session = std::unique_ptr<spk_session, decltype(&spk_session_release)>;
std::string params(spk_session* s) {
    char* text = nullptr;
    require(spk_get_params(s, &text) == SPK_OK && text, "get params");
    std::unique_ptr<char, decltype(&spk_string_free)> owned(text, spk_string_free);
    return text;
}
std::vector<uint16_t> render(spk_session* s, spk_engine* e, bool* cached = nullptr) {
    spk_result r{};
    require(spk_reprint(s, "full", &r) == SPK_OK, std::string("render: ") + spk_last_error(e));
    require(r.rgba16 && r.width && r.height, "empty result");
    std::vector<uint16_t> pixels;
    pixels.reserve(size_t(r.width)*r.height*4);
    for (uint32_t y = 0; y < r.height; ++y)
        pixels.insert(pixels.end(), r.rgba16 + size_t(y)*r.row_stride_px*4,
                      r.rgba16 + (size_t(y)*r.row_stride_px+r.width)*4);
    if (cached) *cached = r.negative_was_cached != 0;
    spk_result_free(&r);
    return pixels;
}
void set(spk_session* s, spk_engine* e, const std::string& value) {
    require(spk_set_params(s, value.c_str(), nullptr) == SPK_OK,
            std::string("accepted delta: ") + spk_last_error(e));
}
}

int main(int argc, char** argv) {
    try {
        require(argc == 2 || argc == 3, "resource directory and optional synthetic output required");
        std::vector<uint16_t> accepted_outputs;
        auto record = [&](const std::vector<uint16_t>& output) {
            accepted_outputs.insert(accepted_outputs.end(), output.begin(), output.end());
        };
        Engine engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        require(bool(engine), std::string("engine: ") + spk_last_error(nullptr));
        constexpr uint32_t w = 29, h = 19;
        std::vector<float> pixels(size_t(w)*h*3);
        for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x)
            for (uint32_t c = 0; c < 3; ++c)
                pixels[(size_t(y)*w+x)*3+c] = .012f + .6f*float(x+2*y+c)/float(w+2*h+3);
        const spk_image image{pixels.data(), w, h, 3};
        const char* opening = R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","print_stock":"ilford_multigrade_iv_rc","extended_dynamic_range":false,"grain_active":false,"glare_active":false,"halation_active":false,"dir_couplers_active":false,"auto_exposure":false,"scanner_unsharp_amount":0,"product_defaults":true})";
        auto open = [&] {
            Session s(spk_open(engine.get(), &image, opening, nullptr), spk_session_release);
            require(bool(s), std::string("open: ") + spk_last_error(engine.get()));
            return s;
        };
        const char* rejected[] = {
            R"({"output_color_space":"not-a-baked-space"})",
            R"({"extended_dynamic_range":true})",
            R"({"input_color_space":"not-a-baked-space"})",
            R"({"film_stock":"kodak_gold_200","output_color_space":"not-a-baked-space"})",
            R"({"print_stock":"kodak_portra_endura","output_color_space":"not-a-baked-space"})"
        };
        for (const char* bad : rejected) {
            auto s = open();
            const auto before = params(s.get());
            const auto reference = render(s.get(), engine.get());
            bool warm = false;
            check(render(s.get(), engine.get(), &warm) == reference && warm, "initial cache warm");
            char* reply = nullptr;
            const auto status = spk_set_params(s.get(), bad, &reply);
            check(status == SPK_ERR_USER, std::string("build refusal: ") + bad);
            check(reply == nullptr, "rejected delta publishes no reply");
            spk_string_free(reply);
            const bool unchanged = params(s.get()) == before;
            check(unchanged, std::string("params preserved: ") + bad);
            bool cached = false;
            check(render(s.get(), engine.get(), &cached) == reference && cached,
                  "old pipeline and cached negative remain usable");
            auto recovery_control = open();
            render(recovery_control.get(), engine.get());
            set(s.get(), engine.get(), R"({"print_exposure":1.25})");
            set(recovery_control.get(), engine.get(), R"({"print_exposure":1.25})");
            check(params(s.get()) == params(recovery_control.get()),
                  "ordinary live delta recovers accepted params without full restore");
            const auto recovered = spk_set_params(s.get(), R"({"scanner_unsharp_amount":0.4})", nullptr);
            check(recovered == SPK_OK, "ordinary rebuild delta succeeds without full restore");
            set(recovery_control.get(), engine.get(), R"({"scanner_unsharp_amount":0.4})");
            check(recovered == SPK_OK && render(s.get(), engine.get()) == render(recovery_control.get(), engine.get()),
                  "ordinary rebuild recovery output equals fresh control");
            // Restore the accepted full snapshot on both implementations. On
            // the candidate this is an ordinary successful no-change rebuild.
            set(s.get(), engine.get(), before);
            check(params(s.get()) == before, "full snapshot restores accepted params");
            check(render(s.get(), engine.get()) == reference, "full snapshot restores output bytes");
            record(reference);
            auto control = open();
            render(control.get(), engine.get());
            set(s.get(), engine.get(), R"({"print_exposure":1.25})");
            set(control.get(), engine.get(), R"({"print_exposure":1.25})");
            check(params(s.get()) == params(control.get()), "live delta params after failure equal fresh control");
            const auto live_output = render(s.get(), engine.get());
            check(live_output == render(control.get(), engine.get()),
                  "live delta output after failure equals fresh control");
            record(live_output);
            set(s.get(), engine.get(), R"({"scanner_unsharp_amount":0.4})");
            set(control.get(), engine.get(), R"({"scanner_unsharp_amount":0.4})");
            const auto print_output = render(s.get(), engine.get());
            check(print_output == render(control.get(), engine.get()),
                  "print rebuild output after failure equals fresh control");
            record(print_output);
            set(s.get(), engine.get(), R"({"exposure_compensation_ev":0.25})");
            set(control.get(), engine.get(), R"({"exposure_compensation_ev":0.25})");
            const auto shoot_output = render(s.get(), engine.get());
            check(shoot_output == render(control.get(), engine.get()),
                  "shoot rebuild output after failure equals fresh control");
            record(shoot_output);
        }
        if (argc == 3) {
            std::ofstream output(argv[2], std::ios::binary);
            output.write(reinterpret_cast<const char*>(accepted_outputs.data()),
                         std::streamsize(accepted_outputs.size()*sizeof(uint16_t)));
            require(bool(output), "write synthetic accepted-output bytes");
        }
        std::cout << "params_transaction checks=" << checks << " failures=" << failures
                  << " failure_classes=output,print,shoot,film-stock,print-stock full_snapshot=true byte_exact=true\n";
        return failures ? 1 : 0;
    } catch (const std::exception& e) {
        std::cerr << "ERROR " << e.what() << '\n';
        return 2;
    }
}
