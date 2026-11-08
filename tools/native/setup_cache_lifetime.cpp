// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Link the production SetupCache implementation, replacing only costly builders.
#include "setup_cache.hpp"

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <thread>

namespace spk {
bool build_tc_lut(const Colour&, const Blob&, const Profile& film,
                  const SettingsParams&, const GamutCompressSpec&, const Vec&,
                  Vec& out, size_t& side, std::string&) {
    side = 2;
    out.assign(24, double(film.info.stock.at(0)));
    return true;
}
bool cam16_setup_for(const Colour&, const std::string&, Cam16Setup& out, std::string&) {
    out = Cam16Setup{};
    return true;
}
}  // namespace spk

int main() {
    std::shared_ptr<const spk::Vec> retained;
    std::atomic<bool> ok{true};
    {
        spk::SetupCache cache;
        const spk::Colour colour;
        const spk::Blob blob;
        const spk::SettingsParams settings;
        const spk::GamutCompressSpec compress;
        const spk::Vec sensitivity(243, 1);
        auto lookup = [&](char name, const spk::GamutCompressSpec* custom = nullptr) {
            spk::Profile film;
            film.info.stock.assign(1, name);
            std::string error;
            size_t side = 0;
            std::shared_ptr<const spk::Vec> lut;
            if (!cache.tc_lut(colour, blob, film, settings, custom ? *custom : compress, sensitivity, lut, side, error) ||
                !lut || side != 2 || lut->size() != 24) std::abort();
            return lut;
        };
        lookup('A'); lookup('B'); lookup('C');
        retained = lookup('D');
        lookup('A'); // Deterministically replaced the old pointer's payload.
        if (retained->at(0) != 'D') return 1;
        for (char stock = 'E'; stock <= 'Z'; ++stock) lookup(stock);
        if (retained->at(0) != 'D' || cache.stats().lut_entries != 4) return 1;
        auto run = [&](int offset) {
            for (int i = 0; i < 2000; ++i) {
                const char name = char('A' + (i + offset) % 26);
                const auto lut = lookup(name);
                std::this_thread::yield();
                for (double value : *lut) if (value != name) ok = false;
                if (retained->at(0) != 'D') ok = false;
            }
        };
        std::thread a(run, 0), b(run, 7);
        a.join(); b.join();
        spk::GamutCompressSpec first;
        first.knee[0] = 0.12345678901234567;
        first.knee[1] = 0.23456789012345678;
        first.knee[2] = 0.34567890123456789;
        auto second = first;
        second.knee[2] = std::nextafter(first.knee[2], 1.0);
        char key_a[128], key_b[128];
        std::snprintf(key_a, sizeof key_a, "|cxy,%.17g,%.17g,%.17g", first.knee[0], first.knee[1], first.knee[2]);
        std::snprintf(key_b, sizeof key_b, "|cxy,%.17g,%.17g,%.17g", second.knee[0], second.knee[1], second.knee[2]);
        if (std::string(key_a).substr(0, 63) != std::string(key_b).substr(0, 63)) {
            std::fputs("collision fixture no longer exercises truncation\n", stderr); return 1;
        }
        if (lookup('K', &first) == lookup('K', &second)) { std::fputs("distinct gamut parameters shared a LUT\n", stderr); return 1; }
    }
    if (!ok || retained->at(0) != 'D') return 1;
    std::puts("setup cache: rotation, eviction, 4000 concurrent reads, complete gamut keys and cache destruction passed");
}
