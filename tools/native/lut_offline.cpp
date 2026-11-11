// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-11. Synthetic, offline research only.
#include "pipeline/pipeline.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <vector>

namespace {
using RGB=std::array<float,3>;
std::string error;
void require(bool ok,const std::string& message) { if(!ok) throw std::runtime_error(message); }
constexpr double low=-.25, high=16., pivot=.01, zero=.25;
double shape(double x) {
    return x<0 ? zero*(1-std::log1p(-x/pivot)/std::log1p(-low/pivot))
               : zero+(1-zero)*std::log1p(x/pivot)/std::log1p(high/pivot);
}
float unshape(double u) {
    return float(u<zero ? -pivot*std::expm1((1-u/zero)*std::log1p(-low/pivot))
                            : pivot*std::expm1((u-zero)/(1-zero)*std::log1p(high/pivot)));
}
bool supported(const RGB& x) {
    return std::all_of(x.begin(),x.end(),[](float v){return std::isfinite(v)&&v>=low&&v<=high;});
}
struct Sample { std::string group; RGB x; };
std::vector<float> flatten(const std::vector<RGB>& values) {
    std::vector<float> result; result.reserve(values.size()*3);
    for(const auto& x:values) result.insert(result.end(),x.begin(),x.end());
    return result;
}
std::vector<RGB> triples(const std::vector<float>& values) {
    std::vector<RGB> result(values.size()/3);
    for(size_t i=0;i<result.size();++i) std::copy_n(values.data()+3*i,3,result[i].begin());
    return result;
}
class Renderer {
    spk::gpu::Gpu* gpu_;
    spk::Pipeline pipeline_;
public:
    Renderer(spk::gpu::Gpu* gpu,spk::Colour* colour,spk::Blob* blob,spk::SetupCache* cache,const spk::Params& params)
      :gpu_(gpu),pipeline_(gpu,colour,blob,cache) { require(pipeline_.build(params,error),error); }
    std::vector<RGB> run(const std::vector<RGB>& values,uint32_t width,const std::string& label,
                         std::ostream& timing,bool point) {
        require(!values.empty()&&values.size()%width==0,"invalid synthetic shape");
        const auto input=flatten(values);
        spk::Image source,negative,output;
        source.w=width; source.h=uint32_t(values.size()/width); source.c=3;
        source.buf=gpu_->upload(input.data(),source.bytes(),error); require(bool(source.buf),error);
        pipeline_.set_source_shape(source.w,source.h,source.w,source.h);
        spk::Progress film,print; film.detailed=print.detailed=true;
        const auto start=std::chrono::steady_clock::now();
        require(pipeline_.run_film(source,negative,&film,error),error);
        require(pipeline_.run_print(negative,output,&print,error),error);
        std::vector<float> out(output.elements());
        require(gpu_->read(output.buf.get(),0,out.data(),output.bytes(),error),error);
        const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        timing<<label<<",wall,"<<ms<<'\n';
        for(const auto* progress:{&film,&print}) for(const auto& [name,duration]:progress->node_ms) {
            timing<<label<<','<<name<<','<<duration<<'\n';
            if(point) for(const char* forbidden:{"auto_exposure","headroom","halation","grain","glare","unsharp","blur","scene_latitude","contrast_mask"})
                require(name.find(forbidden)==std::string::npos,"point render executed "+name);
        }
        for(float v:out) require(std::isfinite(v),"nonfinite actual Pipeline result");
        std::cout<<"render="<<label<<" pixels="<<values.size()<<" ms="<<ms<<'\n';
        return triples(out);
    }
};
struct Lut {
    unsigned n;
    std::vector<RGB> values;
    const RGB& at(unsigned r,unsigned g,unsigned b) const { return values[(size_t(r)*n+g)*n+b]; }
    RGB sample(const RGB& x,bool tetrahedral) const {
        require(supported(x),"out-of-domain LUT request must use actual renderer");
        std::array<unsigned,3> base;
        std::array<double,3> f;
        for(unsigned c=0;c<3;++c) {
            const double position=std::clamp(shape(x[c]),0.,1.)*(n-1);
            base[c]=std::min(unsigned(position),n-2); f[c]=position-base[c];
        }
        RGB out{};
        if(tetrahedral) {
            std::array<unsigned,3> order={0,1,2};
            std::stable_sort(order.begin(),order.end(),[&](unsigned a,unsigned b){return f[a]>f[b];});
            auto p=base;
            const RGB a=at(p[0],p[1],p[2]); ++p[order[0]];
            const RGB b=at(p[0],p[1],p[2]); ++p[order[1]];
            const RGB c=at(p[0],p[1],p[2]); ++p[order[2]];
            const RGB d=at(p[0],p[1],p[2]);
            for(unsigned channel=0;channel<3;++channel)
                out[channel]=float((1-f[order[0]])*a[channel]+(f[order[0]]-f[order[1]])*b[channel]+
                    (f[order[1]]-f[order[2]])*c[channel]+f[order[2]]*d[channel]);
        } else {
            for(unsigned r=0;r<2;++r) for(unsigned g=0;g<2;++g) for(unsigned b=0;b<2;++b) {
                const double weight=(r?f[0]:1-f[0])*(g?f[1]:1-f[1])*(b?f[2]:1-f[2]);
                const RGB& v=at(base[0]+r,base[1]+g,base[2]+b);
                for(unsigned c=0;c<3;++c) out[c]+=float(weight*v[c]);
            }
        }
        return out;
    }
};
void interpolation_checks() {
    Lut affine{2,{}};
    for(unsigned r=0;r<2;++r) for(unsigned g=0;g<2;++g) for(unsigned b=0;b<2;++b)
        affine.values.push_back({float(1+2*r+3*g+4*b),float(int(r)-int(g)),float(b)});
    for(double a:{.13,.42,.87}) for(double b:{.17,.57,.91}) for(double c:{.11,.61,.97}) {
        const RGB x={unshape(a),unshape(b),unshape(c)};
        for(bool tetra:{false,true}) {
            const auto y=affine.sample(x,tetra);
            require(std::abs(y[0]-(1+2*a+3*b+4*c))<2e-6,"affine interpolation failed");
        }
    }
    for(float v:{float(low),-.01f,0.f,.00001f,.18f,1.f,16.f})
        require(std::abs(double(unshape(shape(v)))-v)<1e-6,"shaper roundtrip failed");
    require(!supported({-.251f,0,0})&&!supported({16.01f,0,0})&&!supported({NAN,0,0}),"domain guard failed");
}
void csv(std::ostream& out,const std::string& set,unsigned size,const std::string& method,
         const Sample& s,const RGB& ref,const RGB& got,bool fallback) {
    out<<set<<','<<size<<','<<method<<','<<s.group<<','<<fallback;
    for(const auto& v:{s.x,ref,got}) for(float c:v) out<<','<<c;
    out<<'\n';
}
}

int main(int argc,char** argv) {
    try {
        require(argc==5,"usage: lut_offline SHADERS RESOURCES OUTDIR smoke|full");
        static_assert(sizeof(RGB)==3*sizeof(float));
        require(std::string(argv[4])=="smoke"||std::string(argv[4])=="full","invalid mode");
        const bool smoke=std::string(argv[4])=="smoke";
        const std::string dir=argv[3]; std::filesystem::create_directories(dir);
        setenv("SPK_VULKAN_FUSION","off",1); unsetenv("SPK_BOUNDED_PRINT_TAIL");
        interpolation_checks();
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1],error)); require(bool(gpu),error);
        std::string math; require(gpu->check_math_mode(math),math);
        std::cout<<"gpu="<<gpu->device_name()<<" math="<<math<<'\n';
        spk::Blob blob; spk::Colour colour;
        require(blob.open(std::string(argv[2])+"/spektrafilm_constants.bin",error)&&colour.init(blob,error),error);
        std::ifstream filterfile(std::string(argv[2])+"/neutral_print_filters.json");
        const std::string text{std::istreambuf_iterator<char>(filterfile),{}};
        spk::Json filters; require(spk::Json::parse(text,filters,error),error);
        spk::Params point;
        require(spk::init_params(argv[2],"kodak_portra_400","kodak_portra_endura",filters,point,error),error);
        point.io.input_color_space="ProPhoto RGB"; point.io.input_cctf_decoding=false;
        point.io.output_color_space="Display P3"; point.io.output_cctf_encoding=false;
        point.camera.auto_exposure=false; point.camera.exposure_baseline_ev=0;
        point.camera.scene_latitude.active=false; point.camera.scene_latitude.b_active=false;
        point.camera.scene_curve.clear(); point.film_render.halation.boost_ev=0;
        auto full=point;
        point.camera.lens_blur_um=0; point.camera.diffusion_filter.active=false;
        point.film_render.halation.active=false; point.film_render.halation.headroom_ev=0;
        point.film_render.grain.active=false; point.film_render.glare.active=false;
        point.film_render.overscan.active=false; point.film_render.date_imprint.active=false;
        point.film_render.dir_couplers.diffusion_size_um=0;
        point.enlarger.lens_blur=0; point.enlarger.diffusion_filter.active=false;
        point.print_render.glare.active=false; point.print_render.contrast_mask.active=false;
        point.scanner.lens_blur=0; point.scanner.unsharp_mask[0]=point.scanner.unsharp_mask[1]=0;
        point.io.geometry={}; point.io.crop=false; point.io.upscale_factor=1;
        std::ofstream(dir+"/point-params.json")<<spk::read_params(point).dump()<<'\n';
        std::ofstream(dir+"/ablation-reference-params.json")<<spk::read_params(full).dump()<<'\n';
        std::ofstream(dir+"/contract.json")<<R"({"input":"linear ProPhoto RGB","output":"linear Display P3","domain":[-0.25,16],"shaper_pivot":0.01,"shaper_zero":0.25,"dir_diffusion_size_um":0,"camera_diffusion_active":false,"enlarger_diffusion_active":false,"enlarger_lens_blur":0,"fusion":"off","grid_order":"blue-fast RGB float32","seed":539365649,"warning":"read_params is not a complete internal Params serialization"})"<<'\n';
        std::ofstream timing(dir+"/timing.csv"); timing<<"render,stage,ms\n"<<std::setprecision(12);
        std::ofstream results(dir+"/samples.csv");
        results<<"set,size,method,group,fallback,in_r,in_g,in_b,ref_r,ref_g,ref_b,got_r,got_g,got_b\n"<<std::setprecision(10);
        spk::SetupCache cache;
        Renderer renderer(gpu.get(),&colour,&blob,&cache,point);
        std::mt19937 random(0x20261111); std::uniform_real_distribution<double> uniform(0,1);
        std::vector<Sample> samples;
        const unsigned count=smoke?64:1024;
        for(unsigned i=0;i<count;++i) {
            const double u=(i+.371)/count;
            samples.push_back({"neutral",{unshape(u),unshape(u),unshape(u)}});
            samples.push_back({"dark",{float(std::pow(10.,-8+6*u)),float(std::pow(10.,-8+6*u)*.61),float(std::pow(10.,-8+6*u)*1.37)}});
            samples.push_back({"shaped",{unshape(uniform(random)),unshape(uniform(random)),unshape(uniform(random))}});
            samples.push_back({"linear",{float(low+(high-low)*uniform(random)),float(low+(high-low)*uniform(random)),float(low+(high-low)*uniform(random))}});
            RGB saturated={.00001f,.00001f,.00001f}; saturated[i%3]=unshape(.25+.75*u);
            samples.push_back({"saturated",saturated});
            samples.push_back({"signed",{float(-.249*u),float(16*u),float(.18*(1-u))}});
        }
        for(RGB x:std::vector<RGB>{{-.26f,0,0},{16.1f,1,1},{1,-1,0},{32,32,32}}) samples.push_back({"outside",x});
        std::vector<RGB> inputs; for(const auto& s:samples) inputs.push_back(s.x);
        const auto reference=renderer.run(inputs,1,"held-out",timing,true);
        auto reversed=inputs; std::reverse(reversed.begin(),reversed.end());
        auto permutation=renderer.run(reversed,uint32_t(reversed.size()),"permuted-reshape",timing,true);
        std::reverse(permutation.begin(),permutation.end());
        require(std::memcmp(reference.data(),permutation.data(),reference.size()*sizeof(RGB))==0,"point function failed permutation/reshape byte identity");
        const auto repeat=renderer.run(inputs,1,"repeat",timing,true);
        require(std::memcmp(reference.data(),repeat.data(),reference.size()*sizeof(RGB))==0,"point function failed repeat byte identity");
        std::vector<RGB> outside;
        for(const auto& s:samples) if(!supported(s.x)) outside.push_back(s.x);
        const auto fallback_values=renderer.run(outside,1,"out-of-domain-fallback",timing,true);
        for(unsigned n:(smoke?std::vector<unsigned>{9}:std::vector<unsigned>{33,65})) {
            std::vector<RGB> grid;
            for(unsigned r=0;r<n;++r) for(unsigned g=0;g<n;++g) for(unsigned b=0;b<n;++b)
                grid.push_back({unshape(double(r)/(n-1)),unshape(double(g)/(n-1)),unshape(double(b)/(n-1))});
            Lut lut{n,renderer.run(grid,n,"grid-"+std::to_string(n),timing,true)};
            std::ofstream binary(dir+"/lut-"+std::to_string(n)+".f32",std::ios::binary);
            binary.write(reinterpret_cast<const char*>(lut.values.data()),std::streamsize(lut.values.size()*sizeof(RGB)));
            for(bool tetra:{false,true}) {
                size_t fallback_index=0;
                for(size_t i=0;i<samples.size();++i) {
                    const bool fallback=!supported(samples[i].x);
                    const RGB got=fallback?fallback_values[fallback_index++]:lut.sample(samples[i].x,tetra);
                    if(fallback) require(got==reference[i],"actual fallback differs from reference");
                    csv(results,"interpolation",n,tetra?"tetrahedral":"trilinear",samples[i],reference[i],got,fallback);
                }
            }
        }
        const unsigned edge=smoke?16:64;
        std::vector<RGB> scene;
        for(unsigned y=0;y<edge;++y) for(unsigned x=0;x<edge;++x) {
            const double v=std::pow(2.,-12+16.*x/(edge-1));
            const double t=double(y)/(edge-1);
            scene.push_back({float(v),float(v*(.15+.85*t)),float(v*(1-.85*t))});
        }
        const auto without=renderer.run(scene,edge,"ablation-point",timing,true);
        Renderer complete(gpu.get(),&colour,&blob,&cache,full);
        const auto with=complete.run(scene,edge,"ablation-configured-spatial",timing,false);
        for(size_t i=0;i<scene.size();++i) csv(results,"model-deletion",0,"point-vs-configured-spatial",{"synthetic-scene",scene[i]},with[i],without[i],false);
        std::cout<<"PASS fixed point permutation/repeat; LUT generation and held-out data complete samples="<<samples.size()<<'\n';
    } catch(const std::exception& e) { std::cerr<<"FAIL "<<e.what()<<'\n'; return 1; }
}
