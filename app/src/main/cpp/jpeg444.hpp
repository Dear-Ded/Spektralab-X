#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace spk::android {

// A baseline JPEG with 4:4:4 chroma (no subsampling) and optimised Huffman
// tables, from RGBA8 rows (alpha ignored), written to `path`. `icc`, when not
// empty, is embedded as APP2 ICC_PROFILE markers. libjpeg-turbo, linked
// statically (CMakeLists.txt). Bitmap.compress only writes 4:2:0, which halves
// the chroma resolution; 极致渲染 uses this instead (ProcessingQueue).
// False with `error` on any failure; the file is then removed.
bool write_jpeg444(const std::string& path, const uint8_t* rgba, uint32_t width, uint32_t height,
                   size_t stride_bytes, int quality, const std::vector<uint8_t>& icc, std::string& error);

}  // namespace spk::android
