#include "resize.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <functional>
#include <limits>
#include <mutex>
#include <stdexcept>
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

// Workers a thread could not be created for run on the caller: unwinding a
// vector of joinable threads would be std::terminate.
void parallel_workers(uint32_t workers, const std::function<void(uint32_t)>& body) {
    std::vector<std::thread> threads;
    uint32_t spawned_to = 1;
    try {
        threads.reserve(workers - 1);
        for (uint32_t worker = 1; worker < workers; ++worker) {
            threads.emplace_back(body, worker);
            spawned_to = worker + 1;
        }
    } catch (...) {
    }
    body(0);
    for (uint32_t worker = spawned_to; worker < workers; ++worker) body(worker);
    for (auto& t : threads) t.join();
}

size_t rgb_count(uint32_t w, uint32_t h) {
    if (!w || !h || uint64_t(w) * h > std::numeric_limits<size_t>::max() / 3)
        throw std::invalid_argument("resize: invalid image dimensions");
    return size_t(w) * h * 3;
}

uint32_t worker_count(uint32_t nh) {
    return std::min(nh, std::max(1u, std::min(8u, std::thread::hardware_concurrency())));
}

template<class LoadRow, class Stop>
void resize_rows(uint32_t w, uint32_t h, uint32_t nw, uint32_t nh,
                 std::vector<float>& dst, const LoadRow& load_row, const Stop& stop) {
    const auto xt = area_taps(w, nw);
    const auto yt = area_taps(h, nh);
    const uint32_t workers = worker_count(nh);
    constexpr uint32_t cache_rows = 4;
    const size_t row_size = size_t(nw) * 3;
    // Allocate before starting any worker: bad_alloc must unwind on the caller.
    if (row_size > std::numeric_limits<size_t>::max() / (cache_rows * workers))
        throw std::length_error("resize: row cache too large");
    std::vector<float> cache(row_size * cache_rows * workers);
    dst.assign(rgb_count(nw, nh), 0.0f);
    parallel_workers(workers, [&](uint32_t worker) {
        const uint32_t y0 = uint32_t(uint64_t(nh) * worker / workers);
        const uint32_t y1 = uint32_t(uint64_t(nh) * (worker + 1) / workers);
        uint32_t tags[cache_rows];
        std::fill_n(tags, cache_rows, std::numeric_limits<uint32_t>::max());
        float* scratch = cache.data() + row_size * cache_rows * worker;
        for (uint32_t y = y0; y < y1; ++y) {
            if (stop()) return;
            float* out = dst.data() + size_t(y) * row_size;
            const Taps& t = yt[y];
            for (size_t k = 0; k < t.weights.size(); ++k) {
                if (stop()) return;
                const uint32_t iy = t.first + uint32_t(k), slot = iy % cache_rows;
                float* row = scratch + slot * row_size;
                if (tags[slot] != iy) {
                    const float* input = load_row(iy, worker);
                    if (!input) return;
                    for (uint32_t x = 0; x < nw; ++x) {
                        float acc[3] = {0, 0, 0};
                        const Taps& tx = xt[x];
                        for (size_t j = 0; j < tx.weights.size(); ++j) {
                            const float* p = input + size_t(tx.first + j) * 3;
                            acc[0] += tx.weights[j] * p[0];
                            acc[1] += tx.weights[j] * p[1];
                            acc[2] += tx.weights[j] * p[2];
                        }
                        row[3 * x] = acc[0]; row[3 * x + 1] = acc[1]; row[3 * x + 2] = acc[2];
                    }
                    tags[slot] = iy;
                }
                const float wk = t.weights[k];
                // Keep the original k-major accumulation, including zero taps.
                for (size_t i = 0; i < row_size; ++i) out[i] += wk * row[i];
            }
        }
    });
}

}  // namespace

void fit_rgb_size(uint32_t w, uint32_t h, uint32_t max_edge, uint64_t max_pixels,
                  uint32_t& nw, uint32_t& nh) {
    double s = 1.0;
    if (max_edge && std::max(w, h) > max_edge) s = double(max_edge) / double(std::max(w, h));
    if (max_pixels && double(w) * h * s * s > double(max_pixels))
        s = std::sqrt(double(max_pixels) / (double(w) * h));
    nw = w; nh = h;
    if (s >= 1.0) return;
    nw = std::max<uint32_t>(1, uint32_t(std::floor(w * s)));
    nh = std::max<uint32_t>(1, uint32_t(std::floor(h * s)));
}

void resize_area_rgb(const std::vector<float>& src, uint32_t w, uint32_t h,
                     uint32_t nw, uint32_t nh, std::vector<float>& dst) {
    const size_t source_count = rgb_count(w, h);
    rgb_count(nw, nh);
    if (nw > w || nh > h || src.size() != source_count)
        throw std::invalid_argument("resize: expected a packed RGB image and a downscale");
    if (&src == &dst) {
        std::vector<float> result;
        resize_area_rgb(src, w, h, nw, nh, result);
        dst = std::move(result);
        return;
    }
    resize_rows(w, h, nw, nh, dst,
        [&](uint32_t y, uint32_t) { return src.data() + size_t(y) * w * 3; },
        [] { return false; });
}

bool resize_area_rgb16(const uint8_t* src, size_t bytes, uint32_t w, uint32_t h,
                       uint32_t nw, uint32_t nh, std::vector<float>& dst,
                       const std::function<bool()>& cancelled) {
    const size_t count = rgb_count(w, h);
    rgb_count(nw, nh);
    if (!src || nw > w || nh > h || count > std::numeric_limits<size_t>::max() / sizeof(uint16_t) ||
        bytes != count * sizeof(uint16_t))
        throw std::invalid_argument("resize: expected packed native-endian RGB16 and a downscale");
    std::atomic<bool> stopped{false};
    std::mutex poll_mutex;
    const auto stop = [&]() noexcept {
        if (stopped.load(std::memory_order_relaxed)) return true;
        if (!cancelled) return false;
        try {
            std::lock_guard<std::mutex> guard(poll_mutex);
            if (!stopped.load(std::memory_order_relaxed) && cancelled())
                stopped.store(true, std::memory_order_relaxed);
        } catch (...) {
            stopped.store(true, std::memory_order_relaxed);
        }
        return stopped.load(std::memory_order_relaxed);
    };
    if (stop()) return false;
    const size_t row_size = size_t(w) * 3;
    const uint32_t workers = worker_count(nh);
    if (row_size > std::numeric_limits<size_t>::max() / workers)
        throw std::length_error("resize: RGB16 row cache too large");
    std::vector<float> source_rows(row_size * workers), result;
    resize_rows(w, h, nw, nh, result, [&](uint32_t y, uint32_t worker) -> const float* {
        float* row = source_rows.data() + row_size * worker;
        const uint8_t* input = src + size_t(y) * row_size * sizeof(uint16_t);
        for (size_t i = 0; i < row_size; ++i) {
            if (i % 65536 == 0 && stop()) return nullptr;
            uint16_t value;
            std::memcpy(&value, input + i * sizeof(value), sizeof(value));
            row[i] = static_cast<float>(static_cast<double>(value) / 65535.0);
        }
        return row;
    }, stop);
    if (stop()) return false;
    dst = std::move(result);
    return true;
}

}  // namespace spk::android
