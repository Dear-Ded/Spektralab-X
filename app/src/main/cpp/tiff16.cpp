#include "tiff16.hpp"

#include <zlib.h>

#include <algorithm>
#include <cstdio>
#include <limits>
#include <thread>

namespace spk::android {
namespace {

enum : uint16_t { kShort = 3, kLong = 4, kRational = 5, kUndefined = 7 };

struct Entry { uint16_t tag, type; uint32_t count, value; };

void put16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(uint8_t(v)); out.push_back(uint8_t(v >> 8));
}
void put32(std::vector<uint8_t>& out, uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(uint8_t(v >> (8 * i)));
}

// ~1 MB of pixels per strip at 6000 px: small enough to compress in parallel
// with little memory, large enough that zlib's window is not the limit.
constexpr uint32_t kRowsPerStrip = 32;
// Level 4 with the predictor is within a few percent of level 6 on photos,
// at about half the time.
constexpr int kLevel = 4;
constexpr uint64_t kMaxOffset = std::numeric_limits<uint32_t>::max();

// Rows [y0, y1) as little-endian RGB16 through the horizontal predictor
// (tag 317 = 2: each sample minus the same channel's left neighbour, mod
// 2^16), then deflated into one zlib stream (compression 8, Adobe Deflate).
bool deflate_strip(const uint16_t* rgba, uint32_t width, uint32_t y0, uint32_t y1, size_t stride_px,
                   std::vector<uint8_t>& raw, std::vector<uint8_t>& out) {
    raw.resize(size_t(width) * 6 * (y1 - y0));
    uint8_t* dst = raw.data();
    for (uint32_t y = y0; y < y1; ++y) {
        const uint16_t* src = rgba + size_t(y) * stride_px * 4;
        uint16_t prev[3] = {0, 0, 0};
        for (uint32_t x = 0; x < width; ++x) {
            for (int c = 0; c < 3; ++c) {
                const uint16_t v = src[4 * x + c];
                const uint16_t d = uint16_t(v - prev[c]);
                prev[c] = v;
                *dst++ = uint8_t(d); *dst++ = uint8_t(d >> 8);
            }
        }
    }
    uLongf size = compressBound(uLong(raw.size()));
    out.resize(size);
    if (compress2(out.data(), &size, raw.data(), uLong(raw.size()), kLevel) != Z_OK) return false;
    out.resize(size);
    return true;
}

}  // namespace

// Layout: header, the strips in order, then the directory and its arrays.
// The directory goes last because the strips' compressed sizes are known
// only once written; the header's pointer to it is patched at the end. A file
// cut off anywhere therefore has no directory (MediaSaver.checkTiff).
bool write_tiff16(std::FILE* file, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, const TiffRowSource& source, std::string& error) {
    if (!file) { error = "no file to write the TIFF to"; return false; }
    if (!source || !width || !height) {
        error = "invalid image for TIFF export"; return false;
    }
    const uint32_t strips = (height - 1) / kRowsPerStrip + 1;
    std::vector<uint32_t> offsets(strips), counts(strips);

    const uint8_t header[8] = {'I', 'I', 42, 0, 0, 0, 0, 0};   // directory offset patched below
    if (std::fwrite(header, 1, sizeof header, file) != sizeof header) { error = "write failed"; return false; }
    uint64_t pos = sizeof header;

    // A batch of strips compressed at once, one per thread, written in order.
    const uint32_t workers = std::clamp(std::thread::hardware_concurrency(), 1u, 4u);
    std::vector<std::vector<uint8_t>> raw(workers), packed(workers);
    std::vector<std::vector<uint16_t>> converted(workers);
    for (uint32_t first = 0; first < strips; first += workers) {
        const uint32_t n = std::min(workers, strips - first);
        std::vector<char> ok(n, 0);
        auto job = [&](uint32_t k) {
            const uint32_t y0 = (first + k) * kRowsPerStrip;
            const uint32_t count = std::min(kRowsPerStrip, height - y0);
            try {
                const auto view = source(y0, count, converted[k]);
                ok[k] = view.rgba && view.stride_px >= width &&
                        deflate_strip(view.rgba, width, 0, count, view.stride_px, raw[k], packed[k]);
            } catch (...) {
                ok[k] = 0;   // bad_alloc in a thread would otherwise terminate the app
            }
        };
        std::vector<std::thread> threads;
        for (uint32_t k = 1; k < n; ++k) {
            // No thread to be had: that strip is compressed here instead.
            try { threads.emplace_back(job, k); } catch (...) { job(k); }
        }
        job(0);
        for (auto& t : threads) t.join();
        for (uint32_t k = 0; k < n; ++k) {
            if (!ok[k]) { error = "row conversion or deflate failed (out of memory?)"; return false; }
            if (pos + packed[k].size() > kMaxOffset) { error = "image exceeds the classic TIFF 4 GiB limit"; return false; }
            offsets[first + k] = uint32_t(pos);
            counts[first + k] = uint32_t(packed[k].size());
            if (std::fwrite(packed[k].data(), 1, packed[k].size(), file) != packed[k].size()) {
                error = "write failed"; return false;
            }
            pos += packed[k].size();
        }
    }
    if (pos & 1) {   // the directory starts on a word boundary
        if (std::fputc(0, file) == EOF) { error = "write failed"; return false; }
        ++pos;
    }

    const bool with_icc = !icc.empty();
    const uint16_t entries = with_icc ? 16 : 15;
    const uint64_t ifd_offset = pos;
    uint64_t cursor = ifd_offset + 2 + 12u * entries + 4;
    const uint64_t bps_offset = cursor; cursor += 6;
    const uint64_t xres_offset = cursor; cursor += 8;
    const uint64_t yres_offset = cursor; cursor += 8;
    // Always written, even for one strip (whose values are then inline), so
    // the offsets after them do not depend on the strip count.
    const uint64_t offsets_at = cursor; cursor += 4ull * strips;
    const uint64_t counts_at = cursor; cursor += 4ull * strips;
    const uint64_t icc_offset = cursor; cursor += icc.size();
    if (cursor > kMaxOffset) { error = "image exceeds the classic TIFF 4 GiB limit"; return false; }

    std::vector<Entry> fields = {
        {256, kLong, 1, width},
        {257, kLong, 1, height},
        {258, kShort, 3, uint32_t(bps_offset)},
        {259, kShort, 1, 8},              // Adobe Deflate
        {262, kShort, 1, 2},              // RGB
        {273, kLong, strips, strips == 1 ? offsets[0] : uint32_t(offsets_at)},
        {274, kShort, 1, 1},              // top-left
        {277, kShort, 1, 3},
        {278, kLong, 1, kRowsPerStrip},
        {279, kLong, strips, strips == 1 ? counts[0] : uint32_t(counts_at)},
        {282, kRational, 1, uint32_t(xres_offset)},
        {283, kRational, 1, uint32_t(yres_offset)},
        {284, kShort, 1, 1},              // chunky
        {296, kShort, 1, 2},              // inch
        {317, kShort, 1, 2},              // horizontal differencing
    };
    if (with_icc) fields.push_back({34675, kUndefined, uint32_t(icc.size()), uint32_t(icc_offset)});

    std::vector<uint8_t> tail;
    tail.reserve(size_t(cursor - ifd_offset));
    put16(tail, entries);
    for (const Entry& e : fields) {
        put16(tail, e.tag); put16(tail, e.type); put32(tail, e.count);
        if (e.type == kShort && e.count == 1) { put16(tail, uint16_t(e.value)); put16(tail, 0); }
        else put32(tail, e.value);
    }
    put32(tail, 0);  // no next IFD
    for (int i = 0; i < 3; ++i) put16(tail, 16);
    put32(tail, 300); put32(tail, 1);
    put32(tail, 300); put32(tail, 1);
    for (uint32_t v : offsets) put32(tail, v);
    for (uint32_t v : counts) put32(tail, v);
    tail.insert(tail.end(), icc.begin(), icc.end());
    if (std::fwrite(tail.data(), 1, tail.size(), file) != tail.size()) { error = "write failed"; return false; }

    std::vector<uint8_t> pointer;
    put32(pointer, uint32_t(ifd_offset));
    if (std::fseek(file, 4, SEEK_SET) != 0) { error = "cannot seek in the TIFF"; return false; }
    if (std::fwrite(pointer.data(), 1, pointer.size(), file) != pointer.size()) { error = "write failed"; return false; }
    if (std::fflush(file) != 0) { error = "write failed"; return false; }
    return true;
}

bool write_tiff16(const std::string& path, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, const TiffRowSource& source, std::string& error) {
    std::FILE* file = std::fopen(path.c_str(), "wb");
    if (!file) { error = "cannot create " + path; return false; }
    bool ok;
    try { ok = write_tiff16(file, width, height, icc, source, error); }
    catch (...) { std::fclose(file); std::remove(path.c_str()); throw; }
    if (!ok) {
        std::fclose(file); std::remove(path.c_str()); error += ": " + path; return false;
    }
    if (std::fclose(file) != 0) { std::remove(path.c_str()); error = "close failed: " + path; return false; }
    return true;
}

bool write_tiff16(std::FILE* file, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error) {
    if (!rgba || row_stride_px < width) { error = "invalid image for TIFF export"; return false; }
    return write_tiff16(file, width, height, icc,
        [&](uint32_t y0, uint32_t, std::vector<uint16_t>&) {
            return TiffRowView{rgba + size_t(y0) * row_stride_px * 4, row_stride_px};
        }, error);
}

bool write_tiff16(const std::string& path, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error) {
    if (!rgba || row_stride_px < width) { error = "invalid image for TIFF export"; return false; }
    return write_tiff16(path, width, height, icc,
        [&](uint32_t y0, uint32_t, std::vector<uint16_t>&) {
            return TiffRowView{rgba + size_t(y0) * row_stride_px * 4, row_stride_px};
        }, error);
}

}  // namespace spk::android
