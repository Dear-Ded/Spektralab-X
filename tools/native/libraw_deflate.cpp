// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-11. Synthetic decoder dependency probe.
#include <libraw/libraw.h>
#include <fstream>
#include <iostream>
#include <string>

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    LibRaw raw;
    raw.imgdata.rawparams.options &= ~LIBRAW_RAWOPTIONS_CONVERTFLOAT_TO_INT;
    const int opened = raw.open_file(argv[1]);
    const int unpacked = opened == LIBRAW_SUCCESS ? raw.unpack() : opened;
    const auto& s = raw.imgdata.sizes;
    const auto& d = raw.imgdata.rawdata;
    const void* pixels = nullptr;
    size_t count = size_t(s.raw_width)*s.raw_height, bytes = 0;
    bool floating = false;
    if (d.float_image) { pixels = d.float_image; floating = true; }
    else if (d.float3_image) { pixels = d.float3_image; count *= 3; floating = true; }
    else if (d.float4_image) { pixels = d.float4_image; count *= 4; floating = true; }
    else if (d.raw_image) pixels = d.raw_image;
    else if (d.color3_image) { pixels = d.color3_image; count *= 3; }
    else if (d.color4_image) { pixels = d.color4_image; count *= 4; }
    if (unpacked == LIBRAW_SUCCESS && pixels) {
        bytes = count*(floating ? sizeof(float) : sizeof(unsigned short));
        std::ofstream out(argv[2],std::ios::binary);
        out.write(static_cast<const char*>(pixels),std::streamsize(bytes));
        if (!out) return 2;
    }
    LibRaw processed;
    auto& p = processed.imgdata.params;
    p.output_color=4; p.output_bps=16; p.gamm[0]=p.gamm[1]=1;
    p.no_auto_bright=1; p.adjust_maximum_thr=0; p.use_camera_wb=1;
    int process_status=processed.open_file(argv[1]), memory_status=0;
    if (process_status==0) process_status=processed.unpack();
    if (process_status==0) process_status=processed.dcraw_process();
    libraw_processed_image_t* image=nullptr;
    if (process_status==0) image=processed.dcraw_make_mem_image(&memory_status);
    if (image) {
        std::ofstream out(std::string(argv[2])+".processed",std::ios::binary);
        out.write(reinterpret_cast<const char*>(image->data),image->data_size);
        if (!out) return 2;
        LibRaw::dcraw_clear_mem(image);
    }
    std::cout << "{\"capabilities\":" << LibRaw::capabilities()
              << ",\"open\":" << opened << ",\"unpack\":" << unpacked
              << ",\"float\":" << (floating ? "true" : "false")
              << ",\"width\":" << s.raw_width << ",\"height\":" << s.raw_height
              << ",\"bytes\":" << bytes << ",\"process\":" << process_status
              << ",\"memory\":" << memory_status << "}\n";
}
