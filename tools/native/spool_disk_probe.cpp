// Production spool fault injection; never fills a shared filesystem.
#include "rgba8_spool.hpp"

#include <cstring>
#include <filesystem>
#include <iostream>
#include <limits>
#include <sys/stat.h>

namespace {
int reserve_errno = 0, reserve_calls = 0, interrupts = 0, stat_errno = 0;
bool fail_write = false, fail_flush = false, fake_stat = false;
struct statvfs snapshot{};
void check(bool ok, const std::string& error) { if (!ok) throw std::runtime_error(error); }
size_t entries(const std::filesystem::path& dir) {
    return size_t(std::distance(std::filesystem::directory_iterator(dir), std::filesystem::directory_iterator{}));
}
template<class F> std::string failure(F&& action) {
    try { action(); } catch (const std::runtime_error& e) { return e.what(); }
    throw std::runtime_error("expected failure succeeded");
}
void reset() {
    reserve_errno = reserve_calls = interrupts = stat_errno = 0;
    fail_write = fail_flush = fake_stat = false;
    snapshot = {};
}
}
extern "C" int __real_fallocate(int, int, off_t, off_t);
extern "C" int __real_fstatvfs(int, struct statvfs*);
extern "C" size_t __real_fwrite(const void*, size_t, size_t, std::FILE*);
extern "C" int __real_fflush(std::FILE*);
extern "C" int __wrap_fallocate(int fd, int mode, off_t offset, off_t length) {
    ++reserve_calls;
    check(mode == 0 && offset == 0, "unexpected reservation mode/range");
    if (interrupts > 0) { --interrupts; errno = EINTR; return -1; }
    if (reserve_errno) { errno = reserve_errno; return -1; }
    return __real_fallocate(fd, mode, offset, length);
}
extern "C" int __wrap_fstatvfs(int fd, struct statvfs* out) {
    if (stat_errno) { errno = stat_errno; return -1; }
    if (fake_stat) { *out = snapshot; return 0; }
    return __real_fstatvfs(fd, out);
}
extern "C" size_t __wrap_fwrite(const void* p, size_t size, size_t count, std::FILE* file) {
    if (fail_write) { errno = ENOSPC; return 0; }
    return __real_fwrite(p, size, count, file);
}
extern "C" int __wrap_fflush(std::FILE* file) {
    if (fail_flush) { errno = ENOSPC; return EOF; }
    return __real_fflush(file);
}
int main(int argc, char** argv) {
    try {
        check(argc == 2, "fresh output directory required");
        const std::filesystem::path dir(argv[1]);
        std::filesystem::create_directories(dir);
        const size_t before_fds = entries("/proc/self/fd"), before_names = entries(dir);
        const auto clean = [&] {
            check(entries("/proc/self/fd") == before_fds && entries(dir) == before_names, "file/fd leaked");
        };
        constexpr uint32_t w = 73, h = 97;
        constexpr uint64_t required = uint64_t(w) * h * 4;
        const auto create = [&] { return spk::android::Rgba8Spool::create((dir / "result.part").string(), w, h); };
        for (int reason : {ENOSPC, EDQUOT, EIO, EINVAL}) {
            reset(); reserve_errno = reason;
            const auto error = failure([&] { auto spool = create(); });
            check(error.find("temporary storage requires 28324 bytes") != std::string::npos, error);
            check(reserve_calls == 1, "reservation error unexpectedly retried"); clean();
        }
        std::cout << "reserve errors PASS ENOSPC EDQUOT EIO EINVAL explicit_bytes cleanup\n";
        reset(); interrupts = 2;
        {
            auto spool = create(); check(reserve_calls == 3, "EINTR not retried");
            check(entries(dir) == before_names, "spool is not anonymous");
            check(failure([&] { spool->seal(); }) == "incomplete JPEG row spool", "preallocation bypassed row count");
            std::vector<uint8_t> pixels(size_t(w) * h * 4);
            for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = uint8_t(i * 37 + i / 13);
            for (uint32_t y = 0; y < h; y += 32) {
                auto first = pixels.begin() + size_t(y) * w * 4;
                auto last = first + size_t(std::min(32u, h - y)) * w * 4;
                spool->append({first, last});
            }
            spool->seal();
            std::vector<uint8_t> got;
            for (uint32_t y = h; y > 0; --y) {
                const auto row = spool->read(y - 1, 1, got);
                check(row.stride_bytes == w * 4 && got.size() == w * 4 &&
                      std::memcmp(row.rgba, pixels.data() + size_t(y - 1) * w * 4, got.size()) == 0, "reservation changed spool bytes");
            }
        }
        clean(); std::cout << "real reservation PASS EINTR anonymous incomplete_seal byte_parity cleanup\n";
        for (int unsupported : {ENOSYS, EOPNOTSUPP}) {
            for (int mode = 0; mode < 5; ++mode) {
                reset(); reserve_errno = unsupported; fake_stat = true;
                snapshot.f_frsize = 4096; snapshot.f_bavail = 7;
                if (mode == 0) snapshot.f_bavail = 6;
                if (mode == 1) snapshot.f_frsize = 0;
                if (mode == 2) stat_errno = EIO;
                if (mode == 4) snapshot.f_bavail = std::numeric_limits<fsblkcnt_t>::max();
                if (mode < 3) {
                    const auto error = failure([&] { auto spool = create(); });
                    check(error.find("temporary storage requires 28324 bytes") != std::string::npos, error);
                } else { auto spool = create(); }
                clean();
            }
        }
        std::cout << "unsupported PASS ENOSYS EOPNOTSUPP low_space zero_frsize stat_error exact_blocks overflow_safe\n";
        for (bool flush : {false, true}) {
            reset();
            const auto error = failure([&] {
                auto spool = create();
                std::vector<uint8_t> pixels(size_t(w) * h * 4, 42);
                fail_write = !flush; spool->append(pixels);
                fail_flush = flush; spool->seal();
            });
            check(error == (flush ? "JPEG row spool flush failed" : "JPEG row spool write failed"), error);
            reset(); clean();
        }
        std::cout << "later IO errors PASS append flush cleanup\n";
        reset();
        std::string pattern = (dir / "reserve-XXXXXX").string();
        std::vector<char> name(pattern.begin(), pattern.end()); name.push_back('\0');
        const int fd = mkstemp(name.data()); check(fd >= 0, "probe create failed");
        check(unlink(name.data()) == 0, "probe unlink failed");
        const int reserved = fallocate(fd, 0, 0, off_t(required));
        struct stat info{}; const bool stated = fstat(fd, &info) == 0; close(fd);
        check(reserved == 0 && stated && uint64_t(info.st_size) == required && uint64_t(info.st_blocks) * 512 >= required,
              "real reservation did not allocate blocks");
        clean();
        std::cout << "real blocks PASS required=" << required << " allocated=" << info.st_blocks * 512 << " closed\n";
        std::cout << "spool disk probe PASS\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
