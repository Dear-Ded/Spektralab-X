#pragma once

#include "jpeg444.hpp"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <sys/statvfs.h>
#include <unistd.h>

namespace spk::android {

// Anonymous file in the export directory. Conversion finishes and releases
// its full render result before JPEG's full-image Huffman coefficients exist.
class Rgba8Spool {
public:
    static std::unique_ptr<Rgba8Spool> create(const std::string& near_path, uint32_t w, uint32_t h) {
        if (!w || !h || w > 65500 || h > 65500) throw std::invalid_argument("invalid spool dimensions");
        auto spool = std::unique_ptr<Rgba8Spool>(new Rgba8Spool(w, h));
        const std::string name = near_path + ".rgba-XXXXXX";
        std::vector<char> pattern(name.begin(), name.end());
        pattern.push_back('\0');
        const int fd = mkstemp(pattern.data());
        if (fd < 0) throw std::runtime_error("cannot create JPEG row spool");
        if (unlink(pattern.data()) != 0) {
            close(fd);
            std::remove(pattern.data());
            throw std::runtime_error("cannot unlink JPEG row spool");
        }
        spool->file_ = fdopen(fd, "w+b");
        if (!spool->file_) { close(fd); throw std::runtime_error("cannot open JPEG row spool"); }
        const uint64_t bytes = uint64_t(w) * h * 4;
        int reserved;
        do { reserved = fallocate(fd, 0, 0, off_t(bytes)); } while (reserved != 0 && errno == EINTR);
        if (reserved != 0) {
            const int reason = errno;
            const std::string needed = "JPEG temporary storage requires " + std::to_string(bytes) + " bytes";
            if (reason == ENOSPC || reason == EDQUOT)
                throw std::runtime_error(needed + ": insufficient space or quota");
            if (reason != EOPNOTSUPP && reason != ENOSYS)
                throw std::runtime_error(needed + ": reservation failed (errno " + std::to_string(reason) + ")");
            // A fallback snapshot, not a reservation: other writers can still
            // consume these blocks before append/seal or final JPEG encoding.
            struct statvfs space{};
            if (fstatvfs(fd, &space) != 0 || !space.f_frsize)
                throw std::runtime_error(needed + ": cannot determine available space");
            const uint64_t blocks = bytes / space.f_frsize + (bytes % space.f_frsize != 0);
            if (blocks > space.f_bavail)
                throw std::runtime_error(needed + ": insufficient available space");
        }
        return spool;
    }

    ~Rgba8Spool() { if (file_) std::fclose(file_); }
    Rgba8Spool(const Rgba8Spool&) = delete;
    Rgba8Spool& operator=(const Rgba8Spool&) = delete;

    void append(const std::vector<uint8_t>& rgba) {
        const size_t stride = size_t(w_) * 4;
        const size_t rows = rgba.size() / stride;
        if (ready_ || rgba.empty() || rgba.size() % stride || rows > h_ - written_)
            throw std::invalid_argument("invalid JPEG spool rows");
        if (std::fwrite(rgba.data(), 1, rgba.size(), file_) != rgba.size())
            throw std::runtime_error("JPEG row spool write failed");
        written_ += uint32_t(rows);
    }

    void seal() {
        if (written_ != h_) throw std::runtime_error("incomplete JPEG row spool");
        if (std::fflush(file_) != 0) throw std::runtime_error("JPEG row spool flush failed");
        ready_ = true;
    }

    JpegRowView read(uint32_t y0, uint32_t count, std::vector<uint8_t>& scratch) {
        if (!ready_ || y0 > h_ || count > h_ - y0) throw std::out_of_range("JPEG spool row range");
        const size_t stride = size_t(w_) * 4;
        scratch.resize(stride * count);
        if (fseeko(file_, off_t(size_t(y0) * stride), SEEK_SET) != 0 ||
            std::fread(scratch.data(), 1, scratch.size(), file_) != scratch.size())
            throw std::runtime_error("JPEG row spool read failed");
        return {scratch.data(), stride};
    }

private:
    Rgba8Spool(uint32_t w, uint32_t h) : w_(w), h_(h) {}
    std::FILE* file_ = nullptr;
    uint32_t w_, h_, written_ = 0;
    bool ready_ = false;
};

}  // namespace spk::android
