// Synthetic contract failures for the still opt-in external-plane prototypes.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "pipeline/bounded_print_run.hpp"
#include <filesystem>
#include <iostream>
#include <limits>

static unsigned cases = 0;
static void check(bool ok, const char* message) {
    ++cases;
    if (!ok) throw std::runtime_error(message);
}
template<class F> static void rejects(F&& call, const char* message) {
    try { call(); } catch (const std::exception&) { ++cases; return; }
    throw std::runtime_error(message);
}
int main(int argc, char** argv) {
    try {
        check(argc == 2, "scratch directory required");
        std::filesystem::create_directories(argv[1]);
        for (uint32_t w : {1u, 17u, 4096u}) {
            const uint64_t row = uint64_t(w) * 12;
            rejects([&] { spk::choose_band_rows(100, w, row-1, row*3, 0); }, "point range underflow accepted");
            rejects([&] { spk::choose_band_rows(100, w, row, row*3-1, 0); }, "point budget underflow accepted");
            check(spk::choose_band_rows(100, w, row, row*3, 0) == 1, "exact point-row fit");
            for (uint32_t halo : {0u, 1u, 9u}) {
                const uint64_t minWindow = 2ull*halo+1;
                rejects([&] { spk::choose_window_rows(100, w, halo, row*(minWindow-1), row*minWindow*5, 0); }, "halo range underflow accepted");
                rejects([&] { spk::choose_window_rows(100, w, halo, row*minWindow, row*minWindow*5-1, 0); }, "halo budget underflow accepted");
                check(spk::choose_window_rows(100, w, halo, row*minWindow, row*minWindow*5, 0) == 1, "exact halo-row fit");
                check(spk::choose_window_rows(1, w, halo, row, row*5, 0) == 1, "whole short image fits despite halo");
            }
        }
        rejects([] { spk::choose_band_rows(0, 1, 1024, 1024, 0); }, "empty height accepted");
        rejects([] { spk::choose_band_rows(1, 0, 1024, 1024, 0); }, "empty width accepted");
        for (uint32_t halo : {0u, 1u, UINT32_MAX}) for (uint32_t h : {1u, 7u, 100u})
            for (uint64_t range : {uint64_t(0), uint64_t(12), uint64_t(120), uint64_t(12000)}) {
                try {
                    const auto n = spk::choose_window_rows(h, 1, halo, range, range*5, 0);
                    const uint64_t win = std::min<uint64_t>(h, uint64_t(n)+2ull*halo);
                    check(n>0 && n<=h && win*12<=range && win*12*5<=range*5, "chosen window exceeds its limits");
                } catch (const std::invalid_argument&) { ++cases; }
            }
        rejects([&] { spk::ExternalPlane p(argv[1], UINT32_MAX, UINT32_MAX, UINT32_MAX); }, "overflow plane accepted");
        spk::ExternalPlane input(argv[1], 3, 11, 3), output(argv[1], 3, 11, 3);
        rejects([&] { input.read_rows(0,1,nullptr); }, "null destination accepted");
        std::string error;
        spk::PrintRunTables table;
        table.unsharp_sigma=.7; table.unsharp_amount=.7; table.halo=0;
        // An invalid demand must fail before any GPU access.
#ifndef SPK_CONTRACT_BEFORE_FIX
        check(!spk::run_print_run(nullptr,table,input,output,1,spk::BandOrder::Forward,1,nullptr,error) && error.find("halo")!=std::string::npos, "undersized halo accepted");
        table.unsharp_sigma=3; table.halo=100;
        check(!spk::run_print_run(nullptr,table,input,output,1,spk::BandOrder::Forward,1,nullptr,error) && error.find("IIR")!=std::string::npos, "IIR allowed to restart at each band");
        table.unsharp_sigma=std::numeric_limits<double>::quiet_NaN();
        check(!spk::valid_print_run(table,error), "NaN blur accepted");
#endif
        std::cout << "PASS bounded planner and failure contracts: " << cases << " cases\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n'; return 1;
    }
}
