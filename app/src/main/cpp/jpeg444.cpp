#include "jpeg444.hpp"

#include <csetjmp>
#include <algorithm>
#include <cstdio>
#include <exception>
#include <memory>
#include <stdexcept>

#include <jpeglib.h>

namespace spk::android {
namespace {

// libjpeg reports errors by calling error_exit, which must not return: jump
// back to write_jpeg444 with the message.
struct Errors {
    jpeg_error_mgr mgr;
    std::jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

struct Writer {
    jpeg_compress_struct cinfo{};
    Errors errors{};
    std::vector<uint8_t> scratch;
};

void on_error(j_common_ptr cinfo) {
    auto* e = reinterpret_cast<Errors*>(cinfo->err);
    (*cinfo->err->format_message)(cinfo, e->message);
    std::longjmp(e->jump, 1);
}

}  // namespace

bool write_jpeg444(const std::string& path, const uint8_t* rgba, uint32_t width, uint32_t height,
                   size_t stride_bytes, int quality, const std::vector<uint8_t>& icc, std::string& error) {
    if (!rgba || stride_bytes < size_t(width) * 4) {
        error = "invalid image for JPEG";
        return false;
    }
    const JpegRowSource source = [&](uint32_t y0, uint32_t, std::vector<uint8_t>&) {
        return JpegRowView{rgba + size_t(y0) * stride_bytes, stride_bytes};
    };
    return write_jpeg444(path, width, height, quality, icc, source, error);
}

bool write_jpeg444(const std::string& path, uint32_t width, uint32_t height, int quality,
                   const std::vector<uint8_t>& icc, const JpegRowSource& source,
                   std::string& error, uint32_t strip_rows) {
    if (!source || !width || !height || !strip_rows || width > 65500 || height > 65500) {
        error = "invalid image for JPEG";
        return false;
    }
    const auto state = std::make_unique<Writer>();
    std::FILE* file = std::fopen(path.c_str(), "wb");
    if (!file) { error = "cannot create " + path; return false; }

    auto& cinfo = state->cinfo;
    auto& errors = state->errors;
    JpegRowView view;
    uint32_t end_row = 0;
    cinfo.err = jpeg_std_error(&errors.mgr);
    errors.mgr.error_exit = on_error;
    // Mutable compressor/scratch state lives on the heap: longjmp must not
    // make an altered automatic C++ owner indeterminate.
    if (setjmp(errors.jump)) {
        jpeg_destroy_compress(&cinfo);
        std::fclose(file);
        std::remove(path.c_str());
        error = std::string("libjpeg: ") + errors.message;
        return false;
    }
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, file);
    cinfo.image_width = width;
    cinfo.image_height = height;
    // libjpeg-turbo reads Android's RGBA_8888 rows as they are.
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    cinfo.optimize_coding = TRUE;
    for (int c = 0; c < cinfo.num_components; ++c) {
        cinfo.comp_info[c].h_samp_factor = 1;
        cinfo.comp_info[c].v_samp_factor = 1;
    }
    jpeg_start_compress(&cinfo, TRUE);
    if (!icc.empty()) jpeg_write_icc_profile(&cinfo, icc.data(), unsigned(icc.size()));
    try {
        while (cinfo.next_scanline < cinfo.image_height) {
            const uint32_t start = cinfo.next_scanline;
            const uint32_t count = std::min(strip_rows, height - start);
            // The callback has returned before libjpeg can longjmp. No C++
            // conversion stack or temporary owner spans a libjpeg call.
            view = source(start, count, state->scratch);
            if (!view.rgba || view.stride_bytes < size_t(width) * 4)
                throw std::runtime_error("invalid JPEG row source");
            end_row = start + count;
            while (cinfo.next_scanline < end_row) {
                JSAMPROW row = const_cast<JSAMPROW>(view.rgba + size_t(cinfo.next_scanline - start) * view.stride_bytes);
                jpeg_write_scanlines(&cinfo, &row, 1);
            }
        }
    } catch (const std::exception& e) {
        jpeg_destroy_compress(&cinfo);
        std::fclose(file);
        std::remove(path.c_str());
        error = std::string("JPEG rows: ") + e.what();
        return false;
    } catch (...) {
        jpeg_destroy_compress(&cinfo);
        std::fclose(file);
        std::remove(path.c_str());
        error = "JPEG rows: unknown failure";
        return false;
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    const bool flushed = std::fflush(file) == 0;
    if (std::fclose(file) != 0 || !flushed) {
        std::remove(path.c_str());
        error = "write failed: " + path;
        return false;
    }
    return true;
}

}  // namespace spk::android
