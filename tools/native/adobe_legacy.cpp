// Byte comparison driver for old targets, used with baseline and current
// production OutputTransform sources. Link the actual engine or an explicitly
// documented metadata fixture; only the former proves real CAM16 compatibility.
// Co-created with GPT (OpenAI), 2026-11-11. See NOTICE.
#include "output_transform.hpp"
#include "layer2.hpp"
#include <fstream>
#include <memory>
#include <stdexcept>
using namespace spk::android;
int main(int argc,char** argv) {
    if(argc!=3) throw std::runtime_error("legacy RESOURCES OUTPUT");
    std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1],nullptr),&spk_engine_destroy);
    std::ofstream file(argv[2],std::ios::binary);
    constexpr uint32_t w=17,h=65,stride=23;
    std::vector<uint16_t> u16(size_t(stride)*h*4);
    std::vector<float> fp(size_t(stride)*h*3);
    for(size_t i=0;i<u16.size();++i) u16[i]=uint16_t(i*63001+i/7);
    for(size_t i=0;i<fp.size();++i) fp[i]=float(int(i*97%4096)-1024)/2048;
    std::vector<float> values(kLookSize,0.f); values[kExposure]=.35f; values[kVignetteMid]=45;
    const Layer2 grade(values);
    for(const char* target:{"sRGB","Display P3","ProPhoto RGB"}) for(bool exporting:{false,true}) for(bool exact:{false,true}) {
        OutputTransform xf; std::string error;
        if(!xf.fetch(engine.get(),target,exporting,error)) throw std::runtime_error(error);
        xf.set_exact(exact);
        for(bool floating:{false,true}) for(bool grading:{false,true}) {
            const auto* look=grading?&grade:nullptr;
            std::vector<uint16_t> whole;
            if(floating) pack_float_rgba16(fp.data(),stride,w,h,whole,look,xf);
            else pack_rgba16(u16.data(),stride,w,h,whole,look,xf);
            file.write(reinterpret_cast<const char*>(whole.data()),whole.size()*sizeof(uint16_t));
            const Rgba16Rows rows=floating?Rgba16Rows(fp.data(),stride,w,h,look,&xf):Rgba16Rows(u16.data(),stride,w,h,look,&xf);
            for(uint32_t y=0;y<h;y+=7) {
                std::vector<uint16_t> part; rows.pack(y,std::min(7u,h-y),part);
                file.write(reinterpret_cast<const char*>(part.data()),part.size()*sizeof(uint16_t));
            }
        }
    }
}
