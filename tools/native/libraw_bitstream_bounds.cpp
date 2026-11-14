// Compile actual decoder bit pumps, co-created with GPT (OpenAI).
#if defined(SPK_TEST_OLYMPUS)
#include "src/decoders/olympus14.cpp"
#else
#include "src/decoders/decoders_libraw.cpp"
#endif
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static unsigned checks = 0;
static void require(bool ok, const char* message) {
    ++checks;
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}

template<class F> static void rejects(F&& run, const char* message) {
    bool rejected = false;
    try { run(); } catch (LibRaw_exceptions) { rejected = true; }
    require(rejected, message);
}

#if defined(SPK_TEST_OLYMPUS)
// The fixture never calls LibRaw's JPEG adapter; supply the base class key function.
int LibRaw_abstract_datastream::jpeg_src(void*) { return 0; }

class Bytes final : public LibRaw_abstract_datastream {
    std::vector<unsigned char> bytes_;
    size_t at_ = 0;
public:
    explicit Bytes(std::vector<unsigned char> bytes) : bytes_(std::move(bytes)) {}
    int valid() override { return 1; }
    int read(void* out, size_t size, size_t count) override {
        if (!size) return 0;
        count = std::min(count, (bytes_.size() - at_) / size);
        if (!count) return 0;
        std::memcpy(out, bytes_.data() + at_, size * count); at_ += size * count;
        return int(count);
    }
    int seek(INT64, int) override { return -1; }
    INT64 tell() override { return INT64(at_); }
    INT64 size() override { return INT64(bytes_.size()); }
    int get_char() override { return at_ < bytes_.size() ? bytes_[at_++] : EOF; }
    char* gets(char*, int) override { return nullptr; }
    int scanf_one(const char*, void*) override { return 0; }
    int eof() override { return at_ == bytes_.size(); }
    int jpeg_src(void*) override { return 0; }
};

static void test() {
    for (unsigned width = 1; width <= 16; ++width) {
        Bytes input({0xAB, 0xCD, 0xEF, 0x01});
        buffered_bitpump_t pump(&input, 4);
        pump.replace_data();
        require(pump.getbits(width) == (0xABCDEF01u >> (32 - width)), "Olympus full-word extraction");
        require(pump.bitcount == 32 - width, "Olympus remaining count");
    }
    Bytes empty({});
    buffered_bitpump_t zero(&empty, 4);
    require(zero.getbits(0) == 0, "Olympus zero-bit read");
    rejects([&] { zero.getbits(17); }, "Olympus oversized bit width");
    rejects([&] { zero.replace_data(); }, "Olympus empty input");
    Bytes shortInput({1, 2, 3});
    buffered_bitpump_t shortPump(&shortInput, 4);
    rejects([&] { shortPump.get4(); }, "Olympus truncated word");
    Bytes partial({0x12, 0x34, 0x56, 0x78});
    buffered_bitpump_t partialPump(&partial, 2);
    require(partialPump.get2() == 0x1234, "Olympus first small refill");
    require(partialPump.get2() == 0x5678, "Olympus second small refill");
    rejects([&] { partialPump.get2(); }, "Olympus refill EOF");
    Bytes crossing({0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC});
    buffered_bitpump_t crossingPump(&crossing, 3);
    require(crossingPump.get2() == 0x1234, "Olympus prefix before crossing refill");
    require(crossingPump.get4() == 0x56789ABC, "Olympus combines leftover and exact final refill");
    for (unsigned width : {16u, 32u, 0xFFFFFFFFu}) {
        int glc = 0, a = 0, b = 0;
        rejects([&] { oly_code(&zero, width, 0, 0, 1, &glc, &a, &b); }, "Olympus invalid residual width");
    }
}
#else
static void test() {
    uint32_t words[] = {0x12345678, 0x90ABCDEF};
    iiq_bitstream_t pump(words, sizeof(words));
    require(pump.get(0) == 0, "IIQ zero-bit read");
    require(pump.get(32) == words[0], "IIQ full 32-bit mask");
    require(pump.get(32) == words[1], "IIQ exact final word");
    rejects([&] { pump.get(1); }, "IIQ byte-length EOF");
    iiq_bitstream_t invalid(words, sizeof(words));
    rejects([&] { invalid.peek(33); }, "IIQ oversized bit width");
    iiq_bitstream_t empty(words + 2, 0);
    rejects([&] { empty.get(1); }, "IIQ EOF before dereference");
    unsigned char byte[] = {0xEF};
    iiq_bitstream_t partial(reinterpret_cast<uint32_t*>(byte), sizeof(byte));
    require(partial.get(32) == 0xEF, "IIQ bounded partial word zero padding");
    rejects([&] { partial.get(1); }, "IIQ partial word EOF");
    for (size_t length = 1; length <= 7; ++length) {
        std::vector<unsigned char> unaligned(length + 1, 0xAB);
        iiq_bitstream_t bytes(reinterpret_cast<uint32_t*>(unaligned.data() + 1), length);
        for (size_t at = 0; at < length; at += 4) {
            uint32_t expected = 0;
            std::memcpy(&expected, unaligned.data() + 1 + at, std::min(size_t(4), length - at));
            require(bytes.get(32) == expected, "IIQ unaligned exact byte boundary");
        }
        rejects([&] { bytes.get(1); }, "IIQ unaligned byte boundary EOF");
    }
    uint32_t seed = 1;
    for (unsigned trial = 0; trial < 1000; ++trial) {
        uint32_t data[4];
        for (auto& value : data) { seed = seed * 1664525u + 1013904223u; value = seed; }
        iiq_bitstream_t stream(data, sizeof(data));
        unsigned bit = 0;
        while (bit < 128) {
            const unsigned count = std::min(1u + (seed % 16), 128 - bit);
            uint32_t expected = 0;
            for (unsigned i = 0; i < count; ++i, ++bit)
                expected = (expected << 1) | ((data[bit / 32] >> (31 - bit % 32)) & 1u);
            require(stream.get(char(count)) == expected, "IIQ randomized bit-exact word extraction");
            seed = seed * 1664525u + 1013904223u;
        }
        rejects([&] { stream.get(1); }, "IIQ randomized EOF");
    }
    uint32_t zeroWords[8] = {};
    ushort pixels[8] = {};
    decode_S_type(8, zeroWords, pixels, sizeof(zeroWords));
    for (ushort pixel : pixels) require(pixel == 0, "IIQ complete synthetic zero row");
}
#endif

int main() {
    test();
    std::printf("PASS actual LibRaw bitstream bounds: %u checks\n", checks);
}
