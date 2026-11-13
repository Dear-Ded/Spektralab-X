// Co-created with GPT (OpenAI), 2026-11-13. See NOTICE.
#include <array>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include "internal/losslessjpeg.h"

namespace {
void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
// Supplies a requested prefix plus controlled excess high bits. Only the cache
// lookup receives excess bits; the independent slow-path reads remain valid.
struct PrefixPump : BitPump {
    uint32_t prefix, high, consumed = 0;
    explicit PrefixPump(uint32_t p, uint32_t h = 0) : prefix(p), high(h) {}
    uint32_t peek(uint32_t num) override {
        require(num <= 13 && consumed + num <= 13, "unexpected prefix request");
        const uint32_t value = (prefix >> (13 - consumed - num)) & ((1u << num) - 1);
        return value | (num == 13 ? high : 0);
    }
    void consume(uint32_t num) override { consumed += num; }
};
void initialize(HuffTable& table) {
    uint32_t lengths[17]{}, values[256]{};
    lengths[1] = 2; values[0] = 0; values[1] = 1;
    table.initval(lengths, values, false);
    require(table.decodecache.size() == 8192, "wrong production cache capacity");
}
int32_t expected(uint32_t prefix) {
    return prefix & 4096 ? (prefix & 2048 ? 1 : -1) : 0;
}
uint32_t expected_bits(uint32_t prefix) { return prefix & 4096 ? 2 : 1; }
}

int main(int argc, char** argv) try {
    require(argc == 2, "usage: libraw_huffman_cache --legal|--high");
    HuffTable table; initialize(table);
    const bool high = std::string(argv[1]) == "--high";
    require(high || std::string(argv[1]) == "--legal", "unknown mode");
    unsigned cases = 0;
    if (high) {
        for (uint32_t extra : {0x2000u, 0x80000000u, 0xffffe000u}) for (uint32_t i = 0; i < 8192; ++i) {
            PrefixPump pump(i, extra);
            require(table.decode(pump) == expected(i), "wrong masked cache value");
            require(pump.consumed == expected_bits(i), "wrong masked bit consumption");
            ++cases;
        }
        std::printf("PASS high-index cache cases=%u\n", cases);
        return 0;
    }
    for (unsigned route = 0; route < 3; ++route) for (uint32_t i = 0; i < 8192; ++i) {
        table.disable_cache = route == 1;
        const uint64_t saved = table.decodecache[i];
        if (route == 2) table.decodecache[i] = 0;
        PrefixPump pump(i);
        require(table.decode(pump) == expected(i), "wrong legal value");
        require(pump.consumed == expected_bits(i), "wrong legal bit consumption");
        table.decodecache[i] = saved;
        ++cases;
    }
    table.disable_cache = false;
    for (bool bug : {false, true}) for (uint32_t bits : {16u, 17u, 24u}) {
        table.dng_bug = bug;
        for (auto& entry : table.decodecache)
            entry = LIBRAW_CACHE_PRESENT_FLAG | (uint64_t(bits) << 16) | 0x8000;
        for (uint32_t i = 0; i < 8192; ++i) {
            PrefixPump pump(i);
            require(table.decode(pump) == -32768, "wrong cached 16-bit difference");
            require(pump.consumed == (bug ? bits - 16 : bits), "wrong DNG bit consumption");
            ++cases;
        }
    }
    initialize(table);
    for (uint32_t i = 0; i < 8192; ++i) {
        std::array<uint8_t, 64> bytes{};
        bytes[0] = uint8_t(i >> 5); bytes[1] = uint8_t(i << 3);
        // Keep valid JPEG byte stuffing when the first entropy byte is 0xff.
        if (bytes[0] == 0xff) { bytes[2] = bytes[1]; bytes[1] = 0; }
        ByteStreamBE source(bytes.data(), bytes.size());
        BitPumpJpeg cached(source), slow(source);
        table.disable_cache = false;
        const int32_t got = table.decode(cached);
        table.disable_cache = true;
        require(got == table.decode(slow), "real bit pump cache/slow mismatch");
        require(cached.pos == slow.pos && cached.nbits == slow.nbits && cached.bits == slow.bits,
            "real bit pump state mismatch");
        ++cases;
    }
    std::printf("PASS legal cache/slow/DNG/real-pump cases=%u\n", cases);
} catch (const std::exception& e) {
    std::fprintf(stderr, "FAIL %s\n", e.what()); return 1;
} catch (...) {
    std::fprintf(stderr, "FAIL LibRaw exception\n"); return 1;
}
