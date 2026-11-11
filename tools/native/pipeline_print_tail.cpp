// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-11. Source-only synthetic regression.
#include "pipeline/pipeline.hpp"
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <set>
#include <stdexcept>
#include <unistd.h>

namespace {
unsigned checks = 0, renders = 0;
size_t compared = 0;
std::string error;
spk::Progress* active_progress = nullptr;
unsigned reads = 0, writes = 0, fail_read = 0, fail_write = 0, cancel_read = 0;
void check(bool ok, const std::string& why) {
    ++checks;
    if (!ok) throw std::runtime_error(why);
}
void env(const char* key, const char* value) {
    check((value ? setenv(key,value,1) : unsetenv(key)) == 0, "cannot set test environment");
}
void faults() { reads=writes=fail_read=fail_write=cancel_read=0; }
void exact(const std::vector<float>& a, const std::vector<float>& b, const std::string& label) {
    check(a.size()==b.size() && std::memcmp(a.data(),b.data(),a.size()*sizeof(float))==0,label);
    compared += a.size();
}
std::vector<float> read(spk::gpu::Gpu* gpu, const spk::Image& image) {
    std::vector<float> data(image.elements());
    check(gpu->read(image.buf.get(),0,data.data(),image.bytes(),error),error);
    return data;
}

// Forward all production math. Faults are injected only at observable GPU API
// boundaries while the real Pipeline reports its bounded-tail stage.
class Proxy final : public spk::gpu::Gpu {
    using Buffer=spk::gpu::Buffer;
    using Ref=spk::gpu::BufferRef;
    spk::gpu::Gpu* real_;
    bool tail() const { return active_progress && active_progress->stage=="scanning.bounded_print_tail"; }
public:
    enum class Fault { None, Dispatch, CancelDispatch, Copy } fault=Fault::None;
    unsigned tail_dispatches=0, tail_copies=0;
    unsigned tail_trims=0;
    uint64_t spill_before=0, spill_after=0;
    uint64_t window_trim_freed=0;
    std::set<size_t> window_shapes;
    explicit Proxy(spk::gpu::Gpu* real): real_(real) {}
    std::string device_name() const override { return real_->device_name(); }
    bool check_math_mode(std::string& e) override { return real_->check_math_mode(e); }
    void begin_frame() override { real_->begin_frame(); }
    void end_frame() override { real_->end_frame(); }
    Ref alloc(size_t n,std::string& e) override { return real_->alloc(n,e); }
    Ref alloc_bounded(size_t n,std::string& e) override { return real_->alloc_bounded(n,e); }
    Ref alloc_zeroed(size_t n,std::string& e) override { return real_->alloc_zeroed(n,e); }
    Ref upload(const void* p,size_t n,std::string& e) override { return real_->upload(p,n,e); }
    Ref upload_f32(const double* p,size_t n,std::string& e) override { return real_->upload_f32(p,n,e); }
    Ref upload_u32(const uint32_t* p,size_t n,std::string& e) override { return real_->upload_u32(p,n,e); }
    Ref alloc_persistent(size_t n,std::string& e) override { return real_->alloc_persistent(n,e); }
    Ref upload_persistent(const void* p,size_t n,std::string& e) override { return real_->upload_persistent(p,n,e); }
    Ref upload_persistent_f32(const double* p,size_t n,std::string& e) override { return real_->upload_persistent_f32(p,n,e); }
    Ref upload_persistent_u32(const uint32_t* p,size_t n,std::string& e) override { return real_->upload_persistent_u32(p,n,e); }
    Ref alloc_file_backed(size_t n,std::string& e) override { return real_->alloc_file_backed(n,e); }
    Ref upload_file_backed(const void* p,size_t n,std::string& e) override { return real_->upload_file_backed(p,n,e); }
    Ref borrow(void* p,size_t n,std::string& e) override { return real_->borrow(p,n,e); }
    void retain(Buffer* b) override { real_->retain(b); }
    void release(Buffer* b) override { real_->release(b); }
    void* contents(Buffer* b) override { return real_->contents(b); }
    size_t size_bytes(Buffer* b) const override { return real_->size_bytes(b); }
    bool read(Buffer* s,size_t o,void* p,size_t n,std::string& e) override { return real_->read(s,o,p,n,e); }
    bool copy(Buffer* d,size_t o,Buffer* s,size_t so,size_t n,std::string& e) override {
        if (tail() && ++tail_copies==2 && fault==Fault::Copy) { e="injected final tail copy failure"; return false; }
        return real_->copy(d,o,s,so,n,e);
    }
    bool dispatch(const char* k,const std::vector<spk::gpu::Arg>& a,size_t n,std::string& e) override {
        if (tail() && !std::strcmp(k,"spk_matmul3")) window_shapes.insert(real_->size_bytes(a[0].buffer));
        const bool injecting=tail() && ++tail_dispatches==2;
        if (injecting && fault==Fault::Dispatch) { e="injected tail dispatch failure"; return false; }
        const bool ok=real_->dispatch(k,a,n,e);
        if (injecting && fault==Fault::CancelDispatch) active_progress->cancelled=true;
        return ok;
    }
    bool flush(std::string& e) override { return real_->flush(e); }
    void* texture(Buffer* b,uint32_t w,uint32_t h,uint32_t r,std::string& e) override { return real_->texture(b,w,h,r,e); }
    void release_texture(void* t) override { real_->release_texture(t); }
    uint32_t texture_row_alignment_px() const override { return real_->texture_row_alignment_px(); }
    uint32_t max_texture_dimension_2d() const override { return real_->max_texture_dimension_2d(); }
    spk::gpu::PoolStats pool_stats() const override { return real_->pool_stats(); }
    spk::gpu::BackendMemoryStats backend_memory_stats() const override { return real_->backend_memory_stats(); }
    uint64_t trim_transfer_staging() override { return real_->trim_transfer_staging(); }
    size_t trim_pool(size_t n) override {
        const auto before=real_->backend_memory_stats();
        const size_t freed=real_->trim_pool(n);
        if (tail()) {
            ++tail_trims;
            if (tail_trims==1) {
                spill_before=before.buffer_allocation_bytes;
                spill_after=real_->backend_memory_stats().buffer_allocation_bytes;
            }
            else window_trim_freed+=freed;
        }
        return freed;
    }
    bool take_critical_pressure() override { return real_->take_critical_pressure(); }
};

std::vector<float> print(spk::Pipeline& pipeline, Proxy& gpu, const spk::Image& negative, const char* mode) {
    env("SPK_BOUNDED_PRINT_TAIL",mode);
    spk::Progress progress;
    progress.detailed=true;
    active_progress=&progress;
    gpu.tail_dispatches=gpu.tail_copies=gpu.tail_trims=0;
    gpu.window_shapes.clear(); gpu.window_trim_freed=0;
    spk::Image out;
    const bool ok=pipeline.run_print(negative,out,&progress,error);
    active_progress=nullptr;
    check(ok,error);
    check(progress.done && !pipeline.progress_,"successful tail left progress incomplete");
    const bool wanted=mode && !std::strcmp(mode,"1");
    check((progress.node_ms.count("scanning.bounded_print_tail")!=0)==wanted,"production tail selection ignored");
    if (wanted) {
        check(gpu.tail_dispatches>0 && gpu.tail_copies>0,"bounded tail did not run actual GPU work");
        check(gpu.tail_trims==gpu.tail_copies+2,"tail did not trim at every window and both crossings");
        check(gpu.spill_before>gpu.spill_after,"spilled XYZ and idle prefix allocations were retained");
        if (!std::strcmp(std::getenv("SPK_BOUNDED_PRINT_TAIL_ROWS"),"7"))
            check(gpu.window_shapes.size()>=3 && gpu.window_trim_freed>0,
                  "edge/centre/last window capacities were not retired: shapes="+std::to_string(gpu.window_shapes.size())+
                  " freed="+std::to_string(gpu.window_trim_freed));
        const auto memory=gpu.backend_memory_stats();
        check(memory.available && !memory.upload_staging_allocation_bytes && !memory.read_staging_allocation_bytes,
              "bounded tail retained transfer staging after completion");
    }
    ++renders;
    return read(&gpu,out);
}
void failed(spk::Pipeline& pipeline, Proxy& gpu, const spk::Image& negative,
            const spk::Image& sentinel, const char* label, bool cancel=false, bool striped=false,
            const char* selection="1") {
    env("SPK_BOUNDED_PRINT_TAIL",selection);
    spk::Progress progress;
    progress.done=true; progress.detailed=true;
    active_progress=&progress;
    gpu.tail_dispatches=gpu.tail_copies=0;
    spk::Image out=sentinel;
    const bool ok=striped ? pipeline.run_print_striped(negative,out,&progress,error)
                          : pipeline.run_print(negative,out,&progress,error);
    active_progress=nullptr;
    check(!ok,std::string(label)+" was not rejected");
    check(!progress.done && !pipeline.progress_ && !pipeline.mask_on_ && !pipeline.mask_coef_,
          std::string(label)+" leaked progress/mask completion state");
    check(out.buf.get()==sentinel.buf.get() && out.w==sentinel.w && out.h==sentinel.h,
          std::string(label)+" published incomplete output");
    if (cancel) check(error=="render cancelled",std::string(label)+" lost canonical cancellation status: "+error);
    std::cout<<"rejected="<<label<<" error="<<error<<'\n';
}
}

extern "C" ssize_t __real_pread(int,void*,size_t,off_t);
extern "C" ssize_t __real___pread_chk(int,void*,size_t,off_t,size_t);
extern "C" ssize_t __real_pwrite(int,const void*,size_t,off_t);
extern "C" ssize_t __wrap_pread(int fd,void* data,size_t n,off_t off) {
    if (++reads==fail_read) { errno=EIO; return -1; }
    const ssize_t result=__real_pread(fd,data,n,off);
    if (reads==cancel_read && active_progress) active_progress->cancelled=true;
    return result;
}
// Fortified optimized builds may call this entry instead of pread.
extern "C" ssize_t __wrap___pread_chk(int fd,void* data,size_t n,off_t off,size_t capacity) {
    if (++reads==fail_read) { errno=EIO; return -1; }
    const ssize_t result=__real___pread_chk(fd,data,n,off,capacity);
    if (reads==cancel_read && active_progress) active_progress->cancelled=true;
    return result;
}
extern "C" ssize_t __wrap_pwrite(int fd,const void* data,size_t n,off_t off) {
    if (++writes==fail_write) { errno=EIO; return -1; }
    return __real_pwrite(fd,data,n,off);
}

int main(int argc,char** argv) {
    try {
        check(argc==4,"usage: pipeline_print_tail SHADERS RESOURCES SCRATCH");
        env("SPK_VULKAN_FUSION","off");
        env("SPK_BOUNDED_PRINT_TAIL_DIR",argv[3]);
        env("SPK_BOUNDED_PRINT_TAIL_ROWS","7");
        env("SPK_BOUNDED_PRINT_TAIL_HOST_BYTES","65536");
        env("SPK_BOUNDED_PRINT_TAIL_GPU_BYTES","65536");
        std::unique_ptr<spk::gpu::Gpu> real(spk::gpu::Gpu::create_vulkan(argv[1],error));
        check(bool(real),error);
        std::string math;
        check(real->check_math_mode(math),math);
        Proxy gpu(real.get());
        spk::Blob blob;
        spk::Colour colour;
        check(blob.open(std::string(argv[2])+"/spektrafilm_constants.bin",error)&&colour.init(blob,error),error);
        std::ifstream file(std::string(argv[2])+"/neutral_print_filters.json");
        const std::string text{std::istreambuf_iterator<char>(file),{}};
        spk::Json filters;
        check(spk::Json::parse(text,filters,error),error);
        spk::Params params;
        check(spk::init_params(argv[2],"kodak_portra_400","kodak_portra_endura",filters,params,error),error);
        params.film_render.grain.active=false;
        params.print_render.glare.active=false; // independent runs must share identical stochastic input
        params.camera.auto_exposure=false;
        params.io.input_cctf_decoding=false;
        spk::SetupCache cache;
        spk::Image source;
        source.w=67; source.h=50; source.c=3;
        std::vector<float> data(source.elements());
        for(size_t i=0;i<data.size();++i) data[i]=.0001f+float((i*37+i/17)%4093)/1700.f;
        source.buf=gpu.upload(data.data(),source.bytes(),error);
        check(bool(source.buf),error);
        const std::array<const char*,6> spaces={"sRGB","Display P3","ProPhoto RGB","Adobe RGB (1998)",
                                               "ITU-R BT.709","ITU-R BT.2020"};
        for(const char* space:spaces) for(bool cctf:{false,true}) for(unsigned tier:{1u,2u}) for(bool gamut:{false,true}) {
            params.io.output_color_space=space; params.io.output_cctf_encoding=cctf;
            params.io.output_gamut_compress.algorithm=gamut ? "cam16ucs" : "off";
            spk::Pipeline pipeline(&gpu,&colour,&blob,&cache);
            check(pipeline.build(params,error),error);
            pipeline.set_source_shape(source.w,source.h,source.w*tier,source.h*tier);
            spk::Image negative;
            env("SPK_BOUNDED_PRINT_TAIL",nullptr);
            check(pipeline.run_film(source,negative,nullptr,error),error);
            const auto reference=print(pipeline,gpu,negative,nullptr);
            exact(reference,print(pipeline,gpu,negative,"0"),"explicit off differs");
            for(const char* rows:{"1","7","64"}) {
                env("SPK_BOUNDED_PRINT_TAIL_ROWS",rows);
                exact(reference,print(pipeline,gpu,negative,"1"),"actual film/print tail float bytes differ");
            }
            std::cout<<"film_print_memcmp space="<<space<<" cctf="<<cctf<<" tier="<<tier<<" gamut="<<gamut<<" true\n";
        }
        params.io.output_color_space="ProPhoto RGB"; params.io.output_cctf_encoding=true;
        params.io.output_gamut_compress.algorithm="cam16ucs";
        spk::Pipeline pipeline(&gpu,&colour,&blob,&cache);
        check(pipeline.build(params,error),error);
        pipeline.set_source_shape(source.w,source.h,source.w,source.h);
        spk::Image negative;
        check(pipeline.run_film(source,negative,nullptr,error),error);
        env("SPK_BOUNDED_PRINT_TAIL_ROWS","7");
        const auto reference=print(pipeline,gpu,negative,"0");
        auto retry=[&]{ faults(); gpu.fault=Proxy::Fault::None; exact(reference,print(pipeline,gpu,negative,"1"),"retry differs from fresh reference"); };
        for(unsigned phase:{1u,9u}) {
            faults(); fail_write=phase; failed(pipeline,gpu,negative,source,"external pwrite"); retry();
            faults(); fail_read=phase; failed(pipeline,gpu,negative,source,"external pread"); retry();
            faults(); cancel_read=phase; failed(pipeline,gpu,negative,source,"external cancellation",true); retry();
        }
        for(auto fault:{Proxy::Fault::Dispatch,Proxy::Fault::CancelDispatch,Proxy::Fault::Copy}) {
            gpu.fault=fault; failed(pipeline,gpu,negative,source,"GPU failure/cancellation",fault==Proxy::Fault::CancelDispatch); retry();
        }
        env("SPK_BOUNDED_PRINT_TAIL_DIR","/not/a/real/spk-tail-directory");
        failed(pipeline,gpu,negative,source,"temporary create failure"); env("SPK_BOUNDED_PRINT_TAIL_DIR",argv[3]); retry();
        for(const char* key:{"SPK_BOUNDED_PRINT_TAIL_ROWS","SPK_BOUNDED_PRINT_TAIL_HOST_BYTES","SPK_BOUNDED_PRINT_TAIL_GPU_BYTES"}) {
            const std::string saved=std::getenv(key);
            for(const char* bad:{"0","-1","+1","1x","18446744073709551616"}) {
                env(key,bad); failed(pipeline,gpu,negative,source,"invalid numeric opt-in");
            }
            env(key,saved.c_str()); retry();
        }
        faults(); env("SPK_BOUNDED_PRINT_TAIL_HOST_BYTES","1"); failed(pipeline,gpu,negative,source,"host admission");
        check(!reads && !writes,"host admission happened after external pixel IO");
        env("SPK_BOUNDED_PRINT_TAIL_HOST_BYTES","65536"); retry();
        for(const char* bad:{"","on","true","1x"}) {
            failed(pipeline,gpu,negative,source,"invalid opt-in selector",false,false,bad); retry();
        }
#ifndef SPK_TAIL_BASELINE
        for(unsigned config=0;config<8;++config) {
            auto saved=pipeline.params_;
            switch(config) {
                case 0: pipeline.params_.io.digital_intermediate=true; break;
                case 1: pipeline.params_.io.scan_film=true; break;
                case 2: pipeline.params_.film_render.overscan.active=true; break;
                case 3: pipeline.params_.print_render.edr_enabled=true; break;
                case 4: pipeline.params_.scanner.lens_blur=.1; break;
                case 5: pipeline.params_.scanner.unsharp_mask[0]=8; break;
                case 6: pipeline.params_.io.output_gamut_compress.algorithm="unsupported"; break;
                case 7: env("SPK_VULKAN_FUSION","on"); break;
            }
            // Validate the production boundary before changing an image's node graph.
            spk::Image out=source;
            check(!pipeline.print_tail_bounded(source,out,error),"unsupported tail configuration accepted");
            check(out.buf.get()==source.buf.get(),"unsupported direct tail changed output");
            pipeline.params_=std::move(saved); env("SPK_VULKAN_FUSION","off"); retry();
        }
#endif
        failed(pipeline,gpu,negative,source,"striped entry",false,true); retry();
        std::cout<<"PASS production print tail checks="<<checks<<" renders="<<renders<<" float_values="<<compared
                 <<" spill_allocation_before="<<gpu.spill_before<<" spill_allocation_after="<<gpu.spill_after
                 <<" window_shapes="<<gpu.window_shapes.size()<<" window_trim_freed="<<gpu.window_trim_freed
                 <<" gpu="<<gpu.device_name()<<" math="<<math<<'\n';
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<'\n'; return 1; }
}
