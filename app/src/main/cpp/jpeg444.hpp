#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace spk::android {

struct JpegRowView { const uint8_t* rgba = nullptr; size_t stride_bytes = 0; };
// The view remains valid until the next source call. Only the requested rows
// need reside in scratch; coordinates always refer to the complete image.
using JpegRowSource = std::function<JpegRowView(uint32_t y0, uint32_t rows, std::vector<uint8_t>& scratch)>;

bool write_jpeg444(const std::string& path, uint32_t width, uint32_t height, int quality,
                   const std::vector<uint8_t>& icc, const JpegRowSource& source,
                   std::string& error, uint32_t strip_rows = 32);

// A baseline JPEG with 4:4:4 chroma (no subsampling) and optimised Huffman
// tables, from RGBA8 rows (alpha ignored), written to `path`. `icc`, when not
// empty, is embedded as APP2 ICC_PROFILE markers. libjpeg-turbo, linked
// statically (CMakeLists.txt). Bitmap.compress only writes 4:2:0, which halves
// the chroma resolution; 极致渲染 uses this instead (ProcessingQueue).
// False with `error` on any failure; the file is then removed.
bool write_jpeg444(const std::string& path, const uint8_t* rgba, uint32_t width, uint32_t height,
                   size_t stride_bytes, int quality, const std::vector<uint8_t>& icc, std::string& error);

}  // namespace spk::android
