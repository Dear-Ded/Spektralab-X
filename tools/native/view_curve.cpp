// Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
#include "pipeline/view_curve.hpp"
#include <bit>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <new>
#include <random>

namespace {
size_t allocations = 0;
bool count_allocations = false;
using Points = std::vector<std::array<double, 2>>;

// Frozen pre-0055 implementation from pipeline/view_process.cpp.
double reference(const Points& p, double x) {
    size_t n=p.size(); if(x<=p.front()[0])return p.front()[1]; if(x>=p.back()[0])return p.back()[1];
    std::vector<double> d(n-1),m(n);
    for(size_t i=0;i+1<n;++i)d[i]=(p[i+1][1]-p[i][1])/std::max(p[i+1][0]-p[i][0],1e-6);
    m[0]=d[0];m[n-1]=d[n-2];
    for(size_t i=1;i+1<n;++i)m[i]=d[i-1]*d[i]<=0?0:(d[i-1]+d[i])/2;
    for(size_t i=0;i+1<n;++i)if(d[i]!=0){double a=m[i]/d[i],b=m[i+1]/d[i],s=a*a+b*b;if(s>9){double t=3/std::sqrt(s);m[i]=t*a*d[i];m[i+1]=t*b*d[i];}}
    size_t i=0;while(i<n-2&&x>p[i+1][0])++i;
    double h=std::max(p[i+1][0]-p[i][0],1e-6),t=(x-p[i][0])/h,t2=t*t,t3=t2*t;
    return std::clamp((2*t3-3*t2+1)*p[i][1]+(t3-2*t2+t)*h*m[i]+(-2*t3+3*t2)*p[i+1][1]+(t3-t2)*h*m[i+1],0.0,1.0);
}

uint64_t checked = 0;
void compare(const Points& p) {
    const spk::view::detail::PreparedCurve prepared(p);
    const auto at = [&](double x) {
        const double want = reference(p, x), got = prepared.evaluate(x);
        if (std::bit_cast<uint64_t>(want) != std::bit_cast<uint64_t>(got) ||
            std::bit_cast<uint32_t>(float(want)) != std::bit_cast<uint32_t>(float(got))) {
            std::fprintf(stderr, "curve mismatch: points=%zu x=%.17g want=%.17g got=%.17g\n", p.size(), x, want, got);
            std::exit(1);
        }
        ++checked;
    };
    for (unsigned x = 0; x < 256; ++x) at(double(x) / 255);
    for (const auto& q : p) {
        at(q[0]);
        at(std::nextafter(q[0], -std::numeric_limits<double>::infinity()));
        at(std::nextafter(q[0], std::numeric_limits<double>::infinity()));
    }
    at(-1); at(2);
}

double tables(const std::vector<Points>& curves, bool prepared, unsigned repeats) {
    double sum = 0;
    for (unsigned r = 0; r < repeats; ++r) for (const auto& p : curves) {
        if (prepared) {
            const spk::view::detail::PreparedCurve curve(p);
            for (unsigned x = 0; x < 256; ++x) sum += float(curve.evaluate(double(x) / 255));
        } else {
            for (unsigned x = 0; x < 256; ++x) sum += float(reference(p, double(x) / 255));
        }
    }
    return sum;
}
}

void* operator new(size_t bytes) {
    if (count_allocations) ++allocations;
    if (void* p = std::malloc(bytes ? bytes : 1)) return p;
    throw std::bad_alloc();
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, size_t) noexcept { std::free(p); }

int main() {
    std::vector<Points> curves = {
        {{0,0},{1,1}}, {{0,1},{1,0}}, {{0,0.5},{1,0.5}},
        {{0.1,0.2},{0.2,0.9},{0.3,0.1},{0.7,0.8},{0.9,0.4}},
        {{0,0},{1e-12,1},{2e-12,0},{0.5,1},{1,0}},
        {{0,0},{0.25,0},{0.5,0.9},{0.75,0.9},{1,1}},
    };
    std::mt19937_64 rng(0x53504b4355525645ULL);
    for (size_t n : {2,3,4,16,64,128,256}) for (unsigned trial = 0; trial < 40; ++trial) {
        Points p;
        for (size_t i = 0; i < n; ++i)
            p.push_back({double(i) / (n - 1), double(rng() >> 11) / 9007199254740992.0});
        if (trial % 4 == 0) for (size_t i = 1; i < n; ++i) p[i][1] = p[i - 1][1];
        if (trial % 4 == 1) for (size_t i = 0; i < n; ++i) p[i][0] *= 1e-8;
        curves.push_back(std::move(p));
    }
    for (const auto& p : curves) compare(p);
    std::vector<Points> bench(curves.end() - 5, curves.end());
    allocations = 0; count_allocations = true;
    const double old_sum = tables(bench, false, 1);
    const size_t old_allocations = allocations;
    allocations = 0;
    const double new_sum = tables(bench, true, 1);
    const size_t new_allocations = allocations;
    count_allocations = false;
    if (old_sum != new_sum || old_allocations < 2000 || new_allocations > 15) return 2;
    std::printf("curves,samples,double_and_float_bit_mismatches,old_allocations_5_curves,new_allocations_5_curves\n");
    std::printf("%zu,%llu,0,%zu,%zu\n", curves.size(), static_cast<unsigned long long>(checked), old_allocations, new_allocations);
    const auto before = std::chrono::steady_clock::now();
    const double old_bench = tables(bench, false, 100);
    const auto middle = std::chrono::steady_clock::now();
    const double new_bench = tables(bench, true, 100);
    const auto after = std::chrono::steady_clock::now();
    if (old_bench != new_bench) return 3;
    std::fprintf(stderr, "PASS prepared view curves; old_ms=%.3f new_ms=%.3f checksum=%.17g\n",
        std::chrono::duration<double,std::milli>(middle-before).count(),
        std::chrono::duration<double,std::milli>(after-middle).count(), new_bench);
}
