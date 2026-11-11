// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Failure injection for publication and cancellation, using synthetic bytes only.
// Co-created with GPT (OpenAI), 2026-11-11.
#include "pipeline/bounded_point_chain.hpp"
#include <bit>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <iostream>

namespace {
enum class Fault { None, Interrupted, Short, Zero, NoSpace, Paused };
std::atomic<Fault> write_fault{Fault::None}, read_fault{Fault::None};
std::atomic<int> write_calls{0}, read_calls{0}, reserve_interrupts{0};
std::atomic<bool> reserve_fail{false};
std::atomic<int> fail_thread_after{0};
std::atomic<unsigned> thread_failures{0};
std::mutex pause_mutex;
std::condition_variable pause_cv;
bool paused = false, resume_write = false;
unsigned cases = 0;
void check(bool ok, const char* message) {
    ++cases;
    if (!ok) throw std::runtime_error(message);
}
template<class F> void rejects(F&& f, const char* message) {
    bool rejected = false;
    try { f(); } catch (const std::exception&) { rejected = true; }
    check(rejected, message);
}
size_t descriptors() {
    size_t count = 0;
    for (const auto& entry : std::filesystem::directory_iterator("/proc/self/fd")) { (void)entry; ++count; }
    return count;
}
std::vector<float> pixels(uint32_t w, uint32_t h) {
    std::vector<float> result(size_t(w) * h * 3);
    const uint32_t patterns[] = {0, 0x80000000u, 1, 0x3e3c6a7fu, 0xc0800000u, 0x41800000u, 0x7fc12345u};
    for (size_t i = 0; i < result.size(); ++i) result[i] = std::bit_cast<float>(patterns[(i * 7 + i / 3) % 7]);
    return result;
}
void equal(const spk::ExternalPlane& plane, const std::vector<float>& expected) {
    std::vector<float> actual(expected.size());
    plane.read_rows(0, plane.height(), actual.data());
    check(std::memcmp(expected.data(), actual.data(), actual.size() * sizeof(float)) == 0, "float plane bytes changed");
}
}

extern "C" ssize_t __real_pwrite(int, const void*, size_t, off_t);
extern "C" ssize_t __real_pread(int, void*, size_t, off_t);
extern "C" int __real_posix_fallocate(int, off_t, off_t);
extern "C" ssize_t __wrap_pwrite(int fd, const void* data, size_t count, off_t offset) {
    ++write_calls;
    const auto fault = write_fault.exchange(Fault::None);
    if (fault == Fault::Interrupted) { errno = EINTR; return -1; }
    if (fault == Fault::Zero) return 0;
    if (fault == Fault::NoSpace) { errno = ENOSPC; return -1; }
    if (fault == Fault::Paused) {
        std::unique_lock<std::mutex> lock(pause_mutex);
        paused = true; pause_cv.notify_all();
        if (!pause_cv.wait_for(lock, std::chrono::seconds(5), [] { return resume_write; })) { errno = EIO; return -1; }
    }
    return __real_pwrite(fd, data, fault == Fault::Short ? std::min(count, size_t(7)) : count, offset);
}
extern "C" ssize_t __wrap_pread(int fd, void* data, size_t count, off_t offset) {
    ++read_calls;
    const auto fault = read_fault.exchange(Fault::None);
    if (fault == Fault::Interrupted) { errno = EINTR; return -1; }
    if (fault == Fault::Zero) return 0;
    return __real_pread(fd, data, fault == Fault::Short ? std::min(count, size_t(7)) : count, offset);
}
// glibc fortification can redirect pread here after sanitizer optimization.
extern "C" ssize_t __wrap___pread_chk(int fd, void* data, size_t count, off_t offset, size_t capacity) {
    if (count > capacity) std::abort();
    return __wrap_pread(fd, data, count, offset);
}
extern "C" int __wrap_posix_fallocate(int fd, off_t offset, off_t length) {
    if (reserve_interrupts.exchange(0)) return EINTR;
    if (reserve_fail.load()) return ENOSPC;
    return __real_posix_fallocate(fd, offset, length);
}
// Interpose the DSO call made by std::thread as well as direct calls.
extern "C" int pthread_create(pthread_t* thread, const pthread_attr_t* attributes,
                              void* (*entry)(void*), void* argument) noexcept {
    if (fail_thread_after.load() > 0 && fail_thread_after.fetch_sub(1) == 1) {
        ++thread_failures; return EAGAIN;
    }
    using Create = int (*)(pthread_t*, const pthread_attr_t*, void* (*)(void*), void*);
    static const auto create = reinterpret_cast<Create>(dlsym(RTLD_NEXT, "pthread_create"));
    return create ? create(thread, attributes, entry, argument) : ENOSYS;
}

int main(int argc, char** argv) {
    try {
        check(argc == 2, "scratch directory required");
        const std::string dir = argv[1];
        std::filesystem::create_directories(dir);
        const size_t fd_before = descriptors();
        {
            spk::ExternalPlane missing(dir, 3, 7, 3);
            float row[9]{};
            rejects([&] { missing.read_rows(0, 1, row); }, "unwritten preallocated pixels were readable");
#ifndef SPK_PLANE_BEFORE_FIX
            rejects([&] { missing.seal(); }, "incomplete plane was sealed");
            missing.write_rows(6, 1, row);
            rejects([&] { missing.seal(); }, "one written row bypassed full coverage");
            rejects([&] { missing.write_rows(6, 1, row); }, "overlapping row accepted");
            for (uint32_t y = 0; y < 6; ++y) missing.write_rows(y, 1, row);
            missing.seal(); missing.seal();
            check(missing.ready(), "complete plane not ready");
            rejects([&] { missing.write_rows(0, 1, row); }, "sealed plane changed");
            rejects([&] { missing.producer(); }, "sealed plane acquired producer");
            check(missing.ready(), "invalid request unpublished sealed data");
        }
        for (auto fault : {Fault::None, Fault::Interrupted, Fault::Short}) {
            spk::ExternalPlane plane(dir, 19, 17, 3);
            const auto source = pixels(19, 17);
            write_fault = fault;
            plane.write_rows(0, 17, source.data());
            plane.seal();
            read_fault = fault;
            equal(plane, source);
            read_fault = Fault::Zero;
            rejects([&] { equal(plane, source); }, "short/EOF read accepted");
            equal(plane, source);
        }
        for (auto fault : {Fault::Zero, Fault::NoSpace}) {
            spk::ExternalPlane plane(dir, 3, 1, 3);
            const auto source = pixels(3, 1);
            write_fault = fault;
            rejects([&] { plane.write_rows(0, 1, source.data()); }, "failed write accepted");
            rejects([&] { plane.seal(); }, "failed write published");
            rejects([&] { plane.write_rows(0, 1, source.data()); }, "failed plane reused");
            check(!plane.ready(), "failed plane ready");
        }
        reserve_interrupts = 1;
        { spk::ExternalPlane plane(dir, 3, 1, 3); check(!plane.ready(), "reservation completed a plane"); }
        reserve_fail = true;
        rejects([&] { spk::ExternalPlane plane(dir, 3, 1, 3); }, "disk reservation failure accepted");
        reserve_fail = false;
        rejects([&] { spk::ExternalPlane plane(dir + "/absent", 3, 1, 3); }, "missing directory accepted");
        for (unsigned threads : {1u, 4u, 8u}) {
            constexpr uint32_t w = 37, h = 113;
            spk::ExternalPlane plane(dir, w, h, 3);
            const auto source = pixels(w, h);
            std::atomic<uint32_t> next{0};
            std::atomic<bool> failed{false};
            std::vector<std::thread> workers;
            for (unsigned t = 0; t < threads; ++t) workers.emplace_back([&] {
                try {
                    for (;;) {
                        const uint32_t i = next.fetch_add(1);
                        if (i >= h) break;
                        const uint32_t y = (i * 29) % h;
                        plane.write_rows(y, 1, source.data() + size_t(y) * w * 3);
                    }
                } catch (...) { failed = true; }
            });
            for (auto& worker : workers) worker.join();
            check(!failed, "parallel disjoint writes failed");
            plane.seal(); equal(plane, source);
        }
        {
            spk::ExternalPlane plane(dir, 3, 1, 3);
            const auto source = pixels(3, 1);
            std::atomic<bool> write_failed{false};
            write_fault = Fault::Paused;
            std::thread writer([&] { try { plane.write_rows(0, 1, source.data()); } catch (...) { write_failed = true; } });
            bool entered;
            {
                std::unique_lock<std::mutex> lock(pause_mutex);
                entered = pause_cv.wait_for(lock, std::chrono::seconds(5), [] { return paused; });
            }
            // Always release/join the writer before throwing a test failure.
            bool seal_rejected = false, duplicate_rejected = false;
            try { plane.seal(); } catch (...) { seal_rejected = true; }
            try { plane.write_rows(0, 1, source.data()); } catch (...) { duplicate_rejected = true; }
            { std::lock_guard<std::mutex> lock(pause_mutex); resume_write = true; }
            pause_cv.notify_all(); writer.join();
            check(entered && seal_rejected && duplicate_rejected && !write_failed, "inflight write/commit race");
            plane.seal(); equal(plane, source);
        }
        {
            const auto source = pixels(3, 1);
            spk::ExternalPlane abandoned(dir, 3, 1, 3);
            {
                auto producer = abandoned.producer();
                rejects([&] { abandoned.producer(); }, "second producer accepted");
                rejects([&] { abandoned.write_rows(0, 1, source.data()); }, "external write entered owned output");
                producer.write_rows(0, 1, source.data());
                rejects([&] { abandoned.seal(); }, "external seal entered owned output");
            }
            rejects([&] { abandoned.seal(); }, "abandoned complete output published");
            check(!abandoned.ready(), "abandoned output ready");
            spk::ExternalPlane output(dir, 3, 1, 3);
            auto writer = output.producer();
            auto moved = std::move(writer);
            moved.write_rows(0, 1, source.data()); moved.seal(); equal(output, source);
        }
        {
            constexpr uint32_t w = 300001;
            const auto source = pixels(w, 1);
            spk::ExternalPlane cancelled(dir, w, 1, 3);
            write_calls = 0;
            rejects([&] { cancelled.write_rows(0, 1, source.data(), [] { return write_calls.load() > 0; }); }, "mid-write cancel ignored");
            check(write_calls == 1, "write was not bounded to one I/O chunk before cancellation");
            rejects([&] { cancelled.seal(); }, "cancelled partial output published");
            spk::ExternalPlane input(dir, w, 1, 3);
            input.write_rows(0, 1, source.data()); input.seal();
            std::vector<float> scratch(source.size());
            read_calls = 0;
            rejects([&] { input.read_rows(0, 1, scratch.data(), [] { return read_calls.load() > 0; }); }, "mid-read cancel ignored");
            check(read_calls == 1 && input.ready(), "cancel damaged immutable input");
            equal(input, source);
        }
        {
            constexpr uint32_t w = 17, h = 23;
            const auto source = pixels(w, h);
            spk::ExternalPlane input(dir, w, h, 3);
            input.write_rows(0, h, source.data()); input.seal();
            auto kernel = [](const float* src, uint32_t width, uint32_t, uint32_t offset, uint32_t rows,
                             float* dst, std::string&) {
                std::memcpy(dst, src + size_t(offset) * width * 3, size_t(rows) * width * 3 * sizeof(float));
                return true;
            };
            for (unsigned workers : {1u, 4u}) for (uint32_t halo : {0u, 2u, 100u}) {
                spk::ExternalPlane output(dir, w, h, 3);
                std::string error;
                spk::PointChainStats stats;
                check(spk::run_external_bands(input, output, 3, halo, spk::BandOrder::Shuffled, workers,
                    &stats, error, {}, kernel), "shared executor copy failed");
                check(stats.bands == 8 && output.ready(), "shared executor did not publish");
                equal(output, source);
            }
            for (int launch : {1, 2, 3}) {
                spk::ExternalPlane output(dir, w, h, 3);
                std::string error;
                fail_thread_after = launch;
                check(spk::run_external_bands(input, output, 3, 2, spk::BandOrder::Reverse, 4,
                    nullptr, error, {}, kernel), "worker creation fallback failed");
                check(thread_failures == unsigned(launch) && fail_thread_after == 0, "worker creation failure not injected");
                equal(output, source);
            }
            for (int mode : {0, 1, 2, 3}) {
                spk::ExternalPlane output(dir, w, h, 3);
                std::atomic<int> calls{0};
                spk::BoundedCancel cancel = mode == 0 ? spk::BoundedCancel([] { return true; }) : spk::BoundedCancel{};
                auto failing = [&](const float* src, uint32_t width, uint32_t window, uint32_t offset,
                                   uint32_t rows, float* dst, std::string& error) {
                    if (++calls == 2) {
                        if (mode == 1) { error = "injected kernel failure"; return false; }
                        if (mode == 2) throw std::runtime_error("injected kernel exception");
                        if (mode == 3) write_fault = Fault::NoSpace;
                    }
                    return kernel(src, width, window, offset, rows, dst, error);
                };
                std::string error;
                spk::PointChainStats stats{99,99,99};
                check(!spk::run_external_bands(input, output, 3, 2, spk::BandOrder::Forward, 4,
                    &stats, error, cancel, failing), "failed execution returned success");
                check(!output.ready() && stats.bands == 0 && !error.empty(), "failed execution published data or statistics");
                rejects([&] { output.seal(); }, "failed execution was later sealed");
                equal(input, source);
            }
#endif
        }
        check(descriptors() == fd_before, "external plane leaked file descriptor");
        check(std::filesystem::is_empty(dir), "external plane left a named file");
        std::cout << "PASS external plane publication/failure contracts: " << cases << " checks; synthetic bytes; descriptor cleanup\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n'; return 1;
    }
}
