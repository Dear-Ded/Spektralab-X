#include "resize.hpp"

#include <algorithm>
#include <cmath>
#include <functional>
#include <thread>

namespace spk::android {
namespace {

struct Taps {
    uint32_t first = 0;
    std::vector<float> weights;
};

std::vector<Taps> area_taps(uint32_t n, uint32_t nn) {
    const double scale = double(n) / double(nn);
    std::vector<Taps> out(nn);
    for (uint32_t o = 0; o < nn; ++o) {
        const double a = o * scale, b = a + scale;
        const uint32_t i0 = std::min<uint32_t>(n - 1, uint32_t(std::floor(a)));
        const uint32_t i1 = std::min<uint32_t>(n, std::max<uint32_t>(i0 + 1, uint32_t(std::ceil(b))));
        double sum = 0.0;
        out[o].first = i0;
        for (uint32_t i = i0; i < i1; ++i) {
            const double w = std::max(0.0, std::min<double>(i + 1, b) - std::max<double>(i, a));
            out[o].weights.push_back(float(w));
            sum += w;
        }
        if (sum <= 0.0) { out[o].weights.assign(1, 1.0f); continue; }
        for (float& w : out[o].weights) w = float(w / sum);
    }
    return out;
}

// Rows a thread could not be created for run on the caller: unwinding a
// vector of joinable threads would be std::terminate.
void parallel_rows(uint32_t rows, const std::function<void(uint32_t, uint32_t)>& body) {
    if (rows == 0) return;
    const uint32_t workers = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    const uint32_t chunk = (rows + workers - 1) / workers;
    std::vector<std::thread> threads;
    uint32_t spawned_to = std::min(rows, chunk);
    try {
        threads.reserve(workers);
        for (uint32_t start = chunk; start < rows; start += chunk) {
            const uint32_t end = std::min(rows, start + chunk);
            threads.emplace_back(body, start, end);
            spawned_to = end;
        }
    } catch (...) {
    }
    body(0, std::min(rows, chunk));
    if (spawned_to < rows) body(spawned_to, rows);
    for (auto& t : threads) t.join();
}

}  // namespace

void resize_area_rgb(const std::vector<float>& src, uint32_t w, uint32_t h,
                     uint32_t nw, uint32_t nh, std::vector<float>& dst) {
    const auto xt = area_taps(w, nw);
    const auto yt = area_taps(h, nh);
    std::vector<float> mid(size_t(nw) * h * 3);
    parallel_rows(h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            const float* row = src.data() + size_t(y) * w * 3;
            float* out = mid.data() + size_t(y) * nw * 3;
            for (uint32_t x = 0; x < nw; ++x) {
                float acc[3] = {0, 0, 0};
                const Taps& t = xt[x];
                for (size_t k = 0; k < t.weights.size(); ++k) {
                    const float* p = row + size_t(t.first + k) * 3;
                    acc[0] += t.weights[k] * p[0];
                    acc[1] += t.weights[k] * p[1];
                    acc[2] += t.weights[k] * p[2];
                }
                out[3 * x] = acc[0]; out[3 * x + 1] = acc[1]; out[3 * x + 2] = acc[2];
            }
        }
    });
    dst.assign(size_t(nw) * nh * 3, 0.0f);
    parallel_rows(nh, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            float* out = dst.data() + size_t(y) * nw * 3;
            const Taps& t = yt[y];
            for (size_t k = 0; k < t.weights.size(); ++k) {
                const float* row = mid.data() + size_t(t.first + k) * nw * 3;
                const float wk = t.weights[k];
                for (size_t i = 0; i < size_t(nw) * 3; ++i) out[i] += wk * row[i];
            }
        }
    });
}

}  // namespace spk::android
