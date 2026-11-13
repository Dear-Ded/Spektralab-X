// Co-created with GPT (OpenAI). Test the real LibRaw one-shot owner transfer.
#include <libraw/libraw.h>
#include <chrono>
#include <cstring>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <vector>

namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
class Probe : public LibRaw {
public:
    void prepare() { raw2image_start(); }
    bool move() { return spk_move_rawdata_to_image(); }
    void fuji() { libraw_internal_data.internal_output_params.fuji_width = 1; }
    void shrink() { libraw_internal_data.internal_output_params.shrink = 1; }
    void other_decoder() { load_raw = nullptr; }
};
void configure(LibRaw& raw, bool custom) {
    auto& p = raw.imgdata.params;
    p.user_qual = 3; p.output_color = 4; p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1; p.no_auto_bright = 1;
    p.adjust_maximum_thr = 0; p.bright = 1;
    p.use_camera_wb = custom ? 0 : 1; p.use_auto_wb = 0;
    p.highlight = 0; p.med_passes = 0;
    if (custom) {
        p.user_mul[0] = 2; p.user_mul[1] = 1;
        p.user_mul[2] = 1.5f; p.user_mul[3] = 1;
    }
}
void open(Probe& raw, const char* file, bool custom = false) {
    configure(raw, custom);
    require(raw.open_file(file) == 0 && raw.unpack() == 0, "open/unpack");
}
using Image = std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)>;
Image bitmap(LibRaw& raw) {
    int status = 0;
    Image result(raw.dcraw_make_mem_image(&status), &LibRaw::dcraw_clear_mem);
    require(status == 0 && result && result->bits == 16 && result->colors == 3, "bitmap");
    return result;
}
bool same(const Image& a, const Image& b) {
    return a->type == b->type && a->width == b->width && a->height == b->height &&
        a->colors == b->colors && a->bits == b->bits && a->data_size == b->data_size &&
        std::memcmp(a->data, b->data, a->data_size) == 0;
}
void guards(const char* file) {
    const std::vector<std::function<void(Probe&)>> reject = {
        [](auto& r) { r.imgdata.image = r.imgdata.rawdata.color4_image; },
        [](auto& r) { r.imgdata.progress_flags = 0; },
        [](auto& r) { r.imgdata.progress_flags |= LIBRAW_PROGRESS_RAW2_IMAGE; },
        [](auto& r) { r.imgdata.idata.dng_version = 0; },
        [](auto& r) { r.imgdata.idata.filters = 0x94949494; },
        [](auto& r) { r.imgdata.idata.colors = 4; },
        [](auto& r) { r.imgdata.idata.is_foveon = 1; },
        [](auto& r) { r.shrink(); }, [](auto& r) { r.fuji(); },
        [](auto& r) { r.imgdata.sizes.pixel_aspect = 1.2; },
        [](auto& r) { r.imgdata.sizes.left_margin = 1; },
        [](auto& r) { r.imgdata.sizes.top_margin = 1; },
        [](auto& r) { r.imgdata.sizes.width = 0; },
        [](auto& r) { r.imgdata.sizes.height = 0; },
        [](auto& r) { --r.imgdata.sizes.raw_width; },
        [](auto& r) { --r.imgdata.sizes.raw_height; },
        [](auto& r) { --r.imgdata.sizes.iwidth; },
        [](auto& r) { --r.imgdata.sizes.iheight; },
        [](auto& r) { r.imgdata.sizes.raw_pitch += 8; },
        [](auto& r) { r.other_decoder(); },
        [](auto& r) { r.imgdata.process_warnings |= LIBRAW_WARN_RAWSPEED_PROCESSED; },
        [](auto& r) { r.imgdata.process_warnings |= LIBRAW_WARN_RAWSPEED3_PROCESSED; },
        [](auto& r) { r.imgdata.rawdata.raw_alloc = nullptr; },
        [](auto& r) { ++r.imgdata.rawdata.color4_image; },
        [](auto& r) { r.imgdata.rawdata.raw_image = reinterpret_cast<ushort*>(r.imgdata.rawdata.raw_alloc); },
        [](auto& r) { r.imgdata.rawdata.color3_image = reinterpret_cast<ushort(*)[3]>(r.imgdata.rawdata.raw_alloc); },
        [](auto& r) { r.imgdata.rawdata.float_image = reinterpret_cast<float*>(r.imgdata.rawdata.raw_alloc); },
        [](auto& r) { r.imgdata.rawdata.float3_image = reinterpret_cast<float(*)[3]>(r.imgdata.rawdata.raw_alloc); },
        [](auto& r) { r.imgdata.rawdata.float4_image = reinterpret_cast<float(*)[4]>(r.imgdata.rawdata.raw_alloc); },
    };
    for (const auto& mutate : reject) {
        auto raw = std::make_unique<Probe>(); open(*raw, file); raw->prepare();
        auto saved = std::make_unique<libraw_data_t>();
        std::memcpy(saved.get(), &raw->imgdata, sizeof(*saved));
        mutate(*raw);
        auto before = std::make_unique<libraw_data_t>();
        std::memcpy(before.get(), &raw->imgdata, sizeof(*before));
        const bool moved = raw->move();
        const bool unchanged = std::memcmp(before.get(), &raw->imgdata, sizeof(*before)) == 0;
        std::memcpy(&raw->imgdata, saved.get(), sizeof(*saved));
        require(!moved && unchanged, "ineligible owner/geometry mutated");
    }
    auto raw = std::make_unique<Probe>(); open(*raw, file); raw->prepare();
    void* owner = raw->imgdata.rawdata.raw_alloc;
    require(owner && owner == raw->imgdata.rawdata.color4_image, "fixture must have owned color4");
    const size_t bytes = size_t(raw->imgdata.sizes.width) * raw->imgdata.sizes.height * 8;
    std::vector<unsigned char> pixels(bytes);
    std::memcpy(pixels.data(), owner, bytes);
    require(raw->move() && raw->imgdata.image == owner && !raw->imgdata.rawdata.raw_alloc &&
        !raw->imgdata.rawdata.color4_image, "owner not moved exactly once");
    require(std::memcmp(pixels.data(), raw->imgdata.image, bytes) == 0, "transfer changed samples");
    require(!raw->move() && raw->raw2image() == LIBRAW_OUT_OF_ORDER_CALL, "moved source reused");
    raw->free_image(); raw->recycle();
    std::cout << "PASS guards=" << reject.size() << ", base transferred, raw views ended, exact samples, cleanup\n";
}
void fixture(const char* file, bool custom, bool crop) {
    auto normal = std::make_unique<Probe>(); auto once = std::make_unique<Probe>();
    for (auto* raw : {normal.get(), once.get()}) {
        open(*raw, file, custom);
        if (crop) {
            raw->imgdata.params.cropbox[0] = 2; raw->imgdata.params.cropbox[1] = 2;
            raw->imgdata.params.cropbox[2] = 48; raw->imgdata.params.cropbox[3] = 36;
        }
    }
    require(normal->dcraw_process() == 0 && once->spk_dcraw_process_once() == 0, "process");
    const bool moved = !once->imgdata.rawdata.raw_alloc;
    require(!crop || !moved, "cropped source transferred");
    auto a = bitmap(*normal), b = bitmap(*once);
    require(same(a, b), "one-shot changed RGB16 output");
    auto c = bitmap(*once); require(same(a, c), "repeat bitmap");
    require(normal->dcraw_process() == 0, "normal API lost reusable raw source");
    auto d = bitmap(*normal); require(same(a, d), "normal API reprocess changed");
    if (moved) require(once->spk_dcraw_process_once() == LIBRAW_OUT_OF_ORDER_CALL, "one-shot reprocessed consumed source");
    else once->spk_release_rawdata();
    once->recycle(); open(*once, file, custom);
    if (!crop) {
        require(once->spk_dcraw_process_once() == 0, "reopen once");
        auto e = bitmap(*once); require(same(a, e), "reopen pixels");
    }
    once.reset(); require(same(a, b), "bitmap ownership");
    std::cout << "PASS " << file << ",custom=" << custom << ",crop=" << crop << ",moved=" << moved
        << ",RGB16_exact,normal_reusable,repeat_bitmap,reopen,independent_bitmap\n";
}
void phase(const char* name) {
    std::ifstream status("/proc/self/status"); std::string line; long rss = -1;
    while (std::getline(status, line)) if (line.rfind("VmRSS:", 0) == 0) rss = std::stol(line.substr(6));
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::cout << "phase=" << name << ",rss_kib=" << rss << ",peak_kib=" << usage.ru_maxrss << '\n';
}
void measure(const char* file, bool once) {
    const auto start = std::chrono::steady_clock::now();
    auto raw = std::make_unique<Probe>(); open(*raw, file); phase("unpack");
    require((once ? raw->spk_dcraw_process_once() : raw->dcraw_process()) == 0, "measure process");
    phase("process"); raw->spk_release_rawdata(); phase("release");
    auto image = bitmap(*raw); phase("bitmap"); raw.reset(); phase("raw_destroyed");
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < image->data_size; ++i) { hash ^= image->data[i]; hash *= 0x100000001b3ull; }
    std::cout << "once=" << once << ",ms=" << ms << ",fnv1a=" << std::hex << hash << '\n';
}
}
int main(int argc, char** argv) {
    try {
        require(argc > 1, "supply synthetic integer LinearRaw first, then other fixtures");
        if (argc == 3 && std::string(argv[1]).rfind("--measure-", 0) == 0) {
            const std::string mode = argv[1];
            require(mode == "--measure-copy" || mode == "--measure-move", "measure mode");
            measure(argv[2], mode == "--measure-move");
        } else {
            guards(argv[1]);
            for (int i = 1; i < argc; ++i) for (bool custom : {false, true})
                for (bool crop : {false, true}) fixture(argv[i], custom, crop);
        }
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
