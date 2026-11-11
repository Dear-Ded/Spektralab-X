// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Production striped cancellation must not change the next frame's coordinates.
// Co-created with GPT (OpenAI), 2026-11-11. See NOTICE for project terms.
// Compiled with -fno-access-control; no production fault injection hooks.
#include "pipeline/pipeline.hpp"
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
unsigned cases = 0;
// Use real Vulkan allocations/transfers; stub DI maths to isolate publication.
// Fail one explicitly observed pipeline flush without production fault hooks.
class FailFlush final : public spk::gpu::Gpu {
    using Buffer = spk::gpu::Buffer;
    using Ref = spk::gpu::BufferRef;
    spk::gpu::Gpu* real_;
public:
    unsigned flushes = 0, fail_at = 0, di_dispatches = 0;
    explicit FailFlush(spk::gpu::Gpu* real) : real_(real) {}
    std::string device_name() const override { return real_->device_name(); }
    bool check_math_mode(std::string& e) override { return real_->check_math_mode(e); }
    void begin_frame() override { real_->begin_frame(); }
    void end_frame() override { real_->end_frame(); }
    Ref alloc(size_t n, std::string& e) override { return real_->alloc(n,e); }
    Ref alloc_zeroed(size_t n, std::string& e) override { return real_->alloc_zeroed(n,e); }
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
    bool copy(Buffer* d,size_t doff,Buffer* s,size_t soff,size_t n,std::string& e) override { return real_->copy(d,doff,s,soff,n,e); }
    bool dispatch(const char* k,const std::vector<spk::gpu::Arg>& a,size_t n,std::string& e) override {
        if (!std::strcmp(k,"spk_spectral_epilogue") || !std::strcmp(k,"spk_curves") || !std::strcmp(k,"spk_di_encode")) {
            ++di_dispatches; return true;
        }
        return real_->dispatch(k,a,n,e);
    }
    bool flush(std::string& e) override {
        if (++flushes == fail_at) { e = "injected phase flush failure"; return false; }
        return real_->flush(e);
    }
    void* texture(Buffer* b,uint32_t w,uint32_t h,uint32_t r,std::string& e) override { return real_->texture(b,w,h,r,e); }
    void release_texture(void* t) override { real_->release_texture(t); }
    uint32_t texture_row_alignment_px() const override { return real_->texture_row_alignment_px(); }
    uint32_t max_texture_dimension_2d() const override { return real_->max_texture_dimension_2d(); }
    spk::gpu::PoolStats pool_stats() const override { return real_->pool_stats(); }
    size_t trim_pool(size_t n) override { return real_->trim_pool(n); }
    bool take_critical_pressure() override { return real_->take_critical_pressure(); }
};
void check(bool ok, const std::string& what) {
    ++cases;
    if (!ok) throw std::runtime_error(what);
}
void configure(spk::Pipeline& p) {
    auto& s = p.params_.camera.scene_latitude;
    s.active = true; s.highlight_room = 2; s.shadow_room = 0;
    s.b_active = true; s.b_highlight_room = 0; s.b_shadow_room = 2;
    s.split = .5;
    p.rgb_to_xyz_ae_.m[1][0] = .2126;
    p.rgb_to_xyz_ae_.m[1][1] = .7152;
    p.rgb_to_xyz_ae_.m[1][2] = .0722;
}
std::vector<float> scene(spk::Pipeline& p, const spk::Image& input, std::string& error) {
    spk::Image out;
    check(p.node_scene_latitude(input, out, error), error);
    std::vector<float> result(input.pixels() * 3);
    check(p.gpu_->read(out.buf.get(), 0, result.data(), result.size()*sizeof(float), error), error);
    return result;
}
}

int main(int argc, char** argv) {
    try {
        check(argc == 2 || argc == 3, "shader directory required");
        std::string error;
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(argv[1], error));
        check(bool(gpu), error);
        spk::Image input;
        input.w = 7; input.h = 31; input.c = 3;
        std::vector<float> pixels(input.pixels()*3);
        for (size_t i=0; i<pixels.size(); ++i) pixels[i] = i%2 ? 4.f : .001f;
        input.buf = gpu->upload(pixels.data(), pixels.size()*sizeof(float), error);
        check(bool(input.buf), error);

        if (argc == 2) {
        for (uint32_t row : {0u, 9u}) {
            spk::Pipeline recovered(gpu.get(), nullptr, nullptr, nullptr);
            spk::Pipeline fresh(gpu.get(), nullptr, nullptr, nullptr);
            configure(recovered); configure(fresh);
            spk::Progress cancelled;
            cancelled.cancelled = true;
            recovered.progress_ = &cancelled;
            const spk::Pipeline::Stage stage = {"print_output", spk::Pipeline::StageClass::Pointwise,
                                                &spk::Pipeline::print_output, nullptr};
            spk::Pipeline::Chain chain; chain.cur = input;
            spk::Progress::StripRun report;
            const std::vector<spk::StripSpan> plan = {{row, 5}};
            check(!recovered.run_stages_striped(&stage, 1, chain, plan, report, error),
                  "cancelled production stage unexpectedly succeeded");
            check(error == "render cancelled", "wrong stage cancellation failure");
            recovered.progress_ = nullptr;
            // Change the portrait height to expose stale frame_h even when row0 was zero.
            spk::Image next = input; next.h = 23;
            auto a = scene(recovered, next, error), b = scene(fresh, next, error);
            check(a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size()*sizeof(float)) == 0,
                  "next full-frame Scene Latitude differs after striped cancellation");
            check(recovered.band_row0_ == 0 && recovered.strip_plane_h_ == 0,
                  "strip coordinates survived cancellation");
        }

        spk::Pipeline p(gpu.get(), nullptr, nullptr, nullptr);
        p.built_ = true;
        for (bool striped : {false, true}) {
            spk::Progress cancelled; cancelled.cancelled = true; cancelled.done = true;
            spk::Image out;
            bool ok = striped ? p.run_film_striped(input, out, &cancelled, error)
                              : p.run_film(input, out, &cancelled, error);
            check(!ok && error == "render cancelled", "film cancellation failure changed");
            check(p.progress_ == nullptr, "film retained caller progress after failure");
            check(!cancelled.done, "cancelled film retained previous done state");
            p.mask_on_ = true; p.mask_coef_ = input.buf; p.band_row0_ = 8;
            p.pixel_size_um_ = 0;
            spk::Progress progress; progress.done = true;
            ok = striped ? p.run_print_striped(input, out, &progress, error)
                         : p.run_print(input, out, &progress, error);
            check(!ok, "print accepted missing film pitch");
            check(p.progress_ == nullptr && !p.mask_on_ && !p.mask_coef_ && p.band_row0_ == 0,
                  "print retained progress or mask after early failure");
            check(!progress.done, "failed print was marked done");
            p.built_ = false;
            progress.done = true;
            ok = striped ? p.run_film_striped(input, out, &progress, error)
                         : p.run_film(input, out, &progress, error);
            check(!ok && !progress.done, "unbuilt film retained previous done state");
            progress.done = true;
            ok = striped ? p.run_print_striped(input, out, &progress, error)
                         : p.run_print(input, out, &progress, error);
            check(!ok && !progress.done, "unbuilt print retained previous done state");
            p.built_ = true;
        }
        }
        spk::Image large;
        large.w = 1024; large.h = 5462; large.c = 3;
        large.buf = gpu->alloc_persistent(large.bytes(), error);
        check(bool(large.buf), error);
        for (bool striped : {false, true}) {
            FailFlush proxy(gpu.get());
            // Count the semantic DI and glare node flushes as well as the
            // prefix/crossing flushes, so the injection reaches final trim.
            proxy.fail_at = striped ? 7 : 4;
            spk::Pipeline print(&proxy, nullptr, nullptr, nullptr);
            print.built_ = true; print.pixel_size_um_ = 1; print.di_active_ = true;
            spk::Image out = input;
            spk::Progress progress; progress.done = true;
            bool ok = striped ? print.run_print_striped(large, out, &progress, error)
                              : print.run_print(large, out, &progress, error);
            check(!ok && error == "injected phase flush failure" && proxy.flushes == proxy.fail_at && proxy.di_dispatches == 3,
                  "phase flush failure was not reached");
            check(out.buf.get() == input.buf.get() && out.w == input.w && out.h == input.h && out.c == input.c,
                  "failed phase flush replaced caller output");
            check(!progress.done && print.progress_ == nullptr && !print.mask_on_ && !print.mask_coef_,
                  "failed phase flush retained completion state");
        }
        std::cout << "PASS production pipeline recovery: " << cases << " checks\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL " << e.what() << '\n'; return 1;
    }
}
