// Co-created with GPT (OpenAI), 2026-11-11. See NOTICE.
#include "output_transform.hpp"
#include "layer2.hpp"
#include "tiff16.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>

using namespace spk::android;
static void check(bool ok, const std::string& why) { if (!ok) throw std::runtime_error(why); }
static std::vector<uint8_t> read(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    check(bool(f), path);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}
int main(int argc, char** argv) {
    check(argc == 4, "usage: adobe_tiff RESOURCES ICC OUTPUT_DIRECTORY");
    std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), &spk_engine_destroy);
    check(bool(engine), "create engine");
    OutputTransform xf; std::string error;
    check(xf.fetch(engine.get(), "Adobe RGB (1998)", true, error), error);
    check(!xf.exporting(), "Adobe export must select explicit clipping, not CAM16");
    char* metadata=nullptr; const float* cmax=nullptr; uint32_t n=0;
    check(spk_output_transform(engine.get(),"ProPhoto RGB","Adobe RGB (1998)","{\"algorithm\":\"off\"}",&metadata,&cmax,&n)==SPK_OK,"read target metadata");
    // Preserve the original CAT02 engine metadata for the negative control.
    // Adobe's file-local PCS matrix is validated independently from TIFF bytes.
    std::ofstream(std::string(argv[3])+"/engine_metadata.json")<<metadata;
    spk_string_free(metadata);
    const auto icc = read(argv[2]);
    constexpr uint32_t w = 17, h = 65, stride = w + 3;
    const std::array<std::array<float, 3>, 17> cases{{
        {0,0,0}, {1.f/65535,1.f/65535,1.f/65535}, {.001f,.001f,.001f},
        {.03125f,.03125f,.03125f}, {.18f,.18f,.18f}, {.5f,.5f,.5f}, {1,1,1},
        {1,0,0}, {0,1,0}, {0,0,1}, {1,1,0}, {1,0,1}, {0,1,1},
        {-.1f,.2f,1.2f}, {.2f,.8f,.1f}, {.7f,.1f,.5f}, {2,-1,.5f}}};
    std::vector<uint16_t> u16(size_t(stride)*h*4, 65535);
    std::vector<float> fp(size_t(stride)*h*3);
    for (uint32_t y=0;y<h;++y) for (uint32_t x=0;x<w;++x) for (int k=0;k<3;++k) {
        const auto v=cases[(x+y)%w][k];
        fp[(size_t(y)*stride+x)*3+k]=v;
        u16[(size_t(y)*stride+x)*4+k]=uint16_t(std::floor(std::clamp(v,0.f,1.f)*65535.f+.5f));
    }
    // Observe the unquantized conversion too: integer packing must not hide NaNs.
    for (auto c: cases) {
        xf.apply(c.data());
        for(float v:c) check(std::isfinite(v) && v>=0 && v<=1, "Adobe conversion nonfinite/out of bounds");
    }
    std::vector<float> values(kLookSize,0.f);
    values[kExposure]=.35f; values[kContrast]=12; values[kSaturation]=25;
    values[kVignette]=-20; values[kVignetteMid]=45;
    const Layer2 grade(values);
    size_t count=0;
    for(bool floating:{false,true}) for(bool grading:{false,true}) {
        const auto* look=grading?&grade:nullptr;
        const std::string base=std::string(argv[3])+"/"+(floating?"float":"u16")+(grading?"_look":"_plain");
        const Rgba16Rows rows=floating?Rgba16Rows(fp.data(),stride,w,h,look,&xf):Rgba16Rows(u16.data(),stride,w,h,look,&xf);
        std::vector<uint16_t> whole;
        if(floating) pack_float_rgba16(fp.data(),stride,w,h,whole,look,xf);
        else pack_rgba16(u16.data(),stride,w,h,whole,look,xf);
        for(uint32_t step:{1u,7u,32u,65u}) for(uint32_t y=0;y<h;y+=step) {
            std::vector<uint16_t> part; rows.pack(y,std::min(step,h-y),part);
            check(std::equal(part.begin(),part.end(),whole.begin()+size_t(y)*w*4),"row/full sample mismatch");
        }
        check(write_tiff16(base+".tif",w,h,icc,[&](uint32_t y,uint32_t n,std::vector<uint16_t>& out){
            rows.pack(y,n,out); return TiffRowView{out.data(),w}; },error),error);
        check(write_tiff16(base+"_whole.tif",whole.data(),w,h,w,icc,error),error);
        check(read(base+".tif")==read(base+"_whole.tif"),"row/full TIFF bytes mismatch");
        // Record the grade boundary, independently of target conversion. Python
        // checks the actual TIFF samples against a double matrix/power oracle.
        std::ofstream samples(base+".csv"); samples<<"r,g,b\n";
        const auto frame=look?look->frame(w,h):Layer2::Frame{};
        for(uint32_t y=0;y<h;++y) for(uint32_t x=0;x<w;++x) {
            float c[3]; uint16_t q[3];
            for(int k=0;k<3;++k) c[k]=floating?fp[(size_t(y)*stride+x)*3+k]:u16[(size_t(y)*stride+x)*4+k]/65535.f;
            if(look) look->apply(c[0],c[1],c[2],(x+.5f)/w,(y+.5f)/h,frame,floating);
            for(int k=0;k<3;++k) q[k]=uint16_t(std::floor(std::clamp(c[k],0.f,1.f)*65535.f+.5f));
            samples<<q[0]<<','<<q[1]<<','<<q[2]<<'\n';
        }
        ++count;
    }
    std::cout<<"PASS Adobe finite conversion, "<<count<<" float/u16 and grade cases; four strip heights; identical full/row TIFF bytes\n";
}
