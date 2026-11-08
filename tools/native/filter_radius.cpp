// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#include "pipeline/blur.hpp"

#include <bit>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <new>

namespace { bool refuse = false; }
void* operator new(size_t size) {
    if (refuse) throw std::bad_alloc();
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, size_t) noexcept { std::free(p); }
void operator delete[](void* p, size_t) noexcept { std::free(p); }

int main() {
    size_t cases = 0;
    for (double truncate : {1.0, 3.0, 4.0, 8.0}) {
        for (size_t k = 0; k < 300; ++k) {
            const double centre = (double(k) + 0.5) / truncate;
            for (double sigma : {std::nextafter(centre, 0.0), centre, std::nextafter(centre, 1000.0)}) {
                const size_t expected = size_t(truncate * sigma + 0.5);
                spk::Vec before(2 * expected + 1), after;
                double total = 0;
                for (size_t i = 0; i < before.size(); ++i) {
                    const double x = double(i) - double(expected);
                    before[i] = std::exp(-0.5 * (x / sigma) * (x / sigma)); total += before[i];
                }
                for (double& value : before) value /= total;
                if (spk::gaussian_kernel_radius(sigma, truncate) != expected ||
                    spk::gaussian_kernel_1d(sigma, truncate, after) != expected || before != after) std::abort();
                refuse = true;
                const auto radius = spk::Blur::fir_radius(sigma, truncate);
                refuse = false;
                if (radius != (spk::Blur::is_fir(sigma) ? expected : 0)) std::abort();
                ++cases;
            }
        }
    }
    for (double sigma : {-100.0, -1.0, -0.0, 0.0}) {
        spk::Vec values;
        if (spk::gaussian_kernel_radius(sigma, 3) || spk::gaussian_kernel_1d(sigma, 3, values) ||
            values != spk::Vec{1.0}) std::abort();
    }
    std::printf("FIR: %zu radius boundaries and kernel bytes unchanged; demand allocation-free; nonpositive sigma identity passed\n", cases);
}
