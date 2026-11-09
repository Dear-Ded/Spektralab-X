// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "passthrough.hpp"
#include "output_transform.hpp"
#include "layer2_local.hpp"
#include "rgba8_spool.hpp"
#ifndef SPK_ROWS_RAW
#define SPK_ROWS_RAW 1
#endif
#if SPK_ROWS_RAW
#include "io/raw_decoder.hpp"
#endif
#include <algorithm>
#include <atomic>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <sys/resource.h>

using namespace spk::android;
using Session = std::unique_ptr<spk_session, decltype(&spk_session_release)>;
static void check(bool ok, const std::string& error) { if (!ok) throw std::runtime_error(error); }
static Session open(spk_engine* e, const std::vector<float>& rgb, uint32_t w, uint32_t h) {
    const spk_image image{rgb.data(), w, h, 3};
    Session s(spk_open(e, &image, R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"auto_exposure":false})", nullptr), spk_session_release);
    check(bool(s), spk_last_error(e)); return s;
}
static std::vector<float> pixels(uint32_t w, uint32_t h) {
    std::vector<float> rgb(size_t(w)*h*3);
    for(size_t i=0;i<rgb.size();++i)rgb[i]=float((i*131+i/11)%577)/177.f-.13f;
    return rgb;
}
static std::vector<uint8_t> bytes(const std::string& path) {
    std::ifstream f(path,std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
}
static void memory(const char* stage) {
    struct rusage r{}; getrusage(RUSAGE_SELF,&r);
    std::ifstream status("/proc/self/status"); std::string line;
    while(std::getline(status,line))if(line.rfind("VmRSS:",0)==0||line.rfind("VmHWM:",0)==0)
        std::cout<<stage<<" "<<line<<" peak_kib="<<r.ru_maxrss<<'\n';
    std::cout.flush();
}
static std::vector<float> local_settings(std::vector<float> values) {
    for(int row=0;row<5;++row)values.insert(values.end(),{4,0,0,.25f,.18f,.75f,.86f,1,1});
    values.insert(values.end(),{9157,1,55,17,30,60,35,23891,3});
    for(int kind=0;kind<3;++kind){
        std::array<float,22> mask{};
        mask[0]=float(kind);mask[1]=float(kind==1);mask[2]=.7f;
        mask[3]=.4f;mask[4]=11;mask[6]=-17;
        mask[13]=.21f;mask[14]=.19f;mask[15]=.78f;mask[16]=.83f;
        mask[17]=.33f;mask[18]=.17f;mask[19]=23;mask[20]=.6f;
        mask[21]=kind==2?3.f:0.f;
        values.insert(values.end(),mask.begin(),mask.end());
        if(kind==2)values.insert(values.end(),{.23f,.29f,.2f,.5f,.7f,
            .47f,.53f,.21f,.6f,.8f,.51f,.49f,.11f,.4f,-.6f});
    }
    return values;
}
static void verify(spk_engine* e) {
    std::string error;
    OutputTransform xf;check(xf.fetch(e,"sRGB",true,error),error);
    std::vector<float> controls(kLookSize,0);controls[kVignetteMid]=37;
    controls[kVignette]=-37;controls[kExposure]=-.7f;controls[kSaturation]=23;
    const Layer2 look(controls);
    const Layer2 local_look(local_settings(controls));
    unsigned cases=0,recoveries=0;
    for(const auto size: {std::pair{1u,1u},std::pair{97u,73u},std::pair{65u,33u}}){
        auto rgb=pixels(size.first,size.second);auto s=open(e,rgb,size.first,size.second);
        for(int geometry=0;geometry<4;++geometry){
            spk::view::Request req;check(view_request(s.get(),"full",true,req,error),error);
            req.ungraded_float=true;req.rgba16=false;req.output_channels=3;
            if(geometry){
                check(spk::Json::parse(geometry==1
                    ? R"({"crop":{"x":0.1,"y":0.15,"width":0.7,"height":0.6},"quarterTurns":1,"flipH":true})"
                    : geometry==2 ? R"({"angle":17,"flipV":true})"
                    : R"({"quarterTurns":3,"flipH":true,"flipV":true})",req.geometry,error),error);
                if(geometry==3)req.long_edge=31;
            }
            spk::view::Result full;check(spk::view::render(s.get(),req,full,error),error);
            const Rgba8Rows source(full.float_pixels.data(),full.width,full.width,full.height,&look,&xf);
            const auto local_frame=local_look.frame(full.width,full.height);
            check(local_frame.placed&&local_frame.placed->masks.size()==3&&
                local_frame.placed->masks[2].le.size()==3&&local_frame.placed->dust.n>0,
                "shared frame fixture omitted masks, brush or dust grid");
            const Rgba8Rows local_source(full.float_pixels.data(),full.width,full.width,full.height,&local_look,&xf);
            uint32_t next=0;size_t calls=0;
            req.float_rows=[&](uint32_t w,uint32_t h,uint32_t y,uint32_t rows,const float* strip,std::string&){
                check(w==full.width&&h==full.height&&y==next&&rows&&rows<=32,"row geometry mismatch");
                check(std::memcmp(strip,full.float_pixels.data()+size_t(y)*w*3,size_t(rows)*w*3*sizeof(float))==0,"float row bytes differ");
                std::vector<uint8_t> expected,actual;
                source.pack(y,rows,expected);
                Rgba8Rows(strip,w,w,h,y,rows,&look,&xf).pack(y,rows,actual);
                check(actual==expected,"strip grade/mask/output bytes differ");
                local_source.pack(y,rows,expected);
                Rgba8Rows(strip,w,w,h,y,rows,&local_look,&xf).pack(y,rows,actual);
                check(actual==expected,"default strip local masks/dust bytes differ");
                const auto references=local_frame.placed.use_count();
                const Rgba8Rows reused(strip,w,w,h,y,rows,&local_look,&xf,&local_frame);
                check(local_frame.placed.use_count()==references+1,"strip did not reuse placed mask/dust frame");
                reused.pack(y,rows,actual);
                check(actual==expected,"shared strip local masks/dust bytes differ");
                next+=rows;++calls;return true;
            };
            spk::view::Result streamed;check(spk::view::render(s.get(),req,streamed,error),error);
            check(next==full.height&&calls==(full.height+31)/32&&streamed.float_pixels.empty()&&streamed.pixels.empty(),"stream retained full result");
            req.ungraded_float=false;
            spk::view::Result invalid;error.clear();
            check(!spk::view::render(s.get(),req,invalid,error)&&error=="view: float rows require ungraded float","graded request accepted float rows");
            req.ungraded_float=true;
            const uint32_t strip_count=(full.height+31)/32;
            for(const uint32_t fail_at: {0u,strip_count/2,strip_count-1})for(int failure=0;failure<3;++failure){
                std::atomic<uint64_t> generation{0};const OutputCancel cancel(generation);
                const auto directory=std::filesystem::temp_directory_path()/(
                    "spk-nofilm-rows-"+std::to_string(getpid())+"-"+std::to_string(recoveries));
                check(std::filesystem::create_directory(directory),"cannot create spool test directory");
                const auto fd_count=[](){return std::distance(std::filesystem::directory_iterator("/proc/self/fd"),std::filesystem::directory_iterator());};
                const auto fds=fd_count();
                std::unique_ptr<Rgba8Spool> partial;
                uint32_t consumed=0;
                req.cancelled=[&]() noexcept{return cancel.cancelled();};
                req.float_rows=[&](uint32_t w,uint32_t h,uint32_t y,uint32_t count,const float* data,std::string& e2){
                    if(!partial)partial=Rgba8Spool::create((directory/"output.jpg").string(),w,h);
                    std::vector<uint8_t> scratch;
                    Rgba8Rows(data,w,w,h,y,count,&look,&xf).pack(y,count,scratch);
                    partial->append(scratch);
                    if(consumed++!=fail_at)return true;
                    if(failure==0){e2="sink rejected rows";return false;}
                    if(failure==1)throw std::runtime_error("sink exception");
                    generation.fetch_add(1,std::memory_order_relaxed);return true;
                };
                spk::view::Result failed;error.clear();
                check(!spk::view::render(s.get(),req,failed,error),"row sink failure was accepted");
                check(error==(failure==0?"sink rejected rows":failure==1?"sink exception":"render cancelled"),"row sink error classification");
                check(consumed==fail_at+1&&bool(partial),"failure injection missed requested strip");
                check(std::filesystem::is_empty(directory),"failed export left a named spool or JPEG");
                partial.reset();
                check(fd_count()==fds,"failed export leaked anonymous spool descriptor");
                check(std::filesystem::remove(directory),"cannot clean spool test directory");
                req.float_rows={};req.cancelled={};
                spk::view::Result recovered;check(spk::view::render(s.get(),req,recovered,error),error);
                check(recovered.float_pixels==full.float_pixels,"sink failure changed resident source");
                ++recoveries;
            }
            ++cases;
        }
        spk_result shape{};
        check(render_view_float_rows(s.get(),"full",shape,[](uint32_t,uint32_t,uint32_t,uint32_t,const float*,std::string&){return true;},error),error);
        check(shape.width==size.first&&shape.height==size.second&&!shape.texture&&!shape.rgba16,"adapter result ownership");
    }
    std::cout<<"nofilm rows PASS cases="<<cases<<" byte_equal=float+grade recoveries="<<recoveries
        <<" failures=first+middle+last after_real_spool_append cleanup=anonymous_fd+no_jpeg invalid_graded_sink=rejected shared_frame=linear+radial+brush+dust_byte_equal\n";
}
static void export_file(spk_engine* e,const std::string& mode,const std::string& input,const std::string& output) {
    std::string error;memory("startup");
    uint32_t w=4096,h=3072;
    std::vector<float> rgb;
    if(input=="synthetic")rgb=pixels(w,h);
    else {
#if SPK_ROWS_RAW
        spk::io::DecodedRaw d;check(spk::io::decode_raw_compatible(input,d,error),error);w=d.width;h=d.height;rgb=std::move(d.rgb);
#else
        throw std::runtime_error("this probe was built for synthetic inputs only");
#endif
    }
    memory("decoded");
    auto s=open(e,rgb,w,h);std::vector<float>().swap(rgb);memory("opened");
    OutputTransform xf;check(xf.fetch(e,"sRGB",true,error),error);
    std::unique_ptr<Rgba8Spool> spool;spk_result shape{};
    size_t max_rows_bytes=0;
    if(mode=="rows"){
        check(render_view_float_rows(s.get(),"full",shape,
            [&](uint32_t sw,uint32_t sh,uint32_t y,uint32_t count,const float* data,std::string&){
                if(!spool)spool=Rgba8Spool::create(output,sw,sh);
                max_rows_bytes=std::max(max_rows_bytes,size_t(count)*sw*3*sizeof(float));
                std::vector<uint8_t> scratch;
                Rgba8Rows(data,sw,sw,sh,y,count,nullptr,&xf).pack(y,count,scratch);
                spool->append(scratch);return true;
            },error),error);
    }else{
        std::vector<float> full;check(render_view_float(s.get(),"full",shape,full,error),error);
        memory("full_float");max_rows_bytes=full.size()*sizeof(float);
        spool=Rgba8Spool::create(output,shape.width,shape.height);
        const Rgba8Rows rows(full.data(),shape.width,shape.width,shape.height,nullptr,&xf);
        std::vector<uint8_t> scratch;
        for(uint32_t y=0;y<shape.height;y+=32){rows.pack(y,std::min(32u,shape.height-y),scratch);spool->append(scratch);}
    }
    check(spool&&shape.width==w&&shape.height==h,"export lost dimensions");
    spool->seal();memory("spooled");
    const JpegRowSource rows=[&](uint32_t y,uint32_t count,std::vector<uint8_t>& scratch){return spool->read(y,count,scratch);};
    check(write_jpeg444(output,w,h,98,{},rows,error),error);memory("encoded");
    std::cout<<"mode="<<mode<<" width="<<w<<" height="<<h<<" float_payload_bytes="<<max_rows_bytes<<" jpeg_bytes="<<std::filesystem::file_size(output)<<'\n';
}
int main(int argc,char** argv){
    try{
        if(argc!=3&&argc!=6)return 2;
        if(argc==6)memory("before_engine");
        std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> e(spk_engine_create(argv[1],nullptr),spk_engine_destroy);
        check(bool(e),"engine unavailable");
        if(argc==6)memory("after_engine");
        if(argc==3){verify(e.get());return 0;}
        check(std::string(argv[2])=="memory","unknown test mode");
        check(std::string(argv[3])=="full"||std::string(argv[3])=="rows","unknown export mode");
        export_file(e.get(),argv[3],argv[4],argv[5]);return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
