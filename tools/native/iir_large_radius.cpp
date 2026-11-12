// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Research dispatch bypasses Blur's admission guard; never enables a product mode.
// Reuse the existing explicit-tail and FIR references without their test main.
#define main iir_boundary_small_main
#include "iir_boundary_gpu.cpp"
#undef main
#include "pipeline/iir_boundary.hpp"
#ifdef SPK_IIR_DIFFERENCE
#include "pipeline/iir_difference.hpp"
#endif

Pixels large_reference(Pixels input,unsigned w,unsigned h,const double sigma[3],unsigned pad) {
    for(unsigned c=0;c<3;++c) {
        if(sigma[c]<3)continue;
        double b[4];spk::yvv_coeffs(sigma[c],b);
        const long double k=b[0]/(1.0L-b[1]-b[2]-b[3]);
        for(unsigned axis:{1u,0u}) {
            const unsigned lines=axis==1?h:w,length=axis==1?w:h,stride=axis==1?3:3*w;
            for(unsigned line=0;line<lines;++line) {
                const unsigned base=(axis==1?line*w*3:line*3)+c;
                // A one-sample axis is constant everywhere; its settled response
                // includes the real float32 forward store. Avoid O(pad*image size).
                if(length==1) {input[base]=float(float(input[base]*k)*k);continue;}
                Pixels row(length);for(unsigned i=0;i<length;++i)row[i]=input[base+i*stride];
                row=extended(row,sigma[c],pad);
                for(unsigned i=0;i<length;++i)input[base+i*stride]=row[i];
            }
        }
    }
    return input;
}

Pixels direct(spk::gpu::Gpu* gpu,const Pixels& pixels,unsigned w,unsigned h,
              const double sigma[3],bool add,bool alias,const char* kernel) {
    using spk::gpu::Arg;
    std::string e;float coef[90]{};uint32_t active[3]{};
    for(unsigned c=0;c<3;++c)if(sigma[c]>=3) {
        double b[4];spk::yvv_coeffs(sigma[c],b);active[c]=1;
#ifdef SPK_IIR_DIFFERENCE
        require(spk::detail::iir_difference_coeffs(b,coef+8*c,coef+24+22*c),"nonfinite difference coefficients");
#else
        require(spk::detail::iir_boundary_coeffs(b,coef+24+22*c),"nonfinite terminal coefficients");
        for(unsigned j=0;j<4;++j) {const float hi=float(b[j]);coef[8*c+2*j]=hi;coef[8*c+2*j+1]=float(b[j]-double(hi));}
#endif
    }
    auto src=gpu->upload(pixels.data(),pixels.size()*4,e);
    auto dst=alias?src:gpu->alloc(pixels.size()*4,e);
    auto cb=gpu->upload(coef,sizeof coef,e),ab=gpu->upload_u32(active,3,e);
    const float weight[3]={0.75f,-0.25f,1.5f};auto wb=gpu->upload(weight,sizeof weight,e);
    Pixels base(pixels.size());for(size_t i=0;i<base.size();++i)base[i]=float(int(i%17)-8)/13;
    auto acc=gpu->upload(base.data(),base.size()*4,e);
    require(bool(src)&&bool(dst)&&bool(cb)&&bool(ab)&&bool(wb)&&bool(acc),e.c_str());
    for(unsigned axis:{1u,0u}) {
        const unsigned threads=(axis==0?w:h)*3;
        for(unsigned first=0;first<threads;first+=768) {
            const uint32_t meta[6]={h,w,unsigned(add&&axis==0),axis,first,std::min(threads,first+768)};
            require(gpu->dispatch(kernel,{Arg::buf(axis==1?src:dst),Arg::buf(cb),Arg::buf(ab),
                Arg::buf(acc),Arg::buf(wb),Arg::inline_bytes(meta,6),Arg::buf(dst)},meta[5]-first,e),e.c_str());
        }
    }
    Pixels out(pixels.size());require(gpu->read(dst.get(),0,out.data(),out.size()*4,e),e.c_str());return out;
}

int main(int argc,char** argv)try {
    require(argc==3 || argc==4,"usage: iir_large_radius SHADERS OUTPUT [KERNEL]");
#ifdef SPK_IIR_DIFFERENCE
    const char* kernel=argc==4?argv[3]:"spk_iir_difference_df_acc";
    const bool public_v2=std::strcmp(kernel,"spk_iir_difference_df_acc")==0;
#else
    const char* kernel=argc==4?argv[3]:"spk_iir_boundary_df_acc";
    const bool public_v2=false;
#endif
    const std::filesystem::path dir=argv[2];std::filesystem::create_directories(dir);
    std::string error;std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1],error));
    require(bool(gpu),error.c_str());require(gpu->check_math_mode(error),error.c_str());
    std::fprintf(stderr,"device=%s math=%s kernel=%s\n",gpu->device_name().c_str(),error.c_str(),kernel);
    std::ofstream csv(dir/"cases.csv"),dump(dir/"output.f32",std::ios::binary);csv.precision(17);
    csv<<"w,h,sigma_r,sigma_g,sigma_b,pattern,accumulate,error,scaled_error,convergence,alias_error,passed\n";
    // Check the one-sample optimization against the unoptimized explicit tail.
    for(double s:{3.,256.,275.,512.,1024.})for(float v:{-0.25f,0.f,0.37f,0.5f,4.f}) {
        const double radii[3]={s,s,s};const Pixels p{v,v,v};
        require(same(large_reference(p,1,1,radii,128),reference(p,1,1,radii,128)),"one-sample oracle differs");
    }
    unsigned cases=0,failures=0;double worst=0,scaled=0,conv=0,alias_worst=0;
    const auto test=[&](unsigned w,unsigned h,const double sigma[3],unsigned pattern,bool add) {
        const auto input=signal(w,h,pattern);auto oracle=large_reference(input,w,h,sigma,64),longer=large_reference(input,w,h,sigma,128);
        if(add) {accumulate(oracle);accumulate(longer);}
        const auto actual=direct(gpu.get(),input,w,h,sigma,add,false,kernel);
        const auto aliased=direct(gpu.get(),input,w,h,sigma,add,true,kernel);
        const double err=difference(actual,longer),cv=difference(oracle,longer),ae=difference(actual,aliased);
        double scale=1;for(float v:input)scale=std::max(scale,std::abs(double(v)));
        const bool constant=pattern==0||pattern==7||pattern==8;
        if(public_v2) {mode("triggs-v2");require(same(actual,render(gpu.get(),input,w,h,sigma,add)),"public v2 differs from direct dispatch");}
        const bool passed=err<=(constant?1e-6:1e-5)*scale&&cv<=1e-7*scale&&same(actual,aliased);
        ++cases;failures+=!passed;worst=std::max(worst,err);scaled=std::max(scaled,err/scale);conv=std::max(conv,cv);alias_worst=std::max(alias_worst,ae);
        csv<<w<<','<<h<<','<<sigma[0]<<','<<sigma[1]<<','<<sigma[2]<<','<<pattern<<','<<add<<','<<err<<','<<err/scale<<','<<cv<<','<<ae<<','<<passed<<'\n';
        dump.write(reinterpret_cast<const char*>(actual.data()),actual.size()*4);
        if(!passed)std::fprintf(stderr,"failed w=%u h=%u sigma=%g pattern=%u add=%d error=%.12g scaled=%.12g conv=%.12g alias=%.12g\n",w,h,sigma[0],pattern,int(add),err,err/scale,cv,ae);
        if(cases%110==0) {csv.flush();std::fprintf(stderr,"progress=%u\n",cases);}
    };
    for(double s:{3.,96.,256.,257.,275.,384.,512.,768.,1024.}) {
        for(unsigned n:{1u,2u,3u,17u,513u,8192u})for(unsigned pattern:{0u,1u,3u,4u,5u,6u,7u,8u,9u})for(bool col:{false,true}) {
            const double sigma[3]={s,s,s};test(col?1:n,col?n:1,sigma,pattern,false);
        }
        for(bool add:{false,true}) {
            const double mixed[3]={s,0.,3.};test(17,19,mixed,6,add);
        }
    }
    // Deterministic held-out radii between the named grid points, plus an actual
    // 35mm CineStill full6000/amount2 component, not a changed error threshold.
    uint32_t rng=0x6a09e667u;
    for(unsigned j=0;j<24;++j) {
        rng=1664525u*rng+1013904223u;
        const double s=j==0?274.8917258329772:3.+1021.*double(rng)/4294967295.;
        const double mixed[3]={s,3.,s};test(1025,1,mixed,6,false);test(1,37,mixed,5,true);
    }
    const double wide[3]={1024.,512.,0.};test(257,259,wide,6,false);test(257,259,wide,6,true);
    if(public_v2) {
        const Pixels p=signal(3,2,1);spk::Image in,out;in.w=3;in.h=2;in.c=3;
        in.buf=gpu->upload(p.data(),p.size()*4,error);require(bool(in.buf),error.c_str());out.w=77;out.h=88;
        const double unsupported[3]={1025,3,6};spk::Blur blur(gpu.get());mode("triggs-v2");
        require(!blur.gaussian(in,unsupported,out,error)&&error.find("[3, 1024]")!=std::string::npos,"v2 range rejection missing");
        require(out.w==77&&out.h==88&&!out.buf,"rejected v2 published output");
        mode("triggs-v1");const double original_limit[3]={257,3,6};
        require(!blur.gaussian(in,original_limit,out,error)&&error.find("[3, 256]")!=std::string::npos,"v1 domain changed");
    }
    mode(nullptr);
    std::ofstream json(dir/"summary.json");json.precision(17);
    json<<"{\"passed\":"<<(failures?"false":"true")<<",\"cases\":"<<cases<<",\"failures\":"<<failures
        <<",\"maximum_error\":"<<worst<<",\"maximum_scaled_error\":"<<scaled<<",\"maximum_convergence\":"<<conv<<",\"maximum_alias_error\":"<<alias_worst<<"}\n";
    require(bool(csv)&&bool(dump)&&bool(json),"evidence write");
    std::fprintf(stderr,"completed cases=%u failures=%u max_error=%.12g scaled=%.12g\n",cases,failures,worst,scaled);
    return failures?1:0;
}catch(const std::exception& e){std::fprintf(stderr,"FAIL %s\n",e.what());return 1;}
