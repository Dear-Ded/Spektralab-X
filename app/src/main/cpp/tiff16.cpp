#include "tiff16.hpp"

#include <cstdio>
#include <limits>
#include <memory>

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

}  // namespace

bool write_tiff16(const std::string& path, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error) {
    if (!rgba || !width || !height || row_stride_px < width) {
        error = "invalid image for TIFF export"; return false;
    }
    const uint64_t pixel_bytes = uint64_t(width) * height * 6;
    if (pixel_bytes + icc.size() + 4096 > std::numeric_limits<uint32_t>::max()) {
        error = "image exceeds the classic TIFF 4 GiB limit"; return false;
    }

    const bool with_icc = !icc.empty();
    const uint16_t entries = with_icc ? 15 : 14;
    const uint32_t ifd_offset = 8;
    const uint32_t ifd_bytes = 2 + 12u * entries + 4;
    uint32_t cursor = ifd_offset + ifd_bytes;
    const uint32_t bps_offset = cursor; cursor += 6;
    const uint32_t xres_offset = cursor; cursor += 8;
    const uint32_t yres_offset = cursor; cursor += 8;
    const uint32_t icc_offset = cursor; cursor += uint32_t(icc.size());
    if (cursor & 1) ++cursor;
    const uint32_t data_offset = cursor;

    std::vector<Entry> fields = {
        {256, kLong, 1, width},
        {257, kLong, 1, height},
        {258, kShort, 3, bps_offset},
        {259, kShort, 1, 1},              // no compression
        {262, kShort, 1, 2},              // RGB
        {273, kLong, 1, data_offset},
        {274, kShort, 1, 1},              // top-left
        {277, kShort, 1, 3},
        {278, kLong, 1, height},
        {279, kLong, 1, uint32_t(pixel_bytes)},
        {282, kRational, 1, xres_offset},
        {283, kRational, 1, yres_offset},
        {284, kShort, 1, 1},              // chunky
        {296, kShort, 1, 2},              // inch
    };
    if (with_icc) fields.push_back({34675, kUndefined, uint32_t(icc.size()), icc_offset});

    std::vector<uint8_t> head;
    head.reserve(data_offset);
    head.push_back('I'); head.push_back('I');
    put16(head, 42);
    put32(head, ifd_offset);
    put16(head, entries);
    for (const Entry& e : fields) {
        put16(head, e.tag); put16(head, e.type); put32(head, e.count);
        if (e.type == kShort && e.count == 1) { put16(head, uint16_t(e.value)); put16(head, 0); }
        else put32(head, e.value);
    }
    put32(head, 0);  // no next IFD
    for (int i = 0; i < 3; ++i) put16(head, 16);
    put32(head, 300); put32(head, 1);
    put32(head, 300); put32(head, 1);
    head.insert(head.end(), icc.begin(), icc.end());
    while (head.size() < data_offset) head.push_back(0);

    std::unique_ptr<std::FILE, decltype(&std::fclose)> file(std::fopen(path.c_str(), "wb"), &std::fclose);
    if (!file) { error = "cannot create " + path; return false; }
    if (std::fwrite(head.data(), 1, head.size(), file.get()) != head.size()) {
        error = "write failed: " + path; return false;
    }
    std::vector<uint8_t> row(size_t(width) * 6);
    for (uint32_t y = 0; y < height; ++y) {
        const uint16_t* src = rgba + size_t(y) * row_stride_px * 4;
        uint8_t* dst = row.data();
        for (uint32_t x = 0; x < width; ++x) {
            for (int c = 0; c < 3; ++c) {
                const uint16_t v = src[4 * x + c];
                *dst++ = uint8_t(v); *dst++ = uint8_t(v >> 8);
            }
        }
        if (std::fwrite(row.data(), 1, row.size(), file.get()) != row.size()) {
            error = "write failed: " + path; return false;
        }
    }
    if (std::fflush(file.get()) != 0) { error = "write failed: " + path; return false; }
    return true;
}

}  // namespace spk::android
