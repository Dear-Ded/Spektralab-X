#include "io/raw_camera.hpp"
#include "io/raw_decoder.hpp"
#include "raw_white_balance.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <string>

namespace {
void require(bool condition, const std::string& message) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message.c_str());
        std::exit(1);
    }
}

bool decode(bool headroom, const char* path, const std::array<float, 4>* wb,
            spk::io::DecodedRaw& out, std::string& error) {
    return headroom ? spk::io::decode_raw_headroom(path, wb, out, error, wb ? 6500 : 0)
                    : spk::io::decode_raw_compatible(path, wb, out, error, wb ? 6500 : 0);
}

void rejected(bool headroom, const char* path, const std::array<float, 4>* wb,
              const char* expected_error) {
    spk::io::DecodedRaw out;
    out.width = 7; out.height = 11; out.rgb = {.125f, -.25f, .5f};
    out.metadata.model = "unchanged";
    out.metadata.camera_whitebalance_valid = true;
    out.metadata.camera_whitebalance.fill(17);
    out.metadata.post_scale_pre_mul.fill(19);
    std::string error;
    require(!decode(headroom, path, wb, out, error), "invalid input was accepted");
    require(error.find(expected_error) != std::string::npos, "unexpected error: " + error);
    require(out.width == 7 && out.height == 11 && out.rgb == std::vector<float>({.125f, -.25f, .5f}) &&
            out.metadata.model == "unchanged" && out.metadata.camera_whitebalance_valid &&
            out.metadata.camera_whitebalance == std::array<float, 4>{17,17,17,17} &&
            out.metadata.post_scale_pre_mul == std::array<float, 4>{19,19,19,19},
            "failed decode changed the output");
}

void actual(const char* path, bool missing_source_wb, bool headroom) {
    auto raw = std::make_unique<spk::io::detail::CameraLibRaw>();
    raw->imgdata.params.use_camera_matrix = 1;
    require(raw->open_file(path) == LIBRAW_SUCCESS, "cannot open fixture");
    raw->interpolate_dng_matrix(nullptr);
    std::array<float, 4> original{};
    std::copy(std::begin(raw->imgdata.color.cam_mul), std::end(raw->imgdata.color.cam_mul), original.begin());
    bool source_valid = true;
    for (unsigned c = 0; c < 4; ++c)
        source_valid &= std::isfinite(original[c]) && (c >= 3 || original[c] > .00001f);
    require(source_valid != missing_source_wb, "fixture source WB does not match its test role");
    std::array<float, 4> selected{};
    std::string error;
    if (missing_source_wb) {
        // Exercise the decoder contract even when source metadata is also
        // insufficient for the host's temperature/tint picker.
        selected = {2, 1, 1.5f, 1};
    } else {
        require(spk::android::raw_multipliers_for(*raw, 6500, 0, selected, error), "cannot select WB: " + error);
    }
    spk::io::DecodedRaw out;
    require(decode(headroom, path, &selected, out, error), "custom decode: " + error);
    require(out.width && out.height && out.rgb.size() == std::size_t(out.width) * out.height * 3,
            "empty or inconsistent image");
    require(out.metadata.camera_whitebalance_valid == !missing_source_wb, "source WB validity was changed");
    require(out.metadata.camera_whitebalance == (missing_source_wb ? std::array<float, 4>{} : original),
            "source WB metadata was replaced by selected WB");
    for (float value : out.rgb) require(std::isfinite(value), "nonfinite output pixel");
    for (float value : out.metadata.camera_to_output) require(std::isfinite(value), "nonfinite output matrix");
    for (float value : out.metadata.post_scale_pre_mul)
        require(std::isfinite(value) && value > 0, "invalid applied WB");
    for (unsigned c = 0; c < 3; ++c) {
        const double applied = double(out.metadata.post_scale_pre_mul[c]) / out.metadata.post_scale_pre_mul[1];
        const double requested = double(selected[c]) / selected[1];
        require(std::fabs(applied / requested - 1) < 1e-5, "applied WB differs from selected WB");
    }
    require(out.metadata.headroom_enabled == headroom, "wrong decode policy");
    std::printf("%s: %s %ux%u source_wb_valid=%d finite=1 selected_wb_matched=1\n",
                path, headroom ? "headroom" : "compatible", out.width, out.height,
                out.metadata.camera_whitebalance_valid);
}
} // namespace

int main(int argc, char** argv) {
    for (bool headroom : {false, true}) {
        for (float invalid : {0.f, -1.f, std::numeric_limits<float>::infinity(),
                              std::numeric_limits<float>::quiet_NaN()}) {
            std::array<float, 4> wb{invalid, 1, 1, 1};
            rejected(headroom, "not-opened-for-invalid-wb.raw", &wb, "invalid user white-balance multiplier");
        }
    }
    std::puts("invalid custom WB: 8 rejection/unchanged-output checks passed");
    if (argc == 1) {
        std::puts("actual decode skipped: supply missing-as-shot RAW, then optional valid-as-shot RAW files");
        return 0;
    }
    spk::io::set_raw_half_size_budget(1);
    for (bool headroom : {false, true}) {
        rejected(headroom, argv[1], nullptr, "valid camera/as-shot RGB white balance is required");
        actual(argv[1], true, headroom);
    }
    for (int i = 2; i < argc; ++i) actual(argv[i], false, false);
    spk::io::set_raw_half_size_budget(0);
    std::puts("missing source WB: 2 strict default rejections and 2 actual custom decodes passed");
}
