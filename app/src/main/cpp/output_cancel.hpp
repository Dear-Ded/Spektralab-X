// Request-local cancellation survives the engine's per-render progress reset.
#pragma once
#include <atomic>
#include <cstdint>
#include <stdexcept>

namespace spk::android {
class OutputCancel {
public:
    OutputCancel() = default;
    explicit OutputCancel(const std::atomic<uint64_t>& generation)
        : generation_(&generation), start_(generation.load(std::memory_order_relaxed)) {}
    bool enabled() const noexcept { return generation_ != nullptr; }
    bool cancelled() const noexcept {
        return generation_ && generation_->load(std::memory_order_relaxed) != start_;
    }
    void check() const {
        if (cancelled()) throw std::runtime_error("render cancelled");
    }
private:
    const std::atomic<uint64_t>* generation_ = nullptr;
    uint64_t start_ = 0;
};
} // namespace spk::android
