// Co-created with GPT (OpenAI). Real LibRaw storage ownership and phase RSS.
// Inputs are public synthetic DNGs; no alternate image-processing kernel.
#include <libraw/libraw.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <fstream>
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
using Image = std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)>;
Image bitmap(LibRaw& raw) {
    int status = 0;
    Image result(raw.dcraw_make_mem_image(&status), &LibRaw::dcraw_clear_mem);
    require(status == 0 && result && result->type == LIBRAW_IMAGE_BITMAP &&
        result->bits == 16 && result->colors == 3, "RGB16 bitmap");
    return result;
}
bool same(const Image& a, const Image& b) {
    return a->type == b->type && a->width == b->width && a->height == b->height &&
        a->colors == b->colors && a->bits == b->bits && a->data_size == b->data_size &&
        std::memcmp(a->data, b->data, a->data_size) == 0;
}
void configure(LibRaw& raw, bool custom) {
    auto& p = raw.imgdata.params;
    p.user_qual = 3;
    p.output_color = 4;
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1;
    p.no_auto_bright = 1;
    p.adjust_maximum_thr = 0;
    p.bright = 1;
    p.use_camera_wb = custom ? 0 : 1;
    p.use_auto_wb = 0;
    p.highlight = 0;
    if (custom) {
        p.user_mul[0] = 2; p.user_mul[1] = 1;
        p.user_mul[2] = 1.5f; p.user_mul[3] = 1;
    }
}
void no_release(LibRaw& raw, const char* message) {
    std::vector<unsigned char> before(sizeof(raw.imgdata));
    std::memcpy(before.data(), &raw.imgdata, before.size());
    require(!raw.spk_release_rawdata() &&
        std::memcmp(before.data(), &raw.imgdata, before.size()) == 0, message);
}
void expected_released(libraw_data_t& data) {
    data.rawdata.raw_alloc = nullptr;
    data.rawdata.raw_image = nullptr;
    data.rawdata.color3_image = nullptr;
    data.rawdata.color4_image = nullptr;
    data.rawdata.float_image = nullptr;
    data.rawdata.float3_image = nullptr;
    data.rawdata.float4_image = nullptr;
}
void guards() {
    auto raw = std::make_unique<LibRaw>();
    no_release(*raw, "empty instance mutated");
    raw->imgdata.image = static_cast<unsigned short (*)[4]>(std::calloc(4, 8));
    raw->imgdata.rawdata.raw_alloc = std::malloc(128);
    raw->imgdata.rawdata.raw_image = static_cast<unsigned short*>(raw->imgdata.rawdata.raw_alloc);
    no_release(*raw, "pre-conversion storage released");
    raw->imgdata.progress_flags = LIBRAW_PROGRESS_CONVERT_RGB;
    for (const unsigned warning : {LIBRAW_WARN_RAWSPEED_PROCESSED, LIBRAW_WARN_RAWSPEED3_PROCESSED}) {
        raw->imgdata.process_warnings = warning;
        no_release(*raw, "external decoder storage released");
    }
    raw->imgdata.process_warnings = 0;
    std::free(raw->imgdata.rawdata.raw_alloc);
    raw->imgdata.rawdata.raw_alloc = raw->imgdata.image;
    raw->imgdata.rawdata.raw_image = reinterpret_cast<unsigned short*>(raw->imgdata.image);
    no_release(*raw, "working image alias released");
    expected_released(raw->imgdata);
    raw->free_image();
    raw->recycle();
    std::cout << "PASS guards: empty, order, RawSpeed2/3, image_alias, cleanup\n";
}
void fixture(const char* file, bool custom) {
    auto reference = std::make_unique<LibRaw>();
    auto candidate = std::make_unique<LibRaw>();
    for (auto* raw : {reference.get(), candidate.get()}) {
        configure(*raw, custom);
        require(raw->open_file(file) == 0, "open fixture");
        no_release(*raw, "open-only state changed");
        require(raw->unpack() == 0, "unpack fixture");
        no_release(*raw, "unpacked raw pixels released early");
        require(raw->dcraw_process() == 0, "process fixture");
    }
    auto expected = std::make_unique<libraw_data_t>();
    std::memcpy(expected.get(), &candidate->imgdata, sizeof(*expected));
    expected_released(*expected);
    require(candidate->spk_release_rawdata(), "owned raw pixels not released");
    require(std::memcmp(expected.get(), &candidate->imgdata, sizeof(*expected)) == 0,
        "release changed image, progress or metadata");
    no_release(*candidate, "repeated release changed state");
    auto a = bitmap(*reference), b = bitmap(*candidate);
    require(same(a, b), "release changed oriented gamma RGB16 bytes");
    require(candidate->raw2image() == LIBRAW_OUT_OF_ORDER_CALL &&
        candidate->dcraw_process() == LIBRAW_OUT_OF_ORDER_CALL, "discarded source reprocessed");
    auto c = bitmap(*candidate);
    require(same(b, c), "repeated bitmap changed");
    candidate->free_image();
    no_release(*candidate, "release after free_image mutated state");
    candidate->recycle();
    configure(*candidate, custom);
    require(candidate->open_file(file) == 0 && candidate->unpack() == 0 &&
        candidate->dcraw_process() == 0 && candidate->spk_release_rawdata(), "reopen after recycle");
    auto d = bitmap(*candidate);
    require(same(a, d), "reopen pixels changed");
    candidate->recycle();
    require(same(a, d), "bitmap lost independent ownership");
    std::cout << "PASS " << file << ": custom=" << custom
        << ", exact_RGB16, metadata_unchanged, repeat, reopen, independent_bitmap\n";
}
void phase(const char* label, const LibRaw* raw) {
    std::ifstream status("/proc/self/status");
    std::string key, line;
    long rss = -1;
    while (std::getline(status, line)) if (line.rfind("VmRSS:", 0) == 0)
        rss = std::stol(line.substr(6));
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    std::cout << "phase=" << label << ",rss_kib=" << rss << ",peak_kib=" << usage.ru_maxrss
        << ",raw_owned=" << (raw && raw->imgdata.rawdata.raw_alloc != nullptr)
        << ",working_owned=" << (raw && raw->imgdata.image != nullptr) << '\n';
}
void measure(const char* file, bool release) {
    auto raw = std::make_unique<LibRaw>();
    configure(*raw, false);
    require(raw->open_file(file) == 0, "measure open"); phase("open", raw.get());
    require(raw->unpack() == 0, "measure unpack"); phase("unpack", raw.get());
    require(raw->dcraw_process() == 0, "measure process"); phase("process", raw.get());
    if (release) require(raw->spk_release_rawdata(), "measure release");
    phase("before_bitmap", raw.get());
    auto image = bitmap(*raw); phase("bitmap", raw.get());
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < image->data_size; ++i) { hash ^= image->data[i]; hash *= 0x100000001b3ull; }
    raw.reset(); phase("destroy_raw", nullptr);
    image.reset(); phase("destroy_bitmap", nullptr);
    std::cout << "release=" << release << ",fnv1a=" << std::hex << hash << '\n';
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[1]).rfind("--measure-", 0) == 0) {
            const std::string mode = argv[1];
            require(mode == "--measure-keep" || mode == "--measure-release", "measure mode");
            measure(argv[2], mode == "--measure-release");
        } else {
            require(argc > 1, "supply public synthetic fixture paths");
            guards();
            for (int i = 1; i < argc; ++i) for (bool custom : {false, true}) fixture(argv[i], custom);
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
