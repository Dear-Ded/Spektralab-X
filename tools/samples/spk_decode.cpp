// spk_decode: decode one RAW the way the app does and describe the result, for
// the sample regression set (regress.sh) and the B&W check (bw_regress.sh).
//
//   spk_decode <raw> [compatible|headroom|both]
//
// Prints one line per mode:
//   <mode> ok <w>x<h> sha256=<hash of the float frame> mean=r,g,b max=r,g,b nonfinite=<n> decoder=<name>
//   <mode> fail <error>
// The hash covers width, height and the packed float RGB bytes, so it is exact:
// it holds for one compiler, one CPU and one patch set (the box's GCC build).
// SPEKTRALAB_RAW_THREADS picks the LibRaw thread count, as in the engine.
#include "io/raw_decoder.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {
// FIPS 180-4 SHA-256, enough for one buffer.
class Sha256 {
public:
    void update(const void* data, std::size_t n) {
        const auto* p = static_cast<const unsigned char*>(data);
        total_ += n;
        while (n) {
            const std::size_t take = std::min(n, std::size_t(64) - fill_);
            std::memcpy(block_ + fill_, p, take);
            fill_ += take; p += take; n -= take;
            if (fill_ == 64) { compress(block_); fill_ = 0; }
        }
    }
    std::string hex() {
        const std::uint64_t bits = total_ * 8;
        const unsigned char one = 0x80, zero = 0;
        update(&one, 1);
        while (fill_ != 56) update(&zero, 1);
        unsigned char len[8];
        for (int i = 0; i < 8; ++i) len[i] = static_cast<unsigned char>(bits >> (56 - 8 * i));
        update(len, 8);
        char out[65];
        for (int i = 0; i < 8; ++i) std::snprintf(out + i * 8, 9, "%08x", h_[i]);
        return std::string(out, 64);
    }

private:
    static std::uint32_t rotr(std::uint32_t x, int n) { return x >> n | x << (32 - n); }
    void compress(const unsigned char* b) {
        static const std::uint32_t k[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
        std::uint32_t w[64];
        for (int i = 0; i < 16; ++i)
            w[i] = std::uint32_t(b[4 * i]) << 24 | std::uint32_t(b[4 * i + 1]) << 16 |
                   std::uint32_t(b[4 * i + 2]) << 8 | b[4 * i + 3];
        for (int i = 16; i < 64; ++i) {
            const std::uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const std::uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        std::uint32_t a = h_[0], bb = h_[1], c = h_[2], d = h_[3], e = h_[4], f = h_[5], g = h_[6], h = h_[7];
        for (int i = 0; i < 64; ++i) {
            const std::uint32_t t1 = h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i];
            const std::uint32_t t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & bb) ^ (a & c) ^ (bb & c));
            h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
        }
        h_[0] += a; h_[1] += bb; h_[2] += c; h_[3] += d; h_[4] += e; h_[5] += f; h_[6] += g; h_[7] += h;
    }
    std::uint32_t h_[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                           0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    unsigned char block_[64] = {};
    std::size_t fill_ = 0;
    std::uint64_t total_ = 0;
};

int describe(const char* mode, const std::string& path) {
    spk::io::DecodedRaw d;
    std::string err;
    const bool headroom = std::strcmp(mode, "headroom") == 0;
    const bool ok = headroom ? spk::io::decode_raw_headroom(path, nullptr, d, err)
                             : spk::io::decode_raw_compatible(path, nullptr, d, err);
    if (!ok) {
        std::replace(err.begin(), err.end(), '\n', ' ');
        std::printf("%s fail %s\n", mode, err.c_str());
        return 1;
    }
    Sha256 sha;
    const std::uint32_t dims[2] = {d.width, d.height};
    sha.update(dims, sizeof dims);
    sha.update(d.rgb.data(), d.rgb.size() * sizeof(float));
    std::array<double, 3> sum{}, mx{};
    std::size_t nonfinite = 0;
    const std::size_t n = d.rgb.size() / 3;
    for (std::size_t i = 0; i < n; ++i)
        for (int c = 0; c < 3; ++c) {
            const float v = d.rgb[i * 3 + c];
            if (!std::isfinite(v)) { ++nonfinite; continue; }
            sum[c] += v;
            mx[c] = std::max(mx[c], double(v));
        }
    const double den = n ? double(n) : 1.0;
    std::string decoder = d.metadata.decoder_name.empty() ? "-" : d.metadata.decoder_name;
    std::replace(decoder.begin(), decoder.end(), ' ', '_');
    std::printf("%s ok %ux%u sha256=%s mean=%.6f,%.6f,%.6f max=%.6f,%.6f,%.6f nonfinite=%zu decoder=%s\n", mode,
                d.width, d.height, sha.hex().c_str(), sum[0] / den, sum[1] / den, sum[2] / den, mx[0], mx[1],
                mx[2], nonfinite, decoder.c_str());
    return 0;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <raw> [compatible|headroom|both]\n", argv[0]);
        return 2;
    }
    const std::string mode = argc > 2 ? argv[2] : "both";
    int status = 0;
    if (mode == "compatible" || mode == "both") status |= describe("compatible", argv[1]);
    // Headroom refuses non-Bayer sources by design; regress.sh records that as a result, not an error.
    if (mode == "headroom" || mode == "both") describe("headroom", argv[1]);
    return status;
}
