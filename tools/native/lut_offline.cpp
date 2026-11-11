// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Co-created with GPT (OpenAI), 2026-11-11. Synthetic, offline research only.
#include "pipeline/pipeline.hpp"
#include "core/hanatos.hpp"
#include "input_transform.hpp"
#include "output_transform.hpp"
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
        return interpolate({shape(x[0]),shape(x[1]),shape(x[2])},tetrahedral);
    }
    RGB interpolate(const std::array<double,3>& coordinate,bool tetrahedral) const {
        require(n>=2&&values.size()==size_t(n)*n*n,"invalid LUT lattice");
        std::array<unsigned,3> base;
        std::array<double,3> f;
        for(unsigned c=0;c<3;++c) {
            require(std::isfinite(coordinate[c])&&coordinate[c]>=-1e-7&&coordinate[c]<=1+1e-7,
                    "coordinate outside the requested lattice");
            const double position=std::clamp(coordinate[c],0.,1.)*(n-1);
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
        for(float v:out) require(std::isfinite(v),"nonfinite LUT interpolation result");
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

void validate_working_pack(spk::gpu::Gpu& gpu,const std::vector<RGB>& encoded,
                           const spk::android::OutputTransform& xf) {
    const auto source=flatten(encoded);
    auto src=gpu.upload(source.data(),source.size()*sizeof(float),error); require(bool(src),error);
    std::vector<uint16_t> working(encoded.size()*4);
    auto dst=gpu.alloc(working.size()*sizeof(uint16_t),error); require(bool(dst),error);
    const uint32_t meta[]={uint32_t(encoded.size()),uint32_t(encoded.size()),uint32_t(encoded.size())};
    require(gpu.dispatch("spk_to_rgba16",{spk::gpu::Arg::buf(src),spk::gpu::Arg::inline_bytes(meta,3),
        spk::gpu::Arg::buf(dst)},encoded.size(),error)&&gpu.read(dst.get(),0,working.data(),working.size()*sizeof(uint16_t),error),error);
    for(size_t i=0;i<encoded.size();++i) {
        for(unsigned c=0;c<3;++c)
            require(working[4*i+c]==uint16_t(std::floor(std::clamp(encoded[i][c],0.f,1.f)*65535.f+.5f)),"GPU/CPU working quantization differs");
        require(working[4*i+3]==65535,"GPU working alpha differs");
    }
    std::vector<uint16_t> actual16,probe16;
    spk::android::pack_rgba16(working.data(),encoded.size(),uint32_t(encoded.size()),1,actual16,nullptr,xf);
    spk::android::pack_float_rgba16(source.data(),encoded.size(),uint32_t(encoded.size()),1,probe16,nullptr,xf);
    require(actual16==probe16,"GPU-working and float-probe P3 16bit bytes differ");
    std::vector<uint8_t> actual8(encoded.size()*4),probe8(actual8.size());
    spk::android::pack_rgba8(working.data(),encoded.size(),uint32_t(encoded.size()),1,actual8.data(),actual8.size(),nullptr,xf);
    spk::android::pack_float_rgba8(source.data(),encoded.size(),uint32_t(encoded.size()),1,probe8.data(),probe8.size(),nullptr,xf);
    require(actual8==probe8,"GPU-working and float-probe P3 8bit bytes differ");
    std::cout<<"PASS actual GPU working pack and production float/u16 P3 output parity pixels="<<encoded.size()<<'\n';
}

// Both candidates cross the production UNORM16 working-space boundary before
// their P3 display conversion. Lab metrics consume decoded P3, not encoded RGB.
std::vector<RGB> display(const std::vector<RGB>& encoded, const spk::android::OutputTransform& xf, bool eight) {
    for(const auto& rgb:encoded) for(float v:rgb) require(std::isfinite(v),"nonfinite display input");
    const auto source=flatten(encoded);
    std::vector<RGB> result(encoded.size());
    if(eight) {
        std::vector<uint8_t> packed(encoded.size()*4);
        spk::android::pack_float_rgba8(source.data(),encoded.size(),uint32_t(encoded.size()),1,
            packed.data(),packed.size(),nullptr,xf);
        for(size_t i=0;i<result.size();++i) for(unsigned c=0;c<3;++c) result[i][c]=packed[4*i+c]/255.f;
    } else {
        std::vector<uint16_t> packed;
        spk::android::pack_float_rgba16(source.data(),encoded.size(),uint32_t(encoded.size()),1,packed,nullptr,xf);
        for(size_t i=0;i<result.size();++i) for(unsigned c=0;c<3;++c) result[i][c]=packed[4*i+c]/65535.f;
    }
    for(auto& rgb:result) for(float& v:rgb)
        v=v<=.04045f?v/12.92f:std::pow((v+.055f)/1.055f,2.4f);
    return result;
}

void signed_coordinates(spk::gpu::Gpu& gpu,const spk::Mat3& matrix,const std::vector<Sample>& samples,
                        unsigned n,const std::string& path) {
    std::vector<RGB> inputs; for(const auto& sample:samples) inputs.push_back(sample.x);
    const auto source=flatten(inputs);
    std::vector<float> coefficients;
    for(const auto& row:matrix.m) for(double value:row) coefficients.push_back(float(value));
    auto src=gpu.upload(source.data(),source.size()*sizeof(float),error); require(bool(src),error);
    auto mat=gpu.upload(coefficients.data(),coefficients.size()*sizeof(float),error); require(bool(mat),error);
    auto tc=gpu.alloc(samples.size()*2*sizeof(float),error); require(bool(tc),error);
    auto brightness=gpu.alloc(samples.size()*sizeof(float),error); require(bool(brightness),error);
    const uint32_t meta[]={uint32_t(samples.size())};
    require(gpu.dispatch("spk_tc_b",{spk::gpu::Arg::buf(src),spk::gpu::Arg::buf(mat),
        spk::gpu::Arg::inline_bytes(meta,1),spk::gpu::Arg::buf(tc),spk::gpu::Arg::buf(brightness)},samples.size(),error),error);
    std::vector<float> coords(samples.size()*2),energy(samples.size());
    require(gpu.read(tc.get(),0,coords.data(),coords.size()*sizeof(float),error)&&
        gpu.read(brightness.get(),0,energy.data(),energy.size()*sizeof(float),error),error);
    std::ofstream out(path); out<<std::setprecision(12);
    out<<"group,in_r,in_g,in_b,gpu_tc_x,gpu_tc_y,gpu_brightness,cpu_X,cpu_Y,cpu_Z,positive_xyz_cell\n";
    for(size_t i=0;i<samples.size();++i) {
        const RGB& x=samples[i].x;
        double xyz[3]={};
        for(unsigned r=0;r<3;++r) for(unsigned c=0;c<3;++c) xyz[r]+=double(coefficients[3*r+c])*x[c];
        bool positive=supported(x);
        std::array<unsigned,3> base{};
        if(positive) for(unsigned c=0;c<3;++c) base[c]=std::min(unsigned(shape(x[c])*(n-1)),n-2);
        // A diagnostic classification, not a proven interpolation-error bound.
        if(positive) for(unsigned corner=0;corner<8;++corner) {
            RGB vertex;
            for(unsigned c=0;c<3;++c) vertex[c]=unshape(double(base[c]+((corner>>c)&1))/(n-1));
            for(unsigned r=0;r<3;++r) {
                double value=0,scale=0;
                for(unsigned c=0;c<3;++c) {
                    const double term=double(coefficients[3*r+c])*vertex[c];
                    value+=term; scale+=std::abs(term);
                }
                if(value<=1e-6*scale+1e-12) positive=false;
            }
        }
        require(std::isfinite(coords[2*i])&&std::isfinite(coords[2*i+1])&&std::isfinite(energy[i]),
                "nonfinite actual upsampling coordinates");
        out<<samples[i].group;
        for(float value:x) out<<','<<value;
        out<<','<<coords[2*i]<<','<<coords[2*i+1]<<','<<energy[i];
        for(double value:xyz) out<<','<<value;
        out<<','<<positive<<'\n';
    }
}

void local_signed_probe(Renderer& renderer,spk::gpu::Gpu& gpu,const spk::Mat3& matrix,
                        const spk::android::OutputTransform& output,const std::string& dir,
                        std::ostream& results,std::ostream& timing) {
    // Previous held-out failures are regression anchors, not a new acceptance set.
    const std::vector<RGB> anchors={{.5552405119f,-.2167534232f,-.2419789582f},
        {.003888315056f,.3225855529f,-.2246024311f},
        {.167887941f,-.01222540624f,-.09380492568f},
        {.1558107734f,-.01677556336f,-.09125823528f}};
    for(size_t anchor=0;anchor<anchors.size();++anchor) {
        std::array<double,3> lower{},upper{};
        for(unsigned c=0;c<3;++c) {
            const unsigned cell=std::min(unsigned(shape(anchors[anchor][c])*32),31u);
            lower[c]=double(cell)/32; upper[c]=double(cell+1)/32;
        }
        constexpr unsigned edge=17;
        std::vector<RGB> grid;
        for(unsigned r=0;r<edge;++r) for(unsigned g=0;g<edge;++g) for(unsigned b=0;b<edge;++b) {
            RGB x; const unsigned indices[]={r,g,b};
            for(unsigned c=0;c<3;++c) x[c]=unshape(lower[c]+(upper[c]-lower[c])*indices[c]/(edge-1));
            grid.push_back(x);
        }
        Lut local{edge,renderer.run(grid,edge,"local-grid-"+std::to_string(anchor),timing,true)};
        std::vector<Sample> samples={{"anchor-"+std::to_string(anchor),anchors[anchor]}};
        for(unsigned c=0;c<3;++c) for(int direction:{-1,1}) {
            RGB neighbor=anchors[anchor];
            neighbor[c]=std::nextafter(neighbor[c],direction<0?-INFINITY:INFINITY);
            samples.push_back({"ulp-"+std::to_string(anchor),neighbor});
        }
        for(unsigned step=0;step<257;++step) {
            // Off-grid line through the same original cell, independent of local nodes.
            RGB x;
            for(unsigned c=0;c<3;++c) x[c]=unshape(lower[c]+(upper[c]-lower[c])*(step+.371)/257);
            samples.push_back({"cell-line-"+std::to_string(anchor),x});
        }
        // Probe the actual front-end Y=0 branch without changing the renderer.
        const float mr=float(matrix.m[1][0]),mg=float(matrix.m[1][1]),mb=float(matrix.m[1][2]);
        if(mg!=0) {
            RGB crossing=anchors[anchor];
            crossing[1]=float(-(double(mr)*crossing[0]+double(mb)*crossing[2])/mg);
            if(supported(crossing)&&shape(crossing[1])>lower[1]&&shape(crossing[1])<upper[1]) {
                for(int step=-32;step<=32;++step) {
                    RGB x=crossing;
                    for(int ulp=0;ulp<std::abs(step);++ulp)
                        x[1]=std::nextafter(x[1],step<0?-INFINITY:INFINITY);
                    samples.push_back({"Y-cross-"+std::to_string(anchor),x});
                }
            }
        }
        std::vector<RGB> inputs,approximate;
        for(const auto& sample:samples) {
            inputs.push_back(sample.x);
            std::array<double,3> position;
            for(unsigned c=0;c<3;++c) position[c]=(shape(sample.x[c])-lower[c])/(upper[c]-lower[c]);
            approximate.push_back(local.interpolate(position,true));
        }
        const auto reference=display(renderer.run(inputs,1,"local-reference-"+std::to_string(anchor),timing,true),output,false);
        const auto packed=display(approximate,output,false);
        for(size_t i=0;i<samples.size();++i) csv(results,"local16",edge,"tetrahedral",samples[i],reference[i],packed[i],false);
        signed_coordinates(gpu,matrix,samples,33,dir+"/local-coordinates-"+std::to_string(anchor)+".csv");
    }
}
}

int main(int argc,char** argv) {
    try {
        require(argc==5,"usage: lut_offline SHADERS RESOURCES OUTDIR smoke|full|gate|diagnostic");
        static_assert(sizeof(RGB)==3*sizeof(float));
        const bool diagnostic=std::string(argv[4])=="diagnostic";
        const bool gate=std::string(argv[4])=="gate"||diagnostic;
        require(std::string(argv[4])=="smoke"||std::string(argv[4])=="full"||gate,"invalid mode");
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
        require(spk::init_params(argv[2],"kodak_portra_400",gate?"kodak_supra_endura":"kodak_portra_endura",filters,point,error),error);
        point.io.input_color_space="ProPhoto RGB"; point.io.input_cctf_decoding=false;
        point.io.output_color_space=gate?"ProPhoto RGB":"Display P3"; point.io.output_cctf_encoding=gate;
        if(gate) {
            point.camera.film_format_mm=36;
            point.io.preview_long_edge=1024;
            point.enlarger.print_exposure_compensation=false;
        }
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
        if(gate) std::ofstream(dir+"/contract.json")<<R"({"film":"kodak_portra_400","paper":"kodak_supra_endura","input":"linear ProPhoto RGB","lut_values":"encoded ProPhoto RGB float32","metrics":"production UNORM16 working boundary, production P3 display transform, quantized 16/8 bit P3 decoded to linear P3","domain":[-0.25,16],"shaper_pivot":0.01,"shaper_zero":0.25,"film_format_mm":36,"live_edge":1024,"auto_exposure":"fixed 0 EV, no per-image metering","point_spatial_effects":"disabled; separate full spatial ablation","fusion":"off","grid_order":"blue-fast RGB float32","seed":539365650,"quality_gate":"each common synthetic group mean DE00 <= 3; tails and stress reported separately; not natural-photo or Android acceptance","warning":"read_params is not a complete internal Params serialization"})"<<'\n';
        std::ofstream timing(dir+"/timing.csv"); timing<<"render,stage,ms\n"<<std::setprecision(12);
        std::ofstream results(dir+"/samples.csv");
        results<<"set,size,method,group,fallback,in_r,in_g,in_b,ref_r,ref_g,ref_b,got_r,got_g,got_b\n"<<std::setprecision(10);
        spk::SetupCache cache;
        Renderer renderer(gpu.get(),&colour,&blob,&cache,point);
        std::mt19937 random(gate?0x20261112:0x20261111); std::uniform_real_distribution<double> uniform(0,1);
        spk::android::InputTransform input("sRGB");
        std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> engine(nullptr,&spk_engine_destroy);
        spk::android::OutputTransform output;
        if(gate) {
            engine.reset(spk_engine_create(argv[2],nullptr)); require(bool(engine),"output metadata engine creation failed");
            require(output.fetch(engine.get(),"Display P3",false,error),error);
        }
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
            if(gate) {
                auto add=[&](const char* label,RGB encoded) {
                    for(unsigned c=0;c<3;++c) encoded[c]=input.decode(encoded[c],c);
                    input.to_prophoto(encoded.data()); samples.push_back({label,encoded});
                };
                add("common-srgb",{float(uniform(random)),float(uniform(random)),float(uniform(random))});
                const float neutral=float(u); add("common-neutral",{neutral,neutral,neutral});
                add("common-warm",{float(.2+.7*uniform(random)),float(.1+.55*uniform(random)),float(.06+.44*uniform(random))});
                add("common-dark",{float(std::pow(10.,-4+3*uniform(random))),float(std::pow(10.,-4+3*uniform(random))),float(std::pow(10.,-4+3*uniform(random)))});
            }
        }
        for(RGB x:std::vector<RGB>{{-.26f,0,0},{16.1f,1,1},{1,-1,0},{32,32,32}}) samples.push_back({"outside",x});
        std::vector<RGB> inputs; for(const auto& s:samples) inputs.push_back(s.x);
        const auto reference=renderer.run(inputs,1,"held-out",timing,true);
        if(gate) validate_working_pack(*gpu,reference,output);
        const auto reference16=gate?display(reference,output,false):std::vector<RGB>{};
        const auto reference8=gate?display(reference,output,true):std::vector<RGB>{};
        auto reversed=inputs; std::reverse(reversed.begin(),reversed.end());
        auto permutation=renderer.run(reversed,uint32_t(reversed.size()),"permuted-reshape",timing,true);
        std::reverse(permutation.begin(),permutation.end());
        require(std::memcmp(reference.data(),permutation.data(),reference.size()*sizeof(RGB))==0,"point function failed permutation/reshape byte identity");
        const auto repeat=renderer.run(inputs,1,"repeat",timing,true);
        require(std::memcmp(reference.data(),repeat.data(),reference.size()*sizeof(RGB))==0,"point function failed repeat byte identity");
        std::vector<RGB> outside;
        for(const auto& s:samples) if(!supported(s.x)) outside.push_back(s.x);
        const auto fallback_values=renderer.run(outside,1,"out-of-domain-fallback",timing,true);
        spk::Mat3 tc_matrix;
        if(diagnostic) require(spk::tc_b_matrix(colour,blob,point.io.input_color_space,
            point.film.info.reference_illuminant,tc_matrix,error),error);
        if(diagnostic) {
            std::ofstream config(dir+"/diagnostic.json");
            config<<R"({"mode":"diagnostic","global_grids":[33,65],"local_subgrid":17,"local_anchors":"four previous held-out failures; not an acceptance set","classifier":"positive XYZ at all original lattice cell vertices with rounding margin; diagnostic, not a DE00 guarantee","matrix_rgb_to_xyz_float32":[)";
            for(unsigned r=0;r<3;++r) for(unsigned c=0;c<3;++c)
                config<<(r||c?",":"")<<std::setprecision(12)<<float(tc_matrix.m[r][c]);
            config<<"]}\n";
        }
        for(unsigned n:(smoke?std::vector<unsigned>{9}:gate&&!diagnostic?std::vector<unsigned>{33}:std::vector<unsigned>{33,65})) {
            if(diagnostic) signed_coordinates(*gpu,tc_matrix,samples,n,dir+"/coordinates-"+std::to_string(n)+".csv");
            std::vector<RGB> grid;
            for(unsigned r=0;r<n;++r) for(unsigned g=0;g<n;++g) for(unsigned b=0;b<n;++b)
                grid.push_back({unshape(double(r)/(n-1)),unshape(double(g)/(n-1)),unshape(double(b)/(n-1))});
            Lut lut{n,renderer.run(grid,n,"grid-"+std::to_string(n),timing,true)};
            std::ofstream binary(dir+"/lut-"+std::to_string(n)+".f32",std::ios::binary);
            binary.write(reinterpret_cast<const char*>(lut.values.data()),std::streamsize(lut.values.size()*sizeof(RGB)));
            for(bool tetra:{false,true}) {
                size_t fallback_index=0;
                std::vector<RGB> approximate;
                for(size_t i=0;i<samples.size();++i) {
                    const bool fallback=!supported(samples[i].x);
                    const RGB got=fallback?fallback_values[fallback_index++]:lut.sample(samples[i].x,tetra);
                    if(fallback) require(got==reference[i],"actual fallback differs from reference");
                    if(gate) approximate.push_back(got);
                    else csv(results,"interpolation",n,tetra?"tetrahedral":"trilinear",samples[i],reference[i],got,fallback);
                }
                if(gate) for(bool eight:{false,true}) {
                    const auto packed=display(approximate,output,eight);
                    const auto& ref=eight?reference8:reference16;
                    for(size_t i=0;i<samples.size();++i)
                        csv(results,eight?"interpolation8":"interpolation16",n,tetra?"tetrahedral":"trilinear",samples[i],ref[i],packed[i],!supported(samples[i].x));
                }
            }
            if(gate&&!diagnostic) {
                // A real 1024-pixel live geometry, not a reshaped training cube.
                const unsigned width=1024,height=768;
                std::vector<RGB> scene; scene.reserve(size_t(width)*height);
                for(unsigned y=0;y<height;++y) for(unsigned x=0;x<width;++x) {
                    const double u=double(x)/(width-1),v=double(y)/(height-1);
                    const double checker=((x/43+y/37)%2)?1.:.45;
                    RGB rgb={float((.05+.9*u)*checker),float((.03+.92*v)*checker),float((.04+.9*(1-u*v))*checker)};
                    for(unsigned c=0;c<3;++c) rgb[c]=input.decode(rgb[c],c);
                    input.to_prophoto(rgb.data()); scene.push_back(rgb);
                }
                std::vector<RGB> exact,approximate;
                for(unsigned repeat=0;repeat<3;++repeat) {
                    auto start=std::chrono::steady_clock::now();
                    const auto raw=renderer.run(scene,width,"preview-point-"+std::to_string(repeat),timing,true);
                    exact=display(raw,output,false);
                    timing<<"preview-exact-end-to-end-"<<repeat<<",wall,"<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<'\n';
                    start=std::chrono::steady_clock::now();
                    std::vector<RGB> interpolated; interpolated.reserve(scene.size());
                    for(const auto& rgb:scene) interpolated.push_back(lut.sample(rgb,true));
                    approximate=display(interpolated,output,false);
                    timing<<"preview-cpu-lut-end-to-end-"<<repeat<<",wall,"<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<'\n';
                }
                Renderer complete(gpu.get(),&colour,&blob,&cache,full);
                const auto spatial_start=std::chrono::steady_clock::now();
                const auto spatial=display(complete.run(scene,width,"preview-full-spatial",timing,false),output,false);
                timing<<"preview-full-spatial-end-to-end,wall,"<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-spatial_start).count()<<'\n';
                // Held-out image pixels only; training nodes never enter metrics.
                for(size_t i=0;i<scene.size();i+=53) {
                    csv(results,"preview16",n,"tetrahedral",{"synthetic-1024-scene",scene[i]},exact[i],approximate[i],false);
                    csv(results,"model-deletion16",0,"point-vs-spatial",{"synthetic-1024-scene",scene[i]},spatial[i],exact[i],false);
                }
            }
        }
        if(diagnostic) local_signed_probe(renderer,*gpu,tc_matrix,output,dir,results,timing);
        const unsigned edge=smoke?16:64;
        std::vector<RGB> scene;
        for(unsigned y=0;y<edge;++y) for(unsigned x=0;x<edge;++x) {
            const double v=std::pow(2.,-12+16.*x/(edge-1));
            const double t=double(y)/(edge-1);
            scene.push_back({float(v),float(v*(.15+.85*t)),float(v*(1-.85*t))});
        }
        const auto without_raw=renderer.run(scene,edge,"ablation-point",timing,true);
        const auto without=gate?display(without_raw,output,false):without_raw;
        Renderer complete(gpu.get(),&colour,&blob,&cache,full);
        const auto with_raw=complete.run(scene,edge,"ablation-configured-spatial",timing,false);
        const auto with=gate?display(with_raw,output,false):with_raw;
        for(size_t i=0;i<scene.size();++i) csv(results,"model-deletion",0,"point-vs-configured-spatial",{"synthetic-scene",scene[i]},with[i],without[i],false);
        std::cout<<"PASS fixed point permutation/repeat; LUT generation and held-out data complete samples="<<samples.size()<<'\n';
    } catch(const std::exception& e) { std::cerr<<"FAIL "<<e.what()<<'\n'; return 1; }
}
