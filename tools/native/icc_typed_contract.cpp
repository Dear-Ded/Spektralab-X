// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
// Actual output CCTF samples for an independent LCMS contract comparison.
// Co-created with GPT (OpenAI), 2026-11-11.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>
#include "upstream_output_math.inc"

int main() {
    std::vector<float> values;
    for (unsigned i = 0; i <= 4096; ++i) values.push_back(float(i) / 4096);
    for (float v : {-2.f, -1.f, -.1f, -0.f, 0.f, .0031308f, .040449936f,
                    .04045f, 1.f / 512, 1.f / 32, 1.f, 1.5f, 2.f}) {
        values.push_back(v);
        values.push_back(std::nextafter(v, -INFINITY));
        values.push_back(std::nextafter(v, INFINITY));
    }
    for (uint32_t mode = 0; mode != 3; ++mode) for (float v : values) {
        std::printf("%u,%.9g,%.9g,%.9g\n", mode, double(v),
                    double(cctf_decode_mode(v, mode)), double(cctf_encode_mode(v, mode)));
    }
}
