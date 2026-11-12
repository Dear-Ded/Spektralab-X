// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Actual Pipeline stages, frozen defaults and explicit-tail checks at real IIR calls.
// -fno-access-control observes existing stage functions without production hooks.
#include "pipeline/pipeline.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
using Pixels=std::vector<float>;
using Gpu=spk::gpu::Gpu;
using Buffer=spk::gpu::Buffer;
using Ref=spk::gpu::BufferRef;
std::string error;
void check(bool ok,const std::string& why) { if(!ok) throw std::runtime_error(why.empty()?error:why); }
void mode(const char* value) {
    check((value?setenv("SPK_IIR_BOUNDARY",value,1):unsetenv("SPK_IIR_BOUNDARY"))==0,"environment");
}
Pixels read(Gpu* gpu,const spk::Image& image) {
    Pixels out(image.elements()); check(gpu->read(image.buf.get(),0,out.data(),image.bytes(),error),error);
    for(float v:out) check(std::isfinite(v),"nonfinite pipeline output");
    return out;
}
bool same(const Pixels& a,const Pixels& b) {
    return a.size()==b.size() && std::memcmp(a.data(),b.data(),a.size()*sizeof(float))==0;
}
double max_diff(const Pixels& a,const Pixels& b) {
    check(a.size()==b.size(),"shape changed"); double out=0;
    for(size_t i=0;i<a.size();++i) out=std::max(out,std::abs(double(a[i])-b[i]));
    return out;
}
double sigma_for(const double b[4]) {
    double lo=3,hi=256;
    for(unsigned i=0;i<52;++i) { double mid=(lo+hi)/2,probe[4]; spk::yvv_coeffs(mid,probe); if(probe[3]<b[3])lo=mid;else hi=mid; }
    return (lo+hi)/2;
}
Pixels explicit_tail(const Pixels& input,const double b[4],double sigma,unsigned multiple) {
    const long double k=b[0]/(1.0L-b[1]-b[2]-b[3]);
    const size_t pad=size_t(std::ceil(multiple*sigma))+128;
    Pixels forward(input.size()+pad);
    std::array<long double,3> s{input.front()*k,input.front()*k,input.front()*k};
    const auto step=[&](long double x) {
        const long double y=b[0]*x+b[1]*s[0]+b[2]*s[1]+b[3]*s[2];
        s={y,s[0],s[1]}; return float(y);
    };
    for(size_t i=0;i<forward.size();++i) forward[i]=step(i<input.size()?input[i]:input.back());
    const long double settled=float(input.back()*k)*k; s={settled,settled,settled};
    for(size_t i=forward.size();i-->0;) forward[i]=step(forward[i]);
    forward.resize(input.size()); return forward;
}

class Observe final:public Gpu {
    Gpu* real_;
public:
    explicit Observe(Gpu* real):real_(real){}
    bool validate=false;
    std::string scene,selection;
    spk::Progress* progress=nullptr;
    std::ofstream* csv=nullptr;
    unsigned corrected_calls=0,legacy_calls=0;
    size_t checked_values=0;
    double worst=0,relative_worst=0,convergence=0,max_sigma=0;
    std::string device_name()const override{return real_->device_name();}
    bool check_math_mode(std::string& e)override{return real_->check_math_mode(e);}
    void begin_frame()override{real_->begin_frame();}
    void end_frame()override{real_->end_frame();}
    Ref alloc(size_t n,std::string& e)override{return real_->alloc(n,e);}
    Ref alloc_bounded(size_t n,std::string& e)override{return real_->alloc_bounded(n,e);}
    Ref alloc_zeroed(size_t n,std::string& e)override{return real_->alloc_zeroed(n,e);}
    Ref upload(const void* p,size_t n,std::string& e)override{return real_->upload(p,n,e);}
    Ref upload_f32(const double* p,size_t n,std::string& e)override{return real_->upload_f32(p,n,e);}
    Ref upload_u32(const uint32_t* p,size_t n,std::string& e)override{return real_->upload_u32(p,n,e);}
    Ref alloc_persistent(size_t n,std::string& e)override{return real_->alloc_persistent(n,e);}
    Ref upload_persistent(const void* p,size_t n,std::string& e)override{return real_->upload_persistent(p,n,e);}
    Ref upload_persistent_f32(const double* p,size_t n,std::string& e)override{return real_->upload_persistent_f32(p,n,e);}
    Ref upload_persistent_u32(const uint32_t* p,size_t n,std::string& e)override{return real_->upload_persistent_u32(p,n,e);}
    Ref alloc_file_backed(size_t n,std::string& e)override{return real_->alloc_file_backed(n,e);}
    Ref upload_file_backed(const void* p,size_t n,std::string& e)override{return real_->upload_file_backed(p,n,e);}
    Ref borrow(void* p,size_t n,std::string& e)override{return real_->borrow(p,n,e);}
    void retain(Buffer* b)override{real_->retain(b);}
    void release(Buffer* b)override{real_->release(b);}
    void* contents(Buffer* b)override{return real_->contents(b);}
    size_t size_bytes(Buffer* b)const override{return real_->size_bytes(b);}
    bool read(Buffer* b,size_t o,void* p,size_t n,std::string& e)override{return real_->read(b,o,p,n,e);}
    bool copy(Buffer* d,size_t o,Buffer* s,size_t so,size_t n,std::string& e)override{return real_->copy(d,o,s,so,n,e);}
    bool flush(std::string& e)override{return real_->flush(e);}
    void* texture(Buffer* b,uint32_t w,uint32_t h,uint32_t r,std::string& e)override{return real_->texture(b,w,h,r,e);}
    void release_texture(void* t)override{real_->release_texture(t);}
    uint32_t texture_row_alignment_px()const override{return real_->texture_row_alignment_px();}
    uint32_t max_texture_dimension_2d()const override{return real_->max_texture_dimension_2d();}
    spk::gpu::PoolStats pool_stats()const override{return real_->pool_stats();}
    spk::gpu::BackendMemoryStats backend_memory_stats()const override{return real_->backend_memory_stats();}
    size_t trim_pool(size_t n)override{return real_->trim_pool(n);}
    bool take_critical_pressure()override{return real_->take_critical_pressure();}
    bool dispatch(const char* kernel,const std::vector<spk::gpu::Arg>& a,size_t n,std::string& e)override {
        const bool corrected=std::strcmp(kernel,"spk_iir_boundary_df_acc")==0;
        if(std::strcmp(kernel,"spk_iir_df_acc")==0)++legacy_calls;
        if(!corrected)return real_->dispatch(kernel,a,n,e);
        ++corrected_calls;
        if(!validate)return real_->dispatch(kernel,a,n,e);
        check(a.size()==7 && a[5].bytes && a[5].size==24,"IIR dispatch contract changed");
        const auto* meta=static_cast<const uint32_t*>(a[5].bytes);
        const uint32_t h=meta[0],w=meta[1],axis=meta[3];
        Pixels input(size_t(h)*w*3),acc(input.size()),actual(input.size());
        float coef[90],weights[3]; uint32_t active[3];
        check(real_->read(a[0].buffer,0,input.data(),input.size()*4,e),e);
        check(real_->read(a[1].buffer,0,coef,sizeof coef,e),e);
        check(real_->read(a[2].buffer,0,active,sizeof active,e),e);
        check(real_->read(a[4].buffer,0,weights,sizeof weights,e),e);
        if(meta[2])check(real_->read(a[3].buffer,0,acc.data(),acc.size()*4,e),e);
        check(real_->dispatch(kernel,a,n,e),e);
        check(real_->read(a[6].buffer,0,actual.data(),actual.size()*4,e),e);
        const uint32_t length=axis==0?h:w,stride=axis==0?3*w:3;
        const uint32_t end=std::min(meta[5],(axis==0?w:h)*3);
        for(uint32_t c=0;c<3;++c) {
            double b[4]; for(unsigned j=0;j<4;++j)b[j]=double(coef[8*c+2*j])+coef[8*c+2*j+1];
            const double sigma=active[c]?sigma_for(b):0;
            max_sigma=std::max(max_sigma,sigma);
            double err=0,conv=0,scale=1; size_t values=0;
            for(uint32_t t=meta[4];t<end;++t) {
                if(t%3!=c)continue;
                const uint32_t line=t/3,base=(axis==0?line*3:line*w*3)+c;
                Pixels row(length); for(uint32_t i=0;i<length;++i)row[i]=input[base+i*stride];
                auto expected=active[c]?explicit_tail(row,b,sigma,64):row;
                // Each dispatch/channel verifies the reference tail on its first line.
                if(active[c] && !values) conv=max_diff(expected,explicit_tail(row,b,sigma,128));
                for(uint32_t i=0;i<length;++i) {
                    const auto ix=base+i*stride;
                    scale=std::max(scale,std::abs(double(row[i])));
                    if(meta[2]) {
                        scale=std::max(scale,std::abs(double(acc[ix]))+std::abs(double(weights[c])*row[i]));
                        expected[i]=acc[ix]+weights[c]*expected[i];
                    }
                    check(std::isfinite(actual[ix]) && std::isfinite(expected[i]),"nonfinite real-call oracle");
                    err=std::max(err,std::abs(double(actual[ix])-expected[i])); ++values;
                }
            }
            check(err<=1e-5*scale,"real pipeline IIR disagrees with explicit tail: "+scene+"/"+(progress?progress->stage:"")+" error="+std::to_string(err));
            check(conv<=1e-7*scale,"real-call reference did not converge");
            worst=std::max(worst,err); convergence=std::max(convergence,conv); checked_values+=values;
            relative_worst=std::max(relative_worst,err/scale);
            if(csv)*csv<<scene<<','<<selection<<','<<(progress?progress->stage:"")<<','<<axis<<','<<c<<','<<sigma<<','<<values<<','<<err<<','<<conv<<','<<scale<<'\n';
        }
        return true;
    }
};

struct Frame {std::string stage; uint32_t w,h; Pixels values;};
using Trace=std::vector<Frame>;
void snapshot(Trace& trace,Gpu* gpu,const spk::Image& image,const std::string& stage) {
    trace.push_back({stage,image.w,image.h,read(gpu,image)});
}
Trace render(Observe& gpu,const spk::Colour& colour,const spk::Blob& blob,spk::SetupCache& cache,
             const spk::Params& params,const spk::Image& source,unsigned full_factor,bool staged) {
    spk::Pipeline p(&gpu,&colour,&blob,&cache); check(p.build(params,error),error);
    p.set_source_shape(source.w,source.h,source.w*full_factor,source.h*full_factor);
    // A fresh Pipeline already has this seed. Pin explicitly for diagnostic clarity.
    p.rng_state_=0x243F6A8885A308D3ull;
    spk::Progress progress; progress.detailed=true; gpu.progress=&progress;
    Trace trace;
    if(!staged) {
        spk::Image negative,out;
        check(p.run_film(source,negative,&progress,error),error);
        snapshot(trace,&gpu,negative,"negative");
        check(p.run_print(negative,out,&progress,error),error);
        snapshot(trace,&gpu,out,"output");
        check(progress.done && !p.progress_,"public pipeline completion contract");
    } else {
        p.progress_=&progress; spk::Pipeline::Chain chain;
        check(p.film_prefix(source,spk::FrameShape{source.h,source.w},chain.cur,error),error);
        snapshot(trace,&gpu,chain.cur,"prefix");
        for(size_t i=0;i<p.kFilmStageCount;++i) {
            spk::Pipeline::Chain next;
            check((p.*p.kFilmStages[i].run)(std::move(chain),next,error),error); chain=std::move(next);
            snapshot(trace,&gpu,chain.cur,p.kFilmStages[i].name);
            if(chain.log_e_film.buf)snapshot(trace,&gpu,chain.log_e_film,"film_log_e");
        }
        check(p.print_prefix(chain.cur,false,error) && p.prepare_contrast_mask(chain.cur,error),error);
        for(size_t i=0;i<p.kPrintStageCount;++i) {
            spk::Pipeline::Chain next;
            check((p.*p.kPrintStages[i].run)(std::move(chain),next,error),error); chain=std::move(next);
            snapshot(trace,&gpu,chain.cur,p.kPrintStages[i].name);
        }
        p.release_contrast_mask(); p.progress_=nullptr;
    }
    gpu.progress=nullptr; return trace;
}
void compare_public(const Trace& trace,const Trace& ordinary) {
    const auto negative=std::find_if(trace.begin(),trace.end(),[](const Frame& f){return f.stage=="film_grain";});
    check(negative!=trace.end() && same(negative->values,ordinary[0].values),"stage walk differs from actual run_film");
    check(same(trace.back().values,ordinary[1].values),"stage walk differs from actual run_print");
}
void difference_csv(std::ofstream& csv,const std::string& scene,const Trace& old,const Trace& corrected) {
    check(old.size()==corrected.size(),"stage count changed");
    for(size_t k=0;k<old.size();++k) {
        const auto& a=old[k];const auto& b=corrected[k];
        check(a.stage==b.stage && a.w==b.w && a.h==b.h,"stage layout changed");
        double max=0,edge=0,interior=0,square=0; size_t changed=0;
        for(size_t i=0;i<a.values.size();++i) {
            double d=std::abs(double(a.values[i])-b.values[i]); max=std::max(max,d); square+=d*d;
            const size_t x=(i/3)%a.w,y=(i/3)/a.w;
            if(x<8 || y<8 || x+8>=a.w || y+8>=a.h)edge=std::max(edge,d);else interior=std::max(interior,d);
            changed+=std::memcmp(&a.values[i],&b.values[i],4)!=0;
        }
        csv<<scene<<','<<a.stage<<','<<a.w<<','<<a.h<<','<<a.values.size()<<','<<changed<<','<<max<<','<<std::sqrt(square/a.values.size())<<','<<edge<<','<<interior<<'\n';
    }
}
Pixels input_image(unsigned w,unsigned h) {
    Pixels p(size_t(w)*h*3);
    for(unsigned y=0;y<h;++y)for(unsigned x=0;x<w;++x)for(unsigned c=0;c<3;++c) {
        float v=0.0001f+0.4f*float(x)/float(w-1)+0.2f*float(y)/float(h-1);
        if(x<4 || x+4>=w)v+=c==0?3.0f:0.7f;
        if(y<3 || y+3>=h)v+=c==2?1.5f:0.2f;
        if((int(x)-int(w/2))*(int(x)-int(w/2))+(int(y)-int(h/2))*(int(y)-int(h/2))<25)v+=4;
        p[(size_t(y)*w+x)*3+c]=v;
    }
    return p;
}
} // namespace

int main(int argc,char** argv)try {
    check(argc==4 || (argc==5 && std::strcmp(argv[4],"--legacy-only")==0),"usage: iir_pipeline SHADERS RESOURCES OUTPUT [--legacy-only]");
    const bool legacy_only=argc==5; const std::filesystem::path dir=argv[3];std::filesystem::create_directories(dir);
    std::unique_ptr<Gpu> real(Gpu::create_vulkan(argv[1],error));check(bool(real),error);
    Observe gpu(real.get());std::string math;check(gpu.check_math_mode(math),math);
    std::cerr<<"device="<<gpu.device_name()<<" math="<<math<<'\n';
    unsetenv("SPK_VULKAN_FUSION");unsetenv("SPK_BOUNDED_PRINT_TAIL");
    spk::Blob blob;spk::Colour colour;spk::SetupCache cache;
    check(blob.open(std::string(argv[2])+"/spektrafilm_constants.bin",error) && colour.init(blob,error),error);
    std::ifstream file(std::string(argv[2])+"/neutral_print_filters.json");
    const std::string text{std::istreambuf_iterator<char>(file),{}};spk::Json filters;check(spk::Json::parse(text,filters,error),error);
    std::ofstream frozen(dir/"legacy.f32",std::ios::binary),changed(dir/"corrected.f32",std::ios::binary);
    std::ofstream stages(dir/"stages.csv"),calls(dir/"iir-calls.csv"),configuration(dir/"configuration.csv");
    stages.precision(17);calls.precision(17);gpu.csv=&calls;
    stages<<"scene,stage,w,h,values,changed,max_abs,rmse,edge8_max,interior_max\n";
    calls<<"scene,selection,node,axis,channel,sigma,values,oracle_error,convergence,scale\n";
    configuration<<"scene,stock,w,h,full_factor,film_mm,back_reflection,grain,glare,scan_film,DI,output_space,cctf\n";
    const std::array<const char*,10> names{"spatial_off","portra_35mm","portra_4mm","cinestill_35mm","cinestill_4mm","sublayer_grain_glare","simple_grain_glare","preview_ratio","scan_negative","digital_intermediate"};
    unsigned completed=0,stage_comparisons=0;
    for(unsigned scene=0;scene<names.size();++scene) {
        gpu.scene=names[scene];spk::Params params;
        check(spk::init_params(argv[2],scene==3||scene==4?"cinestill_800t":"kodak_portra_400","kodak_portra_endura",filters,params,error),error);
        params.camera.auto_exposure=false;params.io.input_cctf_decoding=false;
        params.settings.grain_sampler="exact";params.film_render.grain.active=false;params.print_render.glare.active=false;
        const unsigned w=scene==1||scene==3?193:97,h=65;
        const unsigned full_factor=scene==7?4:1;
        if(scene!=1 && scene!=3)params.camera.film_format_mm=4;
        if(scene==0) {params.film_render.halation.active=false;params.film_render.dir_couplers.active=false;params.scanner.lens_blur=0;params.scanner.unsharp_mask[1]=0;}
        if(scene==2 || scene==7) {params.camera.lens_blur_um=160;params.scanner.lens_blur=3.25;params.scanner.unsharp_mask[0]=5;}
        if(scene==5 || scene==6) {
            params.film_render.grain.active=true;params.film_render.grain.blur=3.5;
            params.film_render.grain.sublayers_active=scene==5;
            params.print_render.glare.active=true;params.print_render.glare.blur=8;
        }
        if(scene==8)params.io.scan_film=true;
        if(scene==9)params.io.digital_intermediate=true;
        configuration<<names[scene]<<','<<params.film_stock<<','<<w<<','<<h<<','<<full_factor<<','
            <<params.camera.film_format_mm<<','<<params.film_render.halation.back_reflection<<','
            <<params.film_render.grain.active<<','<<params.print_render.glare.active<<','
            <<params.io.scan_film<<','<<params.io.digital_intermediate<<','<<params.io.output_color_space<<','<<params.io.output_cctf_encoding<<'\n';
        spk::Image source;source.w=w;source.h=h;source.c=3;const auto pixels=input_image(w,h);
        source.buf=gpu.upload(pixels.data(),source.bytes(),error);check(bool(source.buf),error);
        mode(nullptr);gpu.selection="legacy";gpu.validate=false;
        const auto old=render(gpu,colour,blob,cache,params,source,full_factor,true);
        compare_public(old,render(gpu,colour,blob,cache,params,source,full_factor,false));
        for(const auto& frame:old)frozen.write(reinterpret_cast<const char*>(frame.values.data()),frame.values.size()*4);
        if(!legacy_only) {
            mode("triggs-v1");gpu.selection="triggs-v1";gpu.validate=true;
            const unsigned before=gpu.corrected_calls;
            const auto corrected=render(gpu,colour,blob,cache,params,source,full_factor,true);
            if(scene==0) {check(before==gpu.corrected_calls,"control unexpectedly used IIR");for(size_t i=0;i<old.size();++i)check(same(old[i].values,corrected[i].values),"no-IIR control changed");}
            else check(gpu.corrected_calls>before,"scenario did not exercise corrected IIR");
            gpu.validate=false;compare_public(corrected,render(gpu,colour,blob,cache,params,source,full_factor,false));
            difference_csv(stages,names[scene],old,corrected);stage_comparisons+=old.size();
            for(const auto& frame:corrected)changed.write(reinterpret_cast<const char*>(frame.values.data()),frame.values.size()*4);
            check(same(old[0].values,corrected[0].values),"IIR correction changed prefix");
        }
        ++completed;std::cerr<<"completed="<<names[scene]<<'\n';
    }
    unsigned range_rejections=0;
    if(!legacy_only) {
        spk::Params p;check(spk::init_params(argv[2],"cinestill_800t","kodak_portra_endura",filters,p,error),error);
        check(p.film_render.halation.back_reflection,"range fixture requires the actual no-AH preset");
        p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;
        p.film_render.grain.active=false;p.print_render.glare.active=false;
        for(unsigned w:{6000u,8000u}) {
            p.film_render.halation.halation_amount=w==6000?2:1;
            spk::Image source;source.w=w;source.h=2;source.c=3;const auto pixels=input_image(w,2);
            source.buf=gpu.upload(pixels.data(),source.bytes(),error);check(bool(source.buf),error);
            mode("triggs-v1");gpu.validate=false;spk::Pipeline pipeline(&gpu,&colour,&blob,&cache);
            check(pipeline.build(p,error),error);pipeline.set_source_shape(w,2,w,2);
            spk::Progress progress;progress.done=true;spk::Image out=source;
            error.clear();const unsigned legacy_before=gpu.legacy_calls;
            check(!pipeline.run_film(source,out,&progress,error),"high-radius full-width fixture was accepted");
            check(error.find("[3, 256]")!=std::string::npos,"wrong full-width rejection: "+error);
            check(out.buf.get()==source.buf.get() && out.w==source.w && out.h==source.h,"failed film published output");
            check(!progress.done && !pipeline.progress_,"failed film retained completion state");
            check(legacy_before==gpu.legacy_calls,"experiment silently fell back to legacy");
            ++range_rejections;
            std::cerr<<"expected-range-rejection width="<<w<<" height=2 amount="<<p.film_render.halation.halation_amount<<'\n';
        }
    }
    mode(nullptr);check(bool(frozen)&&bool(changed)&&bool(stages)&&bool(calls)&&bool(configuration),"evidence write failed");
    std::ofstream summary(dir/"summary.json");summary.precision(17);
    summary<<"{\"passed\":true,\"scenarios\":"<<completed<<",\"stage_comparisons\":"<<stage_comparisons
        <<",\"range_rejections\":"<<range_rejections
        <<",\"legacy_dispatches\":"<<gpu.legacy_calls<<",\"corrected_dispatches\":"<<gpu.corrected_calls
        <<",\"oracle_values\":"<<gpu.checked_values<<",\"maximum_oracle_error\":"<<gpu.worst
        <<",\"maximum_scaled_oracle_error\":"<<gpu.relative_worst
        <<",\"maximum_oracle_convergence\":"<<gpu.convergence<<",\"maximum_sigma\":"<<gpu.max_sigma<<"}\n";
    check(bool(summary),"summary write failed");std::cerr<<"PASS complete pipeline: "<<completed<<" scenarios, "<<stage_comparisons<<" stage pairs, "<<gpu.checked_values<<" IIR values\n";
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<'\n';return 1;}
