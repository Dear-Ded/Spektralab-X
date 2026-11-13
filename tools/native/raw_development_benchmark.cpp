// Production RAW statistics/development A/B probe. Co-created with GPT (OpenAI).
#include "raw_development.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <vector>

using namespace spk::android;
using Clock = std::chrono::steady_clock;

int main(int argc, char** argv) {
    try {
        constexpr uint32_t w = 2048, h = 1536;
        std::vector<float> linear(size_t(w)*h*3), source(linear.size()), output(linear.size());
        uint32_t seed = 815732;
        for (size_t i = 0; i < linear.size(); ++i) {
            seed = seed*1664525+1013904223;
            const double value = std::exp2(-20.0+double(seed&0xffff)/65535*28);
            linear[i] = float(value);
            source[i] = float(value < 1.0/512 ? value*16 : std::pow(value,1.0/1.8));
        }
        RawDevelopmentStats stats;
        auto timed = [](auto&& body) {
            const auto start = Clock::now(); body();
            return std::chrono::duration<double,std::milli>(Clock::now()-start).count();
        };
        raw_development_stats(linear.data(),w,w,h);
        const double statistics_ms = timed([&] { stats = raw_development_stats(linear.data(),w,w,h); });
        std::ofstream dump;
        if (argc == 2) {
            dump.open(argv[1],std::ios::binary);
            if (!dump) throw std::runtime_error("cannot create parity dump");
            dump.write(reinterpret_cast<const char*>(stats.histogram.data()),sizeof(stats.histogram));
            dump.write(reinterpret_cast<const char*>(&stats.log2_mean),sizeof(stats.log2_mean));
        }
        std::cout << std::setprecision(12) << "statistics_ms," << statistics_ms << '\n';
        for (bool embedded : {false,true}) {
            RawDevelopmentMetadata meta;
            meta.default_black_render_present = true; meta.default_black_render = 1;
            meta.baseline_exposure_present = true; meta.baseline_exposure = .25;
            if (embedded) {
                meta.profile_tone_curve_present = true;
                meta.profile_tone_curve = {{{0,0}},{{.15f,.03f}},{{.4f,.3f}},{{.7f,.8f}},{{1,.997156f}}};
            }
            const auto plan = RawDevelopment::build(meta,stats,{true,true,-.25f});
            auto develop = [&] {
                std::copy(source.begin(),source.end(),output.begin());
                for (size_t i = 0; i < output.size(); i += 3) plan.apply_encoded_prophoto(output.data()+i);
            };
            develop();
            const double elapsed = timed(develop);
            std::cout << (embedded ? "embedded_ms," : "sigmoid_ms,") << elapsed << '\n';
            std::cout << (embedded ? "embedded_sum," : "sigmoid_sum,")
                      << std::accumulate(output.begin(),output.end(),0.0) << '\n';
            if (dump.is_open()) dump.write(reinterpret_cast<const char*>(output.data()),std::streamsize(output.size()*sizeof(float)));
        }
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n'; return 1;
    }
}
