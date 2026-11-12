// Co-created with GPT (OpenAI). Production lens-op cancellation boundaries.
#include "io/raw_camera.hpp"
#include "io/raw_cancellation.hpp"
#include <atomic>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
using Bytes = std::vector<unsigned char>;
constexpr unsigned W = 64, H = 48;
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
void put32(Bytes& b, std::uint32_t v) {
    for (int s = 24; s >= 0; s -= 8) b.push_back(static_cast<unsigned char>(v >> s));
}
void put64(Bytes& b, double value) {
    std::uint64_t bits;
    std::memcpy(&bits, &value, sizeof bits);
    for (int s = 56; s >= 0; s -= 8) b.push_back(static_cast<unsigned char>(bits >> s));
}
void header(Bytes& b, unsigned id, unsigned size) {
    put32(b, id); put32(b, 0x01030000); put32(b, 1); put32(b, size);
}
Bytes list(unsigned kind) {
    Bytes b;
    put32(b, kind == 2 ? 2 : 1);
    if (kind != 1) {
        header(b, 1, 4 + 3 * 48 + 16); put32(b, 3);
        for (unsigned c = 0; c < 3; ++c)
            for (double k : {0.94 + c * .015, -.03, .01, 0.0, .002, -.001}) put64(b, k);
        put64(b, .37); put64(b, .62);
    }
    if (kind != 0) {
        header(b, 3, 56);
        for (double k : {.5, .25, -.1, 0.0, 0.0, .37, .62}) put64(b, k);
    }
    return b;
}
std::vector<ushort> image() {
    std::vector<ushort> p(W * H * 4);
    for (unsigned y = 0; y < H; ++y)
        for (unsigned x = 0; x < W; ++x) {
            const auto i = (y * W + x) * 4;
            for (unsigned c = 0; c < 3; ++c)
                p[i + c] = ushort((x * 911 + y * 353 + c * 7907) % 65536);
            p[i + 3] = 0xbabe;
        }
    return p;
}
int apply(std::vector<ushort>& p, const Bytes& b, spk::io::detail::RawDecodeProgress* progress = nullptr) {
#ifdef SPK_OL3_BASELINE
    (void)progress;
    return spk::io::detail::apply_dng_opcode_list3(reinterpret_cast<ushort(*)[4]>(p.data()), W, H, 3,
                                                b.data(), b.size());
#else
    return spk::io::detail::apply_dng_opcode_list3(reinterpret_cast<ushort(*)[4]>(p.data()), W, H, 3,
                                                b.data(), b.size(), progress);
#endif
}
struct Camera : spk::io::detail::CameraLibRaw {
    std::vector<ushort> pixels = image();
    Bytes opcodes;
    explicit Camera(Bytes b) : opcodes(std::move(b)) {
        auto& d = imgdata;
        d.idata.dng_version = 0x01040000; d.idata.colors = 3;
        d.sizes.iwidth = W; d.sizes.iheight = H; d.sizes.pixel_aspect = 1;
        d.image = reinterpret_cast<ushort(*)[4]>(pixels.data());
        d.rawdata.color.dng_levels.rawopcodes[2].data = opcodes.data();
        d.rawdata.color.dng_levels.rawopcodes[2].len = opcodes.size();
    }
    ~Camera() {
        imgdata.image = nullptr;
        imgdata.rawdata.color.dng_levels.rawopcodes[2].data = nullptr;
        imgdata.rawdata.color.dng_levels.rawopcodes[2].len = 0;
    }
    void run(spk::io::detail::RawDecodeProgress* progress) {
#ifndef SPK_OL3_BASELINE
        set_decode_progress(progress);
#else
        (void)progress;
#endif
        callbacks.pre_converttorgb_cb(this);
    }
};
void write(const std::string& file, const std::vector<ushort>& p) {
    std::ofstream out(file, std::ios::binary);
    out.write(reinterpret_cast<const char*>(p.data()), p.size() * sizeof(ushort));
    require(bool(out), "could not write golden pixels");
}
void boundaries(unsigned kind, const std::string& folder, bool require_cancellation) {
    using namespace spk::io;
    const auto original = image();
    const auto b = list(kind);
    auto expected = original;
    require(apply(expected, b) == (kind == 2 ? 2 : 1) && expected != original, "fixture did not transform pixels");
    for (unsigned i = 3; i < expected.size(); i += 4)
        require(expected[i] == 0xbabe, "unused fourth channel changed");
    write(folder + "/kind-" + std::to_string(kind) + ".rgb16", expected);
    int polls = 0;
    RawDecodeCancel callback = [&] { ++polls; return false; };
    detail::RawDecodeProgress progress(callback);
    auto candidate = original;
    apply(candidate, b, &progress);
    require(candidate == expected, "false callback changed pixels");
    Camera camera(b);
    int camera_polls = 0;
    RawDecodeCancel camera_callback = [&] { ++camera_polls; return false; };
    detail::RawDecodeProgress camera_progress(camera_callback);
    camera.run(&camera_progress);
    require(camera.pixels == expected, "LibRaw callback changed pixels");
    std::cout << "kind=" << kind << " helper_polls=" << polls << " camera_polls=" << camera_polls;
#ifdef SPK_OL3_BASELINE
    std::cout << " missing_cancellation=" << (polls == 0 && camera_polls == 0) << '\n';
    if (require_cancellation) require(polls >= int(H), "lens correction has no cancellation checkpoints");
#else
    (void)require_cancellation;
    require(polls >= int(H) && camera_polls == polls, "lens workers or CameraLibRaw did not observe cancellation");
    for (int stop = 1; stop <= polls; ++stop) {
        int seen = 0;
        RawDecodeCancel cancel = [&] { return ++seen >= stop; };
        detail::RawDecodeProgress probe(cancel);
        Camera cancelled(b);
        bool threw = false;
        try { cancelled.run(&probe); }
        catch (const RawDecodeCancelled&) { threw = true; }
        require(threw && seen == stop, "cancel was not sticky or did not escape joined workers");
        if (stop <= 2) require(cancelled.pixels == original, "early cancellation changed pixels");
    }
    int throwing_seen = 0;
    RawDecodeCancel throwing = [&]() -> bool {
        if (++throwing_seen == polls / 2) throw std::logic_error("worker callback");
        return false;
    };
    detail::RawDecodeProgress thrown(throwing);
    bool classified = false;
    try { apply(candidate, b, &thrown); }
    catch (const RawDecodeCancelled&) { classified = true; }
    require(classified && throwing_seen == polls / 2, "worker callback exception escaped classification");
    auto retry = original;
    apply(retry, b);
    require(retry == expected, "retry changed output");
    std::cout << " cancellation_positions=" << polls << " PASS\n";
#endif
}
} // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 2 || (argc == 3 && std::string(argv[2]) == "--require-cancellation"), "expected output directory");
        for (unsigned kind = 0; kind < 3; ++kind) boundaries(kind, argv[1], argc == 3);
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
