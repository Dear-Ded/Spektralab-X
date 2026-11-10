// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE for project terms.
// Compare production fused kernels with the unchanged, separated GPU kernels.
#include "gpu/gpu.hpp"
#include "core/blob.hpp"
#include "core/cam16.hpp"
#include "core/colour.hpp"
#include "pipeline/pipeline.hpp"
#include "vulkan_test_hooks.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using spk::gpu::Arg;
using spk::gpu::BufferRef;
using spk::gpu::Gpu;
namespace {
constexpr size_t guard_count = 19;
constexpr float sentinel = -12345.25f;
size_t cases = 0, compared = 0, nonfinite = 0;
std::string error;

void require(bool value, const std::string& detail) {
    if (!value) throw std::runtime_error(detail.empty() ? error : detail);
}
BufferRef upload(Gpu* gpu, const std::vector<float>& data) {
    auto result = gpu->upload(data.data(), data.size() * sizeof(float), error);
    require(bool(result), error);
    return result;
}
BufferRef upload_double(Gpu* gpu, const double* data, size_t n) {
    auto result = gpu->upload_persistent_f32(data, n, error);
    require(bool(result), error);
    return result;
}
std::vector<float> read(Gpu* gpu, const BufferRef& b, size_t n) {
    std::vector<float> out(n);
    require(gpu->read(b.get(), 0, out.data(), n * sizeof(float), error), error);
    return out;
}
void exact(const std::string& label, const std::vector<float>& a,
           const std::vector<float>& b, size_t active, bool guards = true) {
    require(a.size() == b.size(), label + ": size changed");
    for (size_t i = 0; i < a.size(); ++i) {
        if (guards && i >= active)
            require(a[i] == sentinel && b[i] == sentinel, label + ": tail guard overwritten");
        nonfinite += !std::isfinite(a[i]);
        if (std::bit_cast<uint32_t>(a[i]) != std::bit_cast<uint32_t>(b[i])) {
            std::cerr << label << " mismatch at " << i << " old=" << a[i] << " fused=" << b[i]
                      << " bits=" << std::hex << std::bit_cast<uint32_t>(a[i]) << "/"
                      << std::bit_cast<uint32_t>(b[i]) << std::dec << '\n';
            throw std::runtime_error("float32 bytes differ");
        }
    }
    compared += active;
    ++cases;
}

void dir_case(Gpu* gpu, uint32_t n, uint32_t knots, uint32_t cap) {
    require(spk::gpu::test_limit_dispatch_groups_x(gpu, cap, error), error);
    std::vector<float> x(knots * 3), y(knots * 3), inv((knots - 1) * 3);
    for (uint32_t k = 0; k < knots; ++k) for (uint32_t c = 0; c < 3; ++c) {
        x[3*k+c] = -4.0f + float(k) * .125f + float(c) * .03125f;
        y[3*k+c] = float(k*k) * (.001f + float(c) * .0001f);
        if (k + 1 < knots) inv[3*k+c] = 8.0f;
    }
    std::vector<float> input(size_t(n)*3), correction(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        const size_t c = i % 3, k = (i / 3) % knots;
        const float knot = x[3*k+c];
        const float direction = ((i/7)%2) ? std::numeric_limits<float>::infinity()
                                         : -std::numeric_limits<float>::infinity();
        const float target = i%5 == 0 ? std::nextafter(knot, direction) : knot;
        correction[i] = i%3 == 0 ? 0.0f : float(int(i%17)-8) * .125f;
        input[i] = target + correction[i];
        if (i%23 == 0) input[i] = 64.0f;
        if (i%29 == 0) input[i] = -64.0f;
        if (i%31 == 0) { input[i] = -0.0f; correction[i] = 0.0f; }
    }
    auto src=upload(gpu,input), corr=upload(gpu,correction), xb=upload(gpu,x), yb=upload(gpu,y);
    auto ib=upload(gpu,inv), a=upload(gpu,{1,1,1}), b=upload(gpu,{-1,-1,-1});
    std::vector<float> initial(input.size()+guard_count,sentinel);
    auto tmp=upload(gpu,initial), plain=upload(gpu,initial), fused=upload(gpu,initial);
    const uint32_t elements[]={n*3}, meta[]={knots,n};
    require(gpu->dispatch("spk_lincomb3",{Arg::buf(src),Arg::buf(corr),Arg::buf(a),Arg::buf(b),
                Arg::inline_bytes(elements,1),Arg::buf(tmp)},size_t(n)*3,error),error);
    require(gpu->dispatch("spk_curves",{Arg::buf(tmp),Arg::buf(xb),Arg::buf(ib),Arg::buf(yb),
                Arg::inline_bytes(meta,2),Arg::buf(plain)},n,error),error);
    require(gpu->dispatch("spk_dir_finish",{Arg::buf(src),Arg::buf(xb),Arg::buf(ib),Arg::buf(yb),
                Arg::inline_bytes(meta,2),Arg::buf(fused),Arg::buf(corr)},n,error),error);
    exact("dir/n="+std::to_string(n)+"/k="+std::to_string(knots)+"/cap="+std::to_string(cap),
          read(gpu,plain,initial.size()),read(gpu,fused,initial.size()),input.size());
}

struct Gamut {
    BufferRef m2x,m2r,cmax,matrix;
    spk::Cam16Setup setup;
};
Gamut gamut_tables(Gpu* gpu,const spk::Colour& colour,const std::string& space) {
    Gamut g;
    require(spk::cam16_setup_for(colour,space,g.setup,error),error);
    double x[9],r[9],conversion[9];
    g.setup.m_to_xyz.to_row_major(x); g.setup.m_to_rgb.to_row_major(r);
    spk::Mat3 matrix;
    const double d50[]={.3457,.3585};
    require(colour.matrix_XYZ_to_RGB(space,d50,"CAT02",matrix,error),error);
    matrix.transposed().to_row_major(conversion);
    g.m2x=upload_double(gpu,x,9); g.m2r=upload_double(gpu,r,9);
    g.matrix=upload_double(gpu,conversion,9);
    g.cmax=upload_double(gpu,g.setup.c_max_table.data(),g.setup.c_max_table.size());
    return g;
}
void gamut_case(Gpu* gpu,const Gamut& g,const std::string& space,uint32_t n,bool lightness,
                size_t knee_index,uint32_t cap) {
    require(spk::gpu::test_limit_dispatch_groups_x(gpu,cap,error),error);
    const double knees[][3]={{.8,1,1.2},{0,1,1},{.99,1.01,4}};
    const auto& s=g.setup;
    const auto& knee=knees[knee_index];
    const double k[]={s.F_L,s.N_bb,s.N_cb,s.n,s.z,s.A_w,s.c,s.N_c,
        s.l_grid.front(),s.l_grid.back(),s.h_grid.front(),s.h_grid[1]-s.h_grid[0],
        knee[0],knee[1],knee[2],lightness?.9:0.0,1.0,lightness?1.2:1.0,
        s.white_Jp,s.D_rgb[0],s.D_rgb[1],s.D_rgb[2]};
    auto kb=upload_double(gpu,k,22);
    const float values[]={-4,-.1f,-.0001f,-0.0f,0,1e-8f,.003f,.18f,.5f,.9f,1,1.01f,2,4,16};
    std::vector<float> input(size_t(n)*3);
    for(size_t i=0;i<input.size();++i) {
        input[i]=values[(i*7+i/3)%std::size(values)];
        if (i%4 == 0) input[i]=std::nextafter(input[i],std::numeric_limits<float>::infinity());
    }
    auto src=upload(gpu,input);
    std::vector<float> initial(input.size()+guard_count,sentinel);
    auto tmp=upload(gpu,initial),plain=upload(gpu,initial),fused=upload(gpu,initial);
    const uint32_t pixels[]={n},meta[]={n,uint32_t(s.l_grid.size()),uint32_t(s.h_grid.size()),lightness?1u:0u};
    require(gpu->dispatch("spk_matmul3",{Arg::buf(src),Arg::buf(g.matrix),Arg::inline_bytes(pixels,1),
                Arg::buf(tmp)},n,error),error);
    require(gpu->dispatch("spk_cam16ucs_compress",{Arg::buf(tmp),Arg::buf(g.m2x),Arg::buf(g.m2r),
                Arg::buf(g.cmax),Arg::buf(kb),Arg::inline_bytes(meta,4),Arg::buf(plain)},n,error),error);
    require(gpu->dispatch("spk_xyz_cam16_compress",{Arg::buf(src),Arg::buf(g.m2x),Arg::buf(g.m2r),
                Arg::buf(g.cmax),Arg::buf(kb),Arg::inline_bytes(meta,4),Arg::buf(fused),Arg::buf(g.matrix)},n,error),error);
    exact("xyz/"+space+"/n="+std::to_string(n)+"/lc="+std::to_string(lightness)+
          "/knee="+std::to_string(knee_index)+"/cap="+std::to_string(cap),
          read(gpu,plain,initial.size()),read(gpu,fused,initial.size()),input.size());
}

void mode(const char* value) {
#ifdef _WIN32
    require(_putenv_s("SPK_VULKAN_FUSION",value ? value : "")==0,"could not set fusion mode");
#else
    require((value ? setenv("SPK_VULKAN_FUSION",value,1) : unsetenv("SPK_VULKAN_FUSION"))==0,
            "could not set fusion mode");
#endif
}
std::vector<float> render(Gpu* gpu,spk::Colour& colour,spk::Blob& blob,spk::SetupCache& cache,
                         const spk::Params& params,const spk::Image& source,const char* selection) {
    mode(selection);
    spk::Pipeline pipeline(gpu,&colour,&blob,&cache);
    require(pipeline.build(params,error),error);
    pipeline.set_source_shape(source.w,source.h,source.w,source.h);
    spk::Image negative,printed;
    spk::Progress progress;
    progress.detailed=true;
    require(pipeline.run_film(source,negative,&progress,error),error);
    require(pipeline.run_print(negative,printed,&progress,error),error);
    const bool want_xyz=selection && std::strcmp(selection,"on")==0 && !params.io.digital_intermediate;
    require((progress.node_ms.count("scanning.xyz_gamut_fused")!=0)==want_xyz,
            "full render did not use the requested XYZ fusion path");
    return read(gpu,printed.buf,printed.elements());
}
void render_cases(Gpu* gpu,spk::Colour& colour,spk::Blob& blob,const std::string& resources,
                  const std::array<const char*,6>& spaces) {
    std::ifstream file(resources+"/neutral_print_filters.json");
    const std::string text{std::istreambuf_iterator<char>(file),{}};
    spk::Json filters;
    require(file.good()||file.eof(),"neutral filters unavailable");
    require(spk::Json::parse(text,filters,error),error);
    spk::Params params;
    require(spk::init_params(resources,"kodak_portra_400","kodak_portra_endura",filters,params,error),error);
    params.settings.grain_sampler="exact";
    params.film_render.grain.active=false;
    params.io.input_cctf_decoding=false;
    params.camera.auto_exposure=false;
    spk::Image source;
    source.w=67;source.h=49;source.c=3;
    std::vector<float> input(source.elements());
    for(size_t i=0;i<input.size();++i) input[i]=.0001f+float((i*37+i/17)%4093)/1700.0f;
    source.buf=upload(gpu,input);
    spk::SetupCache cache;
    require(spk::gpu::test_limit_dispatch_groups_x(gpu,0,error),error);
    for(const char* space:spaces) for(bool di:{false,true}) {
        params.io.output_color_space=space;
        params.io.digital_intermediate=di;
        const auto old=render(gpu,colour,blob,cache,params,source,"off");
        const auto fused=render(gpu,colour,blob,cache,params,source,"on");
        exact(std::string("render/")+space+(di?"/DI":"/paper"),old,fused,old.size(),false);
        std::cout<<"render space="<<space<<" di="<<di<<" float32_memcmp_equal=true\n";
        if (!di && std::strcmp(space,"sRGB")==0) {
            const auto default_path=render(gpu,colour,blob,cache,params,source,nullptr);
            const auto unknown_path=render(gpu,colour,blob,cache,params,source,"invalid");
            exact("default fusion disabled",old,default_path,old.size(),false);
            exact("unknown fusion mode disabled",old,unknown_path,old.size(),false);
            std::cout<<"default_and_unknown_modes_use_separated_path=true\n";
        }
    }
    mode("off");
}
}

int main(int argc,char** argv) {
    try {
        if(argc!=3) throw std::runtime_error("usage: vulkan_fusion SHADER_DIRECTORY RESOURCES_DIRECTORY");
        std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1],error));
        require(bool(gpu),error);
        std::string math;
        require(gpu->check_math_mode(math),math);
        std::cout<<"backend="<<gpu->device_name()<<" math="<<math<<'\n';
        for(uint32_t cap:{0u,1u}) for(uint32_t knots:{2u,3u,17u,257u})
            for(uint32_t n:{1u,255u,256u,257u,769u,4097u}) dir_case(gpu.get(),n,knots,cap);
        std::cout<<"DIR passed cases="<<cases<<" floats="<<compared<<'\n';
        spk::Blob blob;
        spk::Colour colour;
        require(blob.open(std::string(argv[2])+"/spektrafilm_constants.bin",error)&&colour.init(blob,error),error);
        const std::array<const char*,6> spaces={"sRGB","Display P3","ProPhoto RGB","Adobe RGB (1998)",
                                              "ITU-R BT.709","ITU-R BT.2020"};
        for(const char* space:spaces) {
            const auto tables=gamut_tables(gpu.get(),colour,space);
            for(uint32_t cap:{0u,1u}) for(bool lc:{false,true}) for(size_t knee:{0u,1u,2u})
                for(uint32_t n:{1u,255u,256u,257u,769u,4097u})
                    gamut_case(gpu.get(),tables,space,n,lc,knee,cap);
            std::cout<<"XYZ/CAM16 space="<<space<<" passed\n";
        }
        render_cases(gpu.get(),colour,blob,argv[2],spaces);
        std::cout<<"PASS cases="<<cases<<" float32_values="<<compared<<" nonfinite_reference_values="<<nonfinite
                 <<" tail_guards=true dispatch_chunks_tested=true actual_2d_dispatch=false\n";
        return 0;
    } catch(const std::exception& e) {
        std::cerr<<"FAIL: "<<e.what()<<'\n';return 1;
    }
}
