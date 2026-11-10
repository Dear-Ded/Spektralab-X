// Co-created with GPT (OpenAI). Real decoder cancellation and frozen-ABI parity.
#include "io/raw_decoder.hpp"
#ifndef SPK_CANCEL_BASELINE
#include "io/dng_gain_map.hpp"
#endif
#include <atomic>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>
#include <type_traits>
#include <vector>

namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
using Bytes = std::vector<unsigned char>;
template<class T> void add(Bytes& out, const T& value) {
    static_assert(std::is_arithmetic_v<T>);
    const auto* p = reinterpret_cast<const unsigned char*>(&value);
    out.insert(out.end(),p,p+sizeof value);
}
template<class T, std::size_t N> void add(Bytes& out, const std::array<T,N>& values) {
    for (const auto& value : values) add(out,value);
}
template<class T> void add(Bytes& out, const std::vector<T>& values) {
    add(out,std::uint64_t(values.size()));
    for (const auto& value : values) add(out,value);
}
void add(Bytes& out, const std::string& value) {
    add(out,std::uint64_t(value.size())); out.insert(out.end(),value.begin(),value.end());
}
Bytes metadata(const spk::io::RawMetadata& m) {
    Bytes b;
    const auto& r=m.dng_render;
    add(b,r.is_dng); add(b,r.baseline_exposure_present); add(b,r.baseline_exposure_invalid);
    add(b,r.baseline_exposure); add(b,r.baseline_exposure_offset_present);
    add(b,r.baseline_exposure_offset_invalid); add(b,r.baseline_exposure_offset);
    add(b,r.default_black_render_present); add(b,r.default_black_render_invalid);
    add(b,r.default_black_render); add(b,r.profile_tone_curve_present);
    add(b,r.profile_tone_curve_invalid); add(b,r.profile_tone_curve);
    add(b,r.extra_camera_profiles); add(b,r.profile_source_mismatch);
    add(b,m.libraw_version); add(b,m.libraw_capabilities); add(b,m.make); add(b,m.model);
    add(b,m.decoder_name);
    const auto& s=m.sizes_before_postprocess;
    add(b,s.raw_width); add(b,s.raw_height); add(b,s.width); add(b,s.height);
    add(b,s.iwidth); add(b,s.iheight); add(b,s.top_margin); add(b,s.left_margin); add(b,s.pixel_aspect);
    add(b,m.raw_flip); add(b,m.camera_whitebalance_valid); add(b,m.camera_whitebalance);
    add(b,m.camera_whitebalance_from_xy); add(b,m.camera_white_xy); add(b,m.post_scale_pre_mul);
    add(b,m.as_shot_wb_applied); add(b,m.black); add(b,m.black_level_per_channel);
    add(b,m.black_pattern_rows); add(b,m.black_pattern_cols); add(b,m.black_pattern);
    add(b,m.white_level); add(b,m.camera_white_level_per_channel); add(b,m.postprocess_black);
    add(b,m.postprocess_maximum); add(b,m.process_warnings); add(b,m.rgb_cam);
    add(b,m.colour_channels); add(b,m.camera_from_xyz); add(b,m.headroom_enabled);
    add(b,m.white_balance_exposure_restore); add(b,m.camera_to_output);
    return b;
}
Bytes pixels(const spk::io::DecodedRaw& r) {
    Bytes b; add(b,r.width); add(b,r.height); add(b,r.rgb); return b;
}
void write(const std::string& path, const Bytes& bytes) {
    std::ofstream file(path,std::ios::binary);
    file.write(reinterpret_cast<const char*>(bytes.data()),bytes.size());
    require(bool(file),"cannot write parity output");
}
#ifndef SPK_CANCEL_BASELINE
void cancellation(const char* path, const std::array<float,4>* wb, double temperature,
                  const spk::io::DecodedRaw& expected) {
    using namespace spk::io;
    const auto expected_pixels=pixels(expected), expected_metadata=metadata(expected.metadata);
    std::string error;
    int polls=0;
    DecodedRaw result;
    require(decode_raw_compatible(path,wb,result,error,temperature,[&]{++polls; return false;}),error.c_str());
    require(pixels(result)==expected_pixels && metadata(result.metadata)==expected_metadata,"false callback changed output");
    require(polls>10,"decoder progress callback not reached");
    for (int stop=1; stop<=polls; ++stop) {
        DecodedRaw sentinel;
        sentinel.width=77; sentinel.height=88; sentinel.rgb={-1,3,7};
        sentinel.metadata.make="unchanged"; sentinel.timings.total_ms=-91;
        const auto old_pixels=pixels(sentinel), old_metadata=metadata(sentinel.metadata);
        int seen=0;
        bool cancelled=false;
#ifdef _OPENMP
        const int threads=omp_get_max_threads(), dynamic=omp_get_dynamic(), levels=omp_get_max_active_levels();
#endif
        try { decode_raw_compatible(path,wb,sentinel,error,temperature,[&]{return ++seen>=stop;}); }
        catch (const RawDecodeCancelled&) { cancelled=true; }
        if (!cancelled) {
            std::cerr<<"missed cancellation position="<<stop<<" observed="<<seen<<" initial="<<polls<<" error="<<error<<'\n';
            throw std::runtime_error("cancellation was not classified");
        }
        require(pixels(sentinel)==old_pixels && metadata(sentinel.metadata)==old_metadata &&
                sentinel.timings.total_ms==-91,"cancelled decode published candidate");
#ifdef _OPENMP
        require(threads==omp_get_max_threads() && dynamic==omp_get_dynamic() &&
                levels==omp_get_max_active_levels(),"cancel changed caller OpenMP policy");
#endif
    }
    bool cancelled=false;
    try { decode_raw_compatible(path,wb,result,error,temperature,[]{throw std::runtime_error("callback failure"); return false;}); }
    catch (const RawDecodeCancelled&) { cancelled=true; }
    require(cancelled,"callback exception escaped cancellation classification");
    require(decode_raw_compatible(path,wb,result,error,temperature),error.c_str());
    require(pixels(result)==expected_pixels && metadata(result.metadata)==expected_metadata,"retry changed output");
    std::cout<<"decoder: "<<polls<<" cancellation positions, false callback, throwing callback, retry PASS\n";
}

void put32(Bytes& bytes, std::uint32_t value) {
    for (int shift=24; shift>=0; shift-=8) bytes.push_back(static_cast<unsigned char>(value>>shift));
}
void putdouble(Bytes& bytes, double value) {
    std::uint64_t bits; std::memcpy(&bits,&value,sizeof bits);
    for (int shift=56; shift>=0; shift-=8) bytes.push_back(static_cast<unsigned char>(bits>>shift));
}
struct GainFixture {
    std::unique_ptr<LibRaw> raw=std::make_unique<LibRaw>();
    std::vector<ushort> cfa=std::vector<ushort>(64*48,1000);
    Bytes opcodes;
    GainFixture() {
        auto& d=raw->imgdata;
        d.idata.dng_version=0x01040000; d.idata.filters=0x94949494;
        d.sizes.width=d.sizes.raw_width=64; d.sizes.height=d.sizes.raw_height=48;
        d.sizes.raw_pitch=64*sizeof(ushort);
        d.rawdata.raw_image=cfa.data(); d.rawdata.color.maximum=65535;
        d.rawdata.color.black=100;
        put32(opcodes,1); put32(opcodes,9); put32(opcodes,0x01030000);
        put32(opcodes,0); put32(opcodes,80);
        for (std::uint32_t v : {0u,0u,48u,64u,0u,1u,1u,1u,1u,1u}) put32(opcodes,v);
        putdouble(opcodes,1); putdouble(opcodes,1); putdouble(opcodes,0); putdouble(opcodes,0);
        put32(opcodes,1); put32(opcodes,0x40000000); // Constant 2x gain.
        d.rawdata.color.dng_levels.rawopcodes[1].data=opcodes.data();
        d.rawdata.color.dng_levels.rawopcodes[1].len=opcodes.size();
    }
    ~GainFixture() {
        raw->imgdata.rawdata.raw_image=nullptr;
        raw->imgdata.rawdata.color.dng_levels.rawopcodes[1].data=nullptr;
        raw->imgdata.rawdata.color.dng_levels.rawopcodes[1].len=0;
    }
};
void gain_and_workers() {
    using namespace spk::io;
    GainFixture baseline, candidate;
    require(detail::apply_dng_gain_maps(*baseline.raw)==1,"gain fixture not applied");
    for (auto value: baseline.cfa) require(value==1900,"constant gain reference mismatch");
    int polls=0;
    RawDecodeCancel callback=[&]{++polls;return false;};
    detail::RawDecodeProgress progress(callback);
    require(detail::apply_dng_gain_maps(*candidate.raw,&progress)==1 && candidate.cfa==baseline.cfa,
            "false gain callback changed pixels");
    require(polls>=52,"gain worker progress not reached");
    for (int stop=1;stop<=polls;++stop) {
        GainFixture fixture;
        int seen=0;
        RawDecodeCancel cancel=[&]{return ++seen>=stop;};
        detail::RawDecodeProgress probe(cancel);
        bool cancelled=false;
        try { detail::apply_dng_gain_maps(*fixture.raw,&probe); }
        catch (const RawDecodeCancelled&) { cancelled=true; }
        require(cancelled,"gain cancellation did not throw after workers joined");
        // Destruction follows the throw; ASan checks that workers no longer use CFA/opcodes.
    }
    std::atomic<int> concurrent{0}, maximum{0}, calls{0};
    RawDecodeCancel callback_threads=[&] {
        const int active=++concurrent;
        int previous=maximum.load();
        while (previous<active && !maximum.compare_exchange_weak(previous,active)) {}
        std::this_thread::yield();
        const int count=++calls;
        --concurrent;
        return count==17;
    };
    detail::RawDecodeProgress shared(callback_threads);
    std::vector<std::thread> workers;
    for (int i=0;i<8;++i) workers.emplace_back([&]{for(int j=0;j<32;++j) shared.poll();});
    for (auto& worker: workers) worker.join();
    require(maximum==1 && calls==17 && shared.poll(),"callback serialization or sticky cancellation broken");
    std::cout<<"gain map: "<<polls<<" cancellation positions, exact output; 8 workers serialized/sticky PASS\n";
}
#endif
}

int main(int argc,char** argv) {
    try {
        if (argc!=5) return 2;
        const bool half=std::string(argv[3])=="1", custom=std::string(argv[4])=="1";
        spk::io::set_raw_half_size_budget(half?1:0);
        const std::array<float,4> wb{2,1,1.5f,1};
        spk::io::DecodedRaw decoded;
        std::string error;
        require(spk::io::decode_raw_compatible(argv[1],custom?&wb:nullptr,decoded,error,custom?6500:0),error.c_str());
        write(std::string(argv[2])+".rgb",pixels(decoded));
        write(std::string(argv[2])+".metadata",metadata(decoded.metadata));
#ifndef SPK_CANCEL_BASELINE
        cancellation(argv[1],custom?&wb:nullptr,custom?6500:0,decoded);
        gain_and_workers();
#endif
        spk::io::set_raw_half_size_budget(0);
        std::cout<<"compatible RAW "<<decoded.width<<'x'<<decoded.height<<" half="<<half<<" custom="<<custom<<" PASS\n";
    } catch(const std::exception& error) { std::cerr<<error.what()<<'\n'; return 1; }
}
