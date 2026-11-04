#include "jpeg444.hpp"

#include <csetjmp>
#include <cstdio>
#include <memory>

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

void on_error(j_common_ptr cinfo) {
    auto* e = reinterpret_cast<Errors*>(cinfo->err);
    (*cinfo->err->format_message)(cinfo, e->message);
    std::longjmp(e->jump, 1);
}

}  // namespace

bool write_jpeg444(const std::string& path, const uint8_t* rgba, uint32_t width, uint32_t height,
                   size_t stride_bytes, int quality, const std::vector<uint8_t>& icc, std::string& error) {
    if (!rgba || !width || !height || stride_bytes < size_t(width) * 4 || width > 65500 || height > 65500) {
        error = "invalid image for JPEG";
        return false;
    }
    std::FILE* file = std::fopen(path.c_str(), "wb");
    if (!file) { error = "cannot create " + path; return false; }

    jpeg_compress_struct cinfo{};
    Errors errors{};
    cinfo.err = jpeg_std_error(&errors.mgr);
    errors.mgr.error_exit = on_error;
    // Everything setjmp may return to is declared above it (no C++ objects
    // with destructors are created between here and the last libjpeg call).
    if (setjmp(errors.jump)) {
        error = std::string("libjpeg: ") + errors.message;
        jpeg_destroy_compress(&cinfo);
        std::fclose(file);
        std::remove(path.c_str());
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
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = const_cast<JSAMPROW>(rgba + size_t(cinfo.next_scanline) * stride_bytes);
        jpeg_write_scanlines(&cinfo, &row, 1);
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
