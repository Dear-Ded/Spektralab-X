// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Independent explicit-tail oracle, including the real float32 forward plane.
#include "pipeline/blur.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>

using Pixels=std::vector<float>;
using State=std::array<long double,3>;
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void mode(const char* value) {
#ifdef _WIN32
    require(_putenv_s("SPK_IIR_BOUNDARY",value ? value : "")==0,"environment");
#else
    require((value ? setenv("SPK_IIR_BOUNDARY",value,1) : unsetenv("SPK_IIR_BOUNDARY"))==0,"environment");
#endif
}
long double step(long double input, State& s, const double b[4]) {
    const long double v=b[0]*input+b[1]*s[0]+b[2]*s[1]+b[3]*s[2];
    s={v,s[0],s[1]}; return v;
}
Pixels extended(const Pixels& input, double sigma, unsigned multiple) {
    double b[4]; spk::yvv_coeffs(sigma,b);
    const long double ratio=b[0]/(1.0L-b[1]-b[2]-b[3]);
    const size_t pad=size_t(std::ceil(multiple*sigma))+128;
    Pixels forward(input.size()+pad);
    State state{input.front()*ratio,input.front()*ratio,input.front()*ratio};
    for (size_t i=0; i<forward.size(); ++i)
        forward[i]=float(step(i<input.size() ? input[i] : input.back(),state,b));
    const long double settled=float(input.back()*ratio)*ratio;
    state={settled,settled,settled};
    for (size_t i=forward.size(); i-- > 0;)
        forward[i]=float(step(forward[i],state,b));
    forward.resize(input.size());
    return forward;
}
int reflect(int x, int n) {
    x%=2*n; if (x<0) x+=2*n; return x<n ? x : 2*n-1-x;
}
Pixels fir(const Pixels& input, double sigma) {
    const int radius=int(3*sigma+0.5);
    std::vector<double> kernel(2*radius+1);
    double sum=0;
    for (int k=-radius; k<=radius; ++k) { kernel[k+radius]=std::exp(-0.5*k*k/(sigma*sigma)); sum+=kernel[k+radius]; }
    Pixels out(input.size(),0);
    for (size_t i=0; i<input.size(); ++i)
        for (int k=-radius; k<=radius; ++k)
            out[i]+=input[reflect(int(i)+k,int(input.size()))]*float(kernel[k+radius]/sum);
    return out;
}
Pixels reference(Pixels input, unsigned w, unsigned h, const double sigma[3], unsigned pad) {
    for (unsigned c=0; c<3; ++c) {
        if (!(sigma[c]>0)) continue;
        for (unsigned pass=0; pass<2; ++pass) {
            // FIR is vertical/horizontal; IIR is horizontal/vertical.
            const bool horizontal=(sigma[c]>=3) ? pass==0 : pass==1;
            const unsigned lines=horizontal ? h : w, length=horizontal ? w : h;
            const unsigned stride=horizontal ? 3 : 3*w;
            for (unsigned line=0; line<lines; ++line) {
                const unsigned base=(horizontal ? line*w*3 : line*3)+c;
                Pixels row(length);
                for (unsigned i=0; i<length; ++i) row[i]=input[base+i*stride];
                row=sigma[c]>=3 ? extended(row,sigma[c],pad) : fir(row,sigma[c]);
                for (unsigned i=0; i<length; ++i) input[base+i*stride]=row[i];
            }
        }
    }
    return input;
}
double difference(const Pixels& a, const Pixels& b) {
    require(a.size()==b.size(),"size mismatch"); double error=0;
    for (size_t i=0; i<a.size(); ++i) {
        require(std::isfinite(a[i]) && std::isfinite(b[i]),"nonfinite pixels");
        error=std::max(error,std::abs(double(a[i])-b[i]));
    }
    return error;
}
bool same(const Pixels& a, const Pixels& b) {
    return a.size()==b.size() && std::memcmp(a.data(),b.data(),a.size()*sizeof(float))==0;
}
Pixels render(spk::gpu::Gpu* gpu, const Pixels& pixels, unsigned w, unsigned h,
              const double sigma[3], bool accumulate) {
    spk::Image in,out,acc; in.w=w; in.h=h; in.c=3;
    std::string error;
    in.buf=gpu->upload(pixels.data(),pixels.size()*sizeof(float),error);
    require(bool(in.buf),error.c_str());
    Pixels base(pixels.size());
    for (size_t i=0; i<base.size(); ++i) base[i]=float(int(i%17)-8)/13;
    if (accumulate) {
        acc.w=w; acc.h=h; acc.c=3;
        acc.buf=gpu->upload(base.data(),base.size()*sizeof(float),error);
        require(bool(acc.buf),error.c_str());
    }
    const double weight[3]={0.75,-0.25,1.5};
    spk::Blur blur(gpu);
    require(blur.gaussian(in,sigma,out,error,3.0,accumulate ? &acc : nullptr,weight),error.c_str());
    Pixels result(pixels.size());
    require(gpu->read(out.buf.get(),0,result.data(),result.size()*sizeof(float),error),error.c_str());
    return result;
}
void accumulate(Pixels& pixels) {
    const float weight[3]={0.75f,-0.25f,1.5f};
    for (size_t i=0; i<pixels.size(); ++i)
        pixels[i]=std::fma(weight[i%3],pixels[i],float(int(i%17)-8)/13);
}
Pixels signal(unsigned w, unsigned h, unsigned pattern) {
    Pixels p(size_t(w)*h*3); const unsigned n=std::max(w,h);
    for (unsigned y=0; y<h; ++y) for (unsigned x=0; x<w; ++x) for (unsigned c=0; c<3; ++c) {
        const unsigned i=w>=h ? x : y; float v=0;
        if (pattern==0) v=0.5f;
        if (pattern==1) v=float(i)/std::max(1u,n-1);
        if (pattern==2) v=i>=n-std::min(n,8u) ? 1 : 0;
        if (pattern==3) v=i==n-1 ? 1 : 0;
        if (pattern==4) v=i==n/2 ? 1 : 0;
        if (pattern==5) v=i%2 ? 4.0f : -0.25f;
        if (pattern==6) v=float(int((x*19+y*31+c*7)%37)-11)/7;
        if (pattern==7) v=0.37f;
        if (pattern==8) v=0;
        if (pattern==9) v=i==0 ? 1 : 0;
        p[(size_t(y)*w+x)*3+c]=v;
    }
    return p;
}
int main(int argc,char** argv) try {
    require(argc==3 || (argc==4 && (std::strcmp(argv[3],"--legacy-only")==0 || std::strcmp(argv[3],"--triggs-v2")==0)),"usage: iir_boundary_gpu SHADERS OUTPUT [--legacy-only|--triggs-v2]");
    const bool legacy_only=argc==4&&std::strcmp(argv[3],"--legacy-only")==0;
    const bool differences=argc==4&&std::strcmp(argv[3],"--triggs-v2")==0;
    const char* experiment=differences?"triggs-v2":"triggs-v1";
    const std::filesystem::path dir=argv[2]; std::filesystem::create_directories(dir);
    std::string error;
    std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1],error));
    require(bool(gpu),error.c_str());
    std::fprintf(stderr,"device: %s\n",gpu->device_name().c_str());
    require(gpu->check_math_mode(error),error.c_str());
    std::fprintf(stderr,"math: %s\n",error.c_str());
    std::ofstream dump(dir/"legacy.f32",std::ios::binary), csv(dir/"cases.csv");
    csv.precision(17);
    csv<<"w,h,sigma_r,sigma_g,sigma_b,pattern,accumulate,oracle_error,oracle_convergence,legacy_error,reversal_error\n";
    unsigned cases=0, frozen=0; double worst=0, convergence=0, reversal=0, legacy_gap=0;
    std::array<double,5> by_sigma{};
    const std::array<double,5> radii{3,6,24,96,256};
    const auto check=[&](unsigned w,unsigned h,const double sigma[3],unsigned pattern,bool add) {
        const Pixels input=signal(w,h,pattern);
        mode(nullptr); const Pixels old=render(gpu.get(),input,w,h,sigma,add);
        dump.write(reinterpret_cast<const char*>(old.data()),old.size()*sizeof(float)); ++frozen;
        if (legacy_only) return;
        mode("off"); require(same(old,render(gpu.get(),input,w,h,sigma,add)),"off changed legacy");
        mode("unknown"); require(same(old,render(gpu.get(),input,w,h,sigma,add)),"unknown changed legacy");
        auto expected=reference(input,w,h,sigma,64), longer=reference(input,w,h,sigma,128);
        if (add) { accumulate(expected); accumulate(longer); }
        const double conv=difference(expected,longer);
        mode(experiment); const auto actual=render(gpu.get(),input,w,h,sigma,add);
        const double err=difference(actual,longer), old_err=difference(old,longer);
        double scale=1; for (float v:input) scale=std::max(scale,std::abs(double(v)));
        require(conv<=1e-7*scale,"tail reference did not converge");
        if (err>1e-5*scale) {
            std::fprintf(stderr,"w=%u h=%u sigma=%g pattern=%u add=%d error=%.12g\n",w,h,sigma[0],pattern,int(add),err);
            throw std::runtime_error("GPU boundary disagrees with explicit-tail oracle");
        }
        if (pattern==0 || pattern==7 || pattern==8)
            require(err<=1e-6*scale,"constant boundary error");
        double reverse_error=0;
        if (!add && (w==1 || h==1)) {
            Pixels flipped=input;
            for (size_t i=0; i<input.size()/3; ++i) for (size_t c=0; c<3; ++c)
                flipped[3*i+c]=input[input.size()-3*(i+1)+c];
            auto mirror=render(gpu.get(),flipped,w,h,sigma,false);
            for (size_t i=0; i<input.size()/3/2; ++i) for (size_t c=0; c<3; ++c)
                std::swap(mirror[3*i+c],mirror[mirror.size()-3*(i+1)+c]);
            reverse_error=difference(actual,mirror);
            require(reverse_error<=2e-5*scale,"corrected reversal asymmetry");
        }
        if (w==1 || h==1)
            for (size_t i=0; i<radii.size(); ++i)
                if (sigma[0]==radii[i]) by_sigma[i]=std::max(by_sigma[i],err);
        worst=std::max(worst,err); convergence=std::max(convergence,conv);
        reversal=std::max(reversal,reverse_error); legacy_gap=std::max(legacy_gap,old_err); ++cases;
        csv<<w<<','<<h<<','<<sigma[0]<<','<<sigma[1]<<','<<sigma[2]<<','<<pattern<<','<<int(add)<<','<<err<<','<<conv<<','<<old_err<<','<<reverse_error<<'\n';
    };
    for (unsigned n:{1u,2u,3u,17u,513u}) for (double s:{3.,6.,24.,96.,256.})
        for (unsigned pattern=0; pattern<10; ++pattern) for (bool column:{false,true}) {
            const double sigma[3]={s,s,s}; check(column ? 1 : n,column ? n : 1,sigma,pattern,false);
        }
    for (const auto shape:{std::pair{13u,7u},std::pair{17u,19u},std::pair{3u,257u}})
        for (const auto sigma:{std::array{0.,1.5,24.},std::array{2.999,3.,96.},std::array{256.,6.,0.},
                              std::array{0.,2.999,3.},std::array{3.,0.,96.},std::array{3.,6.,256.}})
            for (bool add:{false,true}) for (unsigned pattern:{5u,6u})
                check(shape.first,shape.second,sigma.data(),pattern,add);
    const double chunk_sigma[3]={3,6,24};
    for (bool add:{false,true}) check(257,259,chunk_sigma,6,add);
    if (!legacy_only) {
        mode(experiment); const auto p=signal(3,2,1);
        spk::Image in,out; in.w=3; in.h=2; in.c=3;
        in.buf=gpu->upload(p.data(),p.size()*sizeof(float),error);
        out.h=77; out.w=88; out.c=3;
        const double unsupported[3]={differences?1025.:257.,3,6}; spk::Blur blur(gpu.get());
        require(!blur.gaussian(in,unsupported,out,error) && error.find(differences?"[3, 1024]":"[3, 256]")!=std::string::npos,"unsupported sigma was not rejected");
        require(out.h==77 && out.w==88 && !out.buf,"rejected sigma changed output");
    }
    mode(nullptr); require(bool(dump) && bool(csv),"output failed");
    std::ofstream summary(dir/"summary.json");
    summary.precision(17);
    summary<<"{\"passed\":true,\"corrected_cases\":"<<cases<<",\"frozen_cases\":"<<frozen
           <<",\"maximum_oracle_error\":"<<worst<<",\"maximum_oracle_convergence\":"<<convergence
           <<",\"maximum_reversal_error\":"<<reversal<<",\"maximum_legacy_oracle_error\":"<<legacy_gap
           <<",\"line_oracle_error_by_sigma\":{";
    for (size_t i=0; i<radii.size(); ++i)
        summary<<(i ? "," : "")<<'\"'<<radii[i]<<"\":"<<by_sigma[i];
    summary<<"}}\n";
    require(bool(summary),"summary failed");
    std::fprintf(stderr,"PASS: %u corrected, %u frozen; max oracle %.12g, reversal %.12g\n",cases,frozen,worst,reversal);
    return 0;
} catch (const std::exception& e) { std::fprintf(stderr,"FAIL: %s\n",e.what()); return 1; }
