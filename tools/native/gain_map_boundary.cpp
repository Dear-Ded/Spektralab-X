// Co-created with GPT (OpenAI). Synthetic GainMap parser boundary regression.
#include "io/dng_gain_map.hpp"
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
using Bytes = std::vector<unsigned char>;
void put32(Bytes& out, std::uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8)
        out.push_back(static_cast<unsigned char>(value >> shift));
}
void put_double(Bytes& out, double value) {
    std::uint64_t bits;
    std::memcpy(&bits, &value, sizeof bits);
    for (int shift = 56; shift >= 0; shift -= 8)
        out.push_back(static_cast<unsigned char>(bits >> shift));
}
void replace32(Bytes& bytes, std::size_t offset, std::uint32_t value) {
    for (int i = 0; i < 4; ++i) bytes.at(offset + i) = static_cast<unsigned char>(value >> (24 - i * 8));
}
void opcode(Bytes& out, std::uint32_t id, std::uint32_t bytes) {
    put32(out, id); put32(out, 0x01030000); put32(out, 1); put32(out, bytes);
}
void gain(Bytes& out) {
    opcode(out, 9, 80);
    for (std::uint32_t v : {0u, 0u, 2u, 2u, 0u, 1u, 1u, 1u, 1u, 1u}) put32(out, v);
    put_double(out, 1); put_double(out, 1); put_double(out, 0); put_double(out, 0);
    put32(out, 1); put32(out, 0x40000000); // Constant gain 2.
}
struct Fixture {
    std::unique_ptr<LibRaw> raw = std::make_unique<LibRaw>();
    std::vector<ushort> pixels{1000, 1000, 1000, 1000};
    Bytes bytes;
    Fixture() {
        auto& d = raw->imgdata;
        d.idata.dng_version = 0x01040000; d.idata.filters = 0x94949494;
        d.sizes.width = d.sizes.raw_width = 2;
        d.sizes.height = d.sizes.raw_height = 2;
        d.sizes.raw_pitch = 2 * sizeof(ushort);
        d.rawdata.raw_image = pixels.data();
        d.rawdata.color.black = 100; d.rawdata.color.maximum = 65535;
    }
    ~Fixture() {
        raw->imgdata.rawdata.raw_image = nullptr;
        raw->imgdata.rawdata.color.dng_levels.rawopcodes[1].data = nullptr;
        raw->imgdata.rawdata.color.dng_levels.rawopcodes[1].len = 0;
    }
    int apply() {
        auto& ops = raw->imgdata.rawdata.color.dng_levels.rawopcodes[1];
        ops.data = bytes.data(); ops.len = bytes.size();
        return spk::io::detail::apply_dng_gain_maps(*raw);
    }
    bool all(ushort value) const {
        return std::all_of(pixels.begin(), pixels.end(), [=](ushort x) { return x == value; });
    }
};
int failures = 0;
void check(const char* name, bool okay, int applied, const Fixture& f) {
    std::cout << name << ": " << (okay ? "PASS" : "FAIL") << " applied=" << applied
              << " bytes=" << f.bytes.size() << " pixels=";
    for (auto value : f.pixels) std::cout << value << ',';
    std::cout << '\n';
    if (!okay) ++failures;
}
void prefix(Fixture& f, std::uint32_t count, unsigned complete) {
    put32(f.bytes, count); gain(f.bytes);
    for (unsigned k = 1; k < complete; ++k) opcode(f.bytes, 0x7ffffffe, 0);
}
void lists() {
    {
        Fixture f; prefix(f, 1, 1); const int n = f.apply();
        check("single-map-control", n == 1 && f.all(1900), n, f);
    }
    {
        Fixture f; prefix(f, 1024, 1024); const int n = f.apply();
        check("1024-complete-control", n == 1 && f.all(1900), n, f);
    }
    {
        Fixture f; prefix(f, 1024, 1023); const int n = f.apply();
        check("1024-missing-final-header", n == 0 && f.all(1000), n, f);
    }
    {
        Fixture f; prefix(f, 1025, 1024); const int n = f.apply();
        check("1025-missing-final-header", n == 0 && f.all(1000), n, f);
    }
    {
        Fixture f; prefix(f, 1025, 1024); opcode(f.bytes, 9, 80);
        f.bytes.push_back(0); const int n = f.apply();
        check("1025-truncated-final-payload", n == 0 && f.all(1000), n, f);
    }
    {
        Fixture f; prefix(f, 1025, 1024); gain(f.bytes); const int n = f.apply();
        // Either explicitly reject an implementation limit or consume every map.
        // Executing just the first map is never a complete list interpretation.
        check("1025-complete-never-prefix-only", (n == 0 && f.all(1000)) ||
              (n == 2 && f.all(3700)), n, f);
    }
    {
        Fixture f; prefix(f, 0xffffffffu, 1024); const int n = f.apply();
        check("max-count-missing-headers", n == 0 && f.all(1000), n, f);
    }
    {
        Fixture f; prefix(f, 1, 1);
        auto& c = f.raw->imgdata.rawdata.color.cblack;
        c[4] = 1; c[5] = 2; c[6] = 10; c[7] = 20;
        const int n = f.apply();
        check("valid-black-pattern-control", n == 1 &&
              f.pixels == std::vector<ushort>({1890, 1880, 1890, 1880}), n, f);
    }
    {
        Fixture f; prefix(f, 1, 1);
        replace32(f.bytes, 20 + 4, 1); // Left is one; the next column is outside.
        replace32(f.bytes, 20 + 28, 0xffffffffu);
        const int n = f.apply();
        check("large-column-pitch-keeps-area", n == 1 &&
              f.pixels == std::vector<ushort>({1000, 1900, 1000, 1900}), n, f);
    }
    {
        Fixture f; prefix(f, 1, 1);
        replace32(f.bytes, 20 + 24, 0xffffffffu);
        const int n = f.apply();
        check("large-row-pitch-keeps-first-row", n == 1 &&
              f.pixels == std::vector<ushort>({1900, 1900, 1000, 1000}), n, f);
    }
}
void black_overflow() {
    Fixture f; prefix(f, 1, 1);
    auto& c = f.raw->imgdata.rawdata.color.cblack;
    c[4] = 65536; c[5] = 65536;
    std::cout << "synthetic-api-black-overflow: dimensions=" << c[4] << 'x' << c[5]
              << " product32=" << c[4] * c[5]
              << " product64=" << std::uint64_t(c[4]) * c[5]
              << " capacity=" << LIBRAW_CBLACK_SIZE - 6 << std::endl;
    const int n = f.apply();
    // An invalid repeat pattern can be rejected or ignored; it cannot index out
    // of bounds. DNG's SHORT RepeatDim cannot itself produce these API values.
    check("invalid-black-pattern-safe", (n == 0 && f.all(1000)) ||
          (n == 1 && f.all(1900)), n, f);
}
void file_probe(const char* path) {
    LibRaw raw;
    int code = raw.open_file(path);
    if (code) throw std::runtime_error(std::string("open: ") + libraw_strerror(code));
    code = raw.unpack();
    if (code) throw std::runtime_error(std::string("unpack: ") + libraw_strerror(code));
    auto& d = raw.imgdata;
    const auto& ops = d.rawdata.color.dng_levels.rawopcodes[1];
    const std::size_t count = std::size_t(d.sizes.raw_pitch / 2) * d.sizes.raw_height;
    if (!d.rawdata.raw_image || !count) throw std::runtime_error("expected CFA fixture");
    const std::vector<ushort> before(d.rawdata.raw_image, d.rawdata.raw_image + count);
    const int applied = spk::io::detail::apply_dng_gain_maps(raw);
    const bool changed = !std::equal(before.begin(), before.end(), d.rawdata.raw_image);
    std::size_t changed_pixels=0;
    for (std::size_t i=0; i<count; ++i) changed_pixels += before[i] != d.rawdata.raw_image[i];
    std::cout << "file-probe: bytes=" << ops.len << " declared="
              << (ops.len >= 4 ? spk::io::detail::gain_map_detail::be32(
                  static_cast<const unsigned char*>(ops.data)) : 0)
              << " applied=" << applied << " changed=" << changed << " changed_pixels=" << changed_pixels
              << " black-dim=" << d.rawdata.color.cblack[4] << 'x'
              << d.rawdata.color.cblack[5] << '\n';
}
}
int main(int argc, char** argv) {
    try {
        const std::string mode = argc > 1 ? argv[1] : "--require-safe";
        if (mode == "--black-overflow") black_overflow();
        else if (mode == "--file" && argc == 3) file_probe(argv[2]);
        else if (mode == "--require-safe" || mode == "--observe") lists();
        else return 2;
        std::cout << "boundary failures=" << failures << '\n';
        return failures && mode != "--observe" ? 1 : 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 2; }
}
