// Real OutputTransform/Layer2 integration, synthetic pixels only. See NOTICE.
#include "output_transform.hpp"
#include "raw_development.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using namespace spk::android;
namespace {
void check(bool pass, const char* message) { if (!pass) throw std::runtime_error(message); }
float encode(double x) { return float(x < 1.0/512.0 ? 16*x : std::pow(x, 1.0/1.8)); }
RawDevelopmentMetadata metadata(bool embedded) {
    RawDevelopmentMetadata m;
    m.default_black_render_present = true; m.default_black_render = 1;
    m.baseline_exposure_present = true; m.baseline_exposure = .25;
    if (embedded) {
        m.profile_tone_curve_present = true;
        m.profile_tone_curve = {{{0,0}}, {{.15f,.03f}}, {{.4f,.3f}}, {{.7f,.8f}}, {{1,.997156f}}};
    }
    return m;
}
Layer2 spatial_look() {
    std::vector<float> p(kLookSize, 0);
    p[kExposure] = -.4f; p[kContrast] = 17; p[kBrightness] = 9;
    p[kVignette] = -37; p[kVignetteMid] = 43;
    for (int i = 0; i < 5; ++i) p.insert(p.end(), {2, 0, 0, 1, 1});
    p.insert(p.end(), {9157, 1, 55, 17, 30, 60, 35, 23891, 1});
    std::array<float,22> mask{};
    mask[0] = 1; mask[2] = .7f; mask[3] = .4f; mask[4] = 11; mask[6] = -17;
    mask[13] = .21f; mask[14] = .19f; mask[15] = .78f; mask[16] = .83f;
    mask[17] = .33f; mask[18] = .17f; mask[19] = 23; mask[20] = .6f;
    p.insert(p.end(), mask.begin(), mask.end());
    return Layer2(p);
}
template<class F> void cancelled(F&& f) {
    bool caught = false;
    try { f(); } catch (const std::runtime_error& e) { caught = std::string(e.what()) == "render cancelled"; }
    check(caught, "missing cancellation classification");
}

size_t parity(spk_engine* engine) {
    const auto look = spatial_look();
    check(bool(look.frame(73,97).placed), "spatial look fixture missing");
    size_t cases = 0;
    for (const char* target : {"sRGB", "Display P3"}) for (bool exporting : {false,true}) {
        OutputTransform xf; std::string error;
        check(xf.fetch(engine, target, exporting, error), error.c_str());
        for (const auto wh : {std::array<uint32_t,2>{1,1}, {7,33}, {73,97}, {97,73}}) {
            const auto [w,h] = wh;
            const uint32_t stride = w+7;
            std::vector<float> linear(size_t(stride)*h*3, -123.f);
            for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
                const size_t i = (size_t(y)*stride+x)*3;
                linear[i] = float(std::exp2(-16.0 + ((x+7*y)%191)/11.0));
                linear[i+1] = float(std::exp2(-13.0 + ((x*13+y)%199)/13.0));
                linear[i+2] = (x+y)%11 == 0 ? -.07f : float(std::exp2(-14.0 + ((x+3*y)%211)/12.0));
            }
            const auto stats = raw_development_stats(linear.data(),stride,w,h);
            std::vector<float> source = linear;
            for (float& value : source) value = encode(value);
            const auto original = source;
            const std::array<RawDevelopment,3> plans{
                RawDevelopment::build({},stats,{}),
                RawDevelopment::build(metadata(false),stats,{true,true,-1.25f}),
                RawDevelopment::build(metadata(true),stats,{true,true,-1.25f})};
            for (const auto& plan : plans) for (const Layer2* grade : {static_cast<const Layer2*>(nullptr), &look}) {
                std::vector<uint8_t> full8(size_t(w)*h*4), oracle8(full8.size());
                std::vector<uint16_t> full16, oracle16;
                pack_float_rgba8(source.data(),stride,w,h,full8.data(),w*4,grade,xf,{},&plan);
                pack_float_rgba16(source.data(),stride,w,h,full16,grade,xf,{},&plan);
                // Independently develop one source copy, then use the old
                // no-plan packing path: catches placement after Layer2 or twice.
                auto developed = source;
                for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x)
                    plan.apply_encoded_prophoto(developed.data()+(size_t(y)*stride+x)*3);
                pack_float_rgba8(developed.data(),stride,w,h,oracle8.data(),w*4,grade,xf);
                pack_float_rgba16(developed.data(),stride,w,h,oracle16,grade,xf);
                check(full8 == oracle8 && full16 == oracle16, "development placement differs from before-Layer2 oracle");
                const Rgba8Rows rows8(source.data(),stride,w,h,grade,&xf,&plan);
                const Rgba16Rows rows16(source.data(),stride,w,h,grade,&xf,&plan);
                const auto frame = grade ? grade->frame(w,h) : Layer2::Frame{};
                for (uint32_t step : {1u,3u,32u,65u}) {
                    std::vector<uint8_t> assembled8(full8.size()), borrowed8(full8.size()), scratch8;
                    std::vector<uint16_t> assembled16(full16.size()), scratch16;
                    // Out-of-order row requests must keep complete-frame masks.
                    for (uint32_t end = h; end;) {
                        const uint32_t n = std::min(step,end), y = end-n; end=y;
                        rows8.pack(y,n,scratch8); rows16.pack(y,n,scratch16);
                        std::copy(scratch8.begin(),scratch8.end(),assembled8.begin()+size_t(y)*w*4);
                        std::copy(scratch16.begin(),scratch16.end(),assembled16.begin()+size_t(y)*w*4);
                        std::vector<float> strip(size_t(stride)*n*3);
                        std::copy_n(source.data()+size_t(y)*stride*3, strip.size(), strip.data());
                        Rgba8Rows borrowed(strip.data(),stride,w,h,y,n,grade,&xf,&frame,&plan);
                        borrowed.pack(y,n,scratch8);
                        std::copy(scratch8.begin(),scratch8.end(),borrowed8.begin()+size_t(y)*w*4);
                    }
                    check(full8 == assembled8 && full8 == borrowed8 && full16 == assembled16,
                          "whole/row/borrowed-strip output bytes differ");
                }
                check(source == original, "packing mutated original float cache");
                ++cases;
            }
        }
    }
    return cases;
}

struct CancelLut final : ColourTable {
    std::atomic<uint64_t>& generation;
    mutable std::atomic<unsigned> calls{0};
    mutable std::atomic<bool> armed{false};
    explicit CancelLut(std::atomic<uint64_t>& g) : generation(g) {}
    void apply(float&,float&,float&) const override {
        if (calls.fetch_add(1,std::memory_order_relaxed) == 7000 && armed.exchange(false))
            generation.fetch_add(1,std::memory_order_relaxed);
    }
    void arm() { calls.store(0); armed.store(true); }
};

void recovery_and_hdr(spk_engine* engine) {
    OutputTransform xf; std::string error;
    check(xf.fetch(engine,"sRGB",false,error),error.c_str());
    auto m = metadata(true); m.baseline_exposure_present=false;
    m.profile_tone_curve = {{{0,0}},{{1,1}}};
    const auto restore = RawDevelopment::build(m,{}, {true,false,-3});
    float hdr[] = {encode(4),encode(2),encode(1)};
    float reference[] = {encode(.5),encode(.25),encode(.125)};
    std::vector<uint8_t> hdr8(4), expected8(4);
    pack_float_rgba8(hdr,1,1,1,hdr8.data(),4,nullptr,xf,{},&restore);
    pack_float_rgba8(reference,1,1,1,expected8.data(),4,nullptr,xf);
    check(hdr8 == expected8, "negative RAW EV failed highlight recovery through actual output transform");
    constexpr uint32_t w=811,h=677;
    std::vector<float> source(size_t(w)*h*3);
    for (size_t i=0;i<source.size();++i) source[i]=float(std::exp2(-12+(i%199)/16.0));
    const auto stats = raw_development_stats(source.data(),w,w,h);
    for (float& value : source) value=encode(value);
    const auto original = source;
    const auto plan = RawDevelopment::build(metadata(false),stats,{true,true,0});
    std::atomic<uint64_t> generation{0};
    auto lut=std::make_shared<CancelLut>(generation);
    Layer2 look(std::vector<float>(kLookSize,0)); look.set_lut(lut,1);
    std::vector<uint8_t> plain8(size_t(w)*h*4), interrupted8(plain8.size());
    std::vector<uint16_t> plain16, interrupted16;
    pack_float_rgba8(source.data(),w,w,h,plain8.data(),w*4,&look,xf,{},&plan);
    pack_float_rgba16(source.data(),w,w,h,plain16,&look,xf,{},&plan);
    {
        const OutputCancel token(generation); lut->arm();
        cancelled([&] { pack_float_rgba8(source.data(),w,w,h,interrupted8.data(),w*4,&look,xf,token,&plan); });
    }
    {
        const OutputCancel fresh(generation);
        pack_float_rgba8(source.data(),w,w,h,interrupted8.data(),w*4,&look,xf,fresh,&plan);
        check(plain8 == interrupted8,"RGBA8 cancelled-recovery bytes differ");
    }
    {
        const OutputCancel token(generation); lut->arm();
        cancelled([&] { pack_float_rgba16(source.data(),w,w,h,interrupted16,&look,xf,token,&plan); });
    }
    {
        const OutputCancel fresh(generation);
        pack_float_rgba16(source.data(),w,w,h,interrupted16,&look,xf,fresh,&plan);
        check(plain16 == interrupted16,"RGBA16 cancelled-recovery bytes differ");
    }
    const Rgba8Rows rows8(source.data(),w,w,h,&look,&xf,&plan);
    const Rgba16Rows rows16(source.data(),w,w,h,&look,&xf,&plan);
    const OutputCancel stale(generation); generation.fetch_add(1);
    std::vector<uint8_t> scratch8{23,45}; std::vector<uint16_t> scratch16{2345,4567};
    cancelled([&] { rows8.pack(11,7,scratch8,stale); });
    cancelled([&] { rows16.pack(11,7,scratch16,stale); });
    check(scratch8 == std::vector<uint8_t>({23,45}) && scratch16 == std::vector<uint16_t>({2345,4567}),
          "pre-cancelled row pack changed storage");
    check(source == original,"cancelled output mutated source");
}
}  // namespace

int main(int argc,char** argv) {
    try {
        check(argc==2,"resources path required");
        std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1],nullptr),spk_engine_destroy);
        check(bool(engine),"engine create failed");
        const size_t cases=parity(engine.get());
        recovery_and_hdr(engine.get());
        std::cout << "RAW_DEVELOPMENT_OUTPUT_PASS cases=" << cases
                  << " targets=sRGB,P3 transforms=display,export bitdepths=8,16 steps=1,3,32,65"
                  << " whole_rows=equal borrowed8=equal neutral_legacy=equal source=immutable HDR=restored cancelled=recovered\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
