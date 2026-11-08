#pragma once

#include <cstdint>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace spk::android {

struct TiffRowView { const uint16_t* rgba = nullptr; size_t stride_px = 0; };
// Concurrent calls must be safe. Return rows starting at y0 as a view, using
// the worker's scratch when conversion is needed; valid until its next call.
using TiffRowSource = std::function<TiffRowView(uint32_t y0, uint32_t rows, std::vector<uint16_t>& scratch)>;

bool write_tiff16(std::FILE* file, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, const TiffRowSource& source, std::string& error);
bool write_tiff16(const std::string& path, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, const TiffRowSource& source, std::string& error);

// Little-endian RGB16 classic TIFF, Deflate with the horizontal predictor in
// 32-row strips, top-left orientation, with `icc` embedded as tag 34675 when
// non-empty. `rgba` is the engine's RGBA16 result; alpha is dropped.
// Overwrites `path`.
bool write_tiff16(const std::string& path, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error);

// The same into an open, empty, seekable `file` (the header is patched at
// the end), flushed but not closed; on failure the caller
// discards what was written (a pending MediaStore entry, ProcessingQueue).
bool write_tiff16(std::FILE* file, const uint16_t* rgba, uint32_t width, uint32_t height,
                  uint32_t row_stride_px, const std::vector<uint8_t>& icc, std::string& error);

}  // namespace spk::android
