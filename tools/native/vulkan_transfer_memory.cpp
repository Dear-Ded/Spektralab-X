// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "gpu/gpu.hpp"
#include "core/json.hpp"
#include "spektrafilm/spk_engine.h"
#include <atomic>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
size_t checks = 0;
void require(bool ok, const char* why) {
    if (!ok) throw std::runtime_error(why);
    ++checks;
}
}
int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("resource directory required");
        std::string error;
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(std::string(argv[1]) + "/vulkan", error));
        require(bool(gpu), error.c_str());
        require(gpu->check_math_mode(error), error.c_str());
        gpu->trim_pool(0);
        const auto empty = gpu->backend_memory_stats();
        require(empty.available && !empty.buffer_allocation_bytes && !empty.upload_staging_allocation_bytes &&
            !empty.read_staging_allocation_bytes, "empty allocation ledger");
        std::vector<float> src(1 << 20), dst(src.size());
        for (size_t i = 0; i < src.size(); ++i) src[i] = float(int(i % 4093) - 2046) / 1024;
        const size_t bytes = src.size() * sizeof(float);
        auto image = gpu->upload_persistent(src.data(), bytes, error);
        auto scratch = gpu->alloc(bytes + 256, error);
        require(bool(image) && bool(scratch), error.c_str());
        require(gpu->read(image.get(), 0, dst.data(), bytes, error), error.c_str());
        require(std::memcmp(src.data(), dst.data(), bytes) == 0, "initial read bytes");
        const auto warm = gpu->backend_memory_stats();
        const auto pool = gpu->pool_stats();
        require(warm.buffer_allocation_bytes >= pool.total_bytes + pool.persistent_bytes, "allocation capacity covers buffers");
        require(warm.upload_staging_allocation_bytes >= bytes && warm.read_staging_allocation_bytes >= bytes,
            "retained staging missing from ledger");
        require(warm.total_bytes() == warm.buffer_allocation_bytes + warm.upload_staging_allocation_bytes +
            warm.read_staging_allocation_bytes + warm.inline_ring_allocation_bytes, "allocation partition");
        require(gpu->trim_transfer_staging() == warm.upload_staging_allocation_bytes + warm.read_staging_allocation_bytes,
            "trim returned wrong allocation capacity");
        const auto trimmed = gpu->backend_memory_stats();
        require(!trimmed.upload_staging_allocation_bytes && !trimmed.read_staging_allocation_bytes &&
            trimmed.buffer_allocation_bytes == warm.buffer_allocation_bytes &&
            trimmed.inline_ring_allocation_bytes == warm.inline_ring_allocation_bytes, "trim changed image or ring allocations");
        const auto still_live = gpu->pool_stats();
        require(still_live.live_bytes == pool.live_bytes && still_live.persistent_bytes == pool.persistent_bytes &&
            still_live.total_bytes == pool.total_bytes, "trim changed logical pool ledger");
        require(gpu->trim_transfer_staging() == 0, "repeated trim");
        require(gpu->read(image.get(), 0, dst.data(), 4096, error), error.c_str());
        const auto small = gpu->backend_memory_stats();
        require(small.read_staging_allocation_bytes >= 4096 && small.read_staging_allocation_bytes < bytes,
            "small read retained old full-sized staging");
        require(std::memcmp(src.data(), dst.data(), 4096) == 0, "read after staging recreation");
        std::atomic<bool> bad{false};
        std::thread observer([&] {
            for (int i = 0; i < 100; ++i) {
                const auto s = gpu->backend_memory_stats();
                if (!s.available || s.buffer_allocation_bytes != warm.buffer_allocation_bytes) bad = true;
            }
        });
        bool transfers = true;
        for (int i = 0; i < 12; ++i) {
            gpu->trim_transfer_staging();
            if (!gpu->read(image.get(), 0, dst.data(), 4096, error)) { transfers = false; break; }
        }
        observer.join();
        require(transfers && !bad, "concurrent transfer/stat snapshots");
        require(std::memcmp(src.data(), dst.data(), 4096) == 0, "concurrent read bytes");
        image = {}; scratch = {};
        gpu->trim_pool(0);
        const auto cleared = gpu->backend_memory_stats();
        require(!cleared.buffer_allocation_bytes && !cleared.upload_staging_allocation_bytes &&
            !cleared.read_staging_allocation_bytes, "pool trim did not clear tracked allocations");
        gpu.reset();
        std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        require(bool(engine), spk_last_error(nullptr));
        const char* report = spk_memory_report(engine.get());
        spk::Json j;
        require(report && spk::Json::parse(report, j, error), "memory report parse");
        const auto& b = j.at("backend_allocations");
        require(b.at("available").as_bool(), "public backend ledger absent");
        require(b.at("total_bytes").as_double() == b.at("buffer_bytes").as_double() +
            b.at("upload_staging_bytes").as_double() + b.at("read_staging_bytes").as_double() +
            b.at("inline_ring_bytes").as_double(), "public allocation partition");
        require(j.at("total_bytes").as_double() == j.at("pool").at("total_bytes").as_double() +
            j.at("persistent").at("bytes").as_double(), "legacy total contract changed");
        std::cout << "VULKAN_TRANSFER_MEMORY_PASS checks=" << checks << " warm_upload=" << warm.upload_staging_allocation_bytes
            << " warm_read=" << warm.read_staging_allocation_bytes << " small_read=" << small.read_staging_allocation_bytes << '\n';
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
