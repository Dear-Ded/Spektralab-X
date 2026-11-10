// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
#include "passthrough.hpp"
#include "output_transform.hpp"
#include "layer2.hpp"
#include "mac_export_reference.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
using namespace spk::android;
void check(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
void app_keys() {
    ViewFlags flags;
    check(!flags.raw_development.enabled && flags.raw_development.auto_exposure, "old records must stay neutral");
    std::string error, params = R"({"x_no_film":true,"x_raw_development":"standard_v1","x_raw_auto_exposure":false,"x_raw_exposure_ev":-2.25,"auto_exposure":true})";
    check(take_app_keys(params, flags, error), error);
    spk::Json engine_params;
    check(spk::Json::parse(params, engine_params, error), error);
    check(engine_params.fields().size() == 1 && engine_params.at("auto_exposure").as_bool(), "app keys leaked to physical engine");
    check(flags.no_film && flags.raw_development.enabled && !flags.raw_development.auto_exposure &&
        flags.raw_development.exposure_ev == -2.25f, "RAW intent was lost");
    const auto original = flags;
    for (const char* invalid : {R"({"x_no_film":false,"x_raw_development":"future"})",
            R"({"x_raw_auto_exposure":1})", R"({"x_raw_exposure_ev":"2"})",
            R"({"x_raw_exposure_ev":8.001})", R"({"x_raw_exposure_ev":-8.001})"}) {
        std::string bad = invalid;
        check(!take_app_keys(bad, flags, error), "malformed RAW option accepted");
        check(flags.no_film == original.no_film && flags.raw_development.enabled == original.raw_development.enabled &&
            flags.raw_development.auto_exposure == original.raw_development.auto_exposure &&
            flags.raw_development.exposure_ev == original.raw_development.exposure_ev, "failed parse mutated active intent");
        check(bad == invalid, "failed parse rewrote delta");
    }
    auto reply = with_app_keys("{}", flags);
    ViewFlags roundtrip;
    check(take_app_keys(reply, roundtrip, error), error);
    check(reply == "{}" && roundtrip.raw_development.enabled && roundtrip.raw_development.exposure_ev == -2.25f,
        "RAW get/set parameters do not roundtrip");
    params = R"({"x_raw_development":"neutral","x_raw_auto_exposure":true,"x_raw_exposure_ev":0})";
    check(take_app_keys(params, flags, error) && !flags.raw_development.enabled && flags.raw_development.auto_exposure &&
        flags.raw_development.exposure_ev == 0.0f, "neutral reset failed");
    std::cout << "RAW app-key validation and persistence PASS\n";
}
struct LookCase {
    std::vector<float> values = std::vector<float>(kLookSize, 0);
    spk::Json adjustments = spk::Json::object();
    LookCase() { values[kVignetteMid] = 50; }
    void set(int index, const char* key, double v) {
        values[index] = float(v); adjustments.set(key, spk::Json(v));
    }
};
struct SessionDeleter { void operator()(spk_session* p) const { spk_session_release(p); } };
using Session = std::unique_ptr<spk_session, SessionDeleter>;
Session open(spk_engine* engine, const std::vector<float>& data, uint32_t w, uint32_t h,
             const char* geometry = "") {
    const std::string params = std::string(R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"auto_exposure":false)") + geometry + "}";
    spk_image image{data.data(), w, h, 3};
    Session s(spk_open(engine, &image, params.c_str(), nullptr));
    check(bool(s), std::string("open: ") + spk_last_error(engine));
    return s;
}
spk::view::Result reference(spk_engine* engine, spk_session* session, const LookCase& look,
                            const std::string& target, bool exporting, bool bits16,
                            const spk::Json& geometry = spk::Json::object(), uint32_t edge = 0) {
    if (exporting) {
        // Keep the real GPU grade/geometry boundary, but evaluate the Mac's
        // updated file intent with an independent double CAM16 implementation.
        const auto graded = reference(engine, session, look, "ProPhoto RGB", false, true, geometry, edge);
        return spk::test::mac_export_reference(engine, graded, target, bits16);
    }
    spk::view::Request request;
    request.no_film = true; request.target_space = target;
    request.export_intent = exporting; request.rgba16 = bits16;
    request.adjustments = look.adjustments; request.geometry = geometry;
    request.long_edge = edge;
    spk::view::Result result;
    std::string error;
    check(spk::view::render(session, request, result, error), error);
    return result;
}
// The upstream grade still uses gamma 2.2 for exposure in encoded ProPhoto.
// For float exposure cases, use an independently pre-exposed scene and keep
// that old operation disabled. Other grade/output operations remain the GPU
// reference, rather than copying the corrected production exposure helper.
spk::view::Result linear_exposure_reference(spk_engine* engine, spk_session* session,
                                           const std::vector<float>& input, uint32_t w, uint32_t h,
                                           const LookCase& look, const std::string& target,
                                           bool exporting, bool bits16) {
    if (look.values[kExposure] == 0) return reference(engine, session, look, target, exporting, bits16);
    check(look.values[kTemperature] == 0 && look.values[kTint] == 0,
          "pre-exposure oracle requires neutral white balance");
    auto exposed = input;
    const double gain = std::exp2(double(look.values[kExposure]));
    for (float& value : exposed) value = float(double(value) * gain);
    auto corrected = open(engine, exposed, w, h);
    auto remaining = look;
    remaining.values[kExposure] = 0;
    remaining.adjustments.set("exposure", spk::Json(0.0));
    return reference(engine, corrected.get(), remaining, target, exporting, bits16);
}
unsigned max_error16(const std::vector<uint16_t>& got, const spk::view::Result& expected) {
    check(expected.pixels.size() == got.size() * 2, "RGBA16 shape mismatch");
    unsigned error = 0;
    for (size_t i = 0; i < got.size(); ++i) {
        uint16_t value;
        std::memcpy(&value, expected.pixels.data() + i * 2, 2);
        error = std::max(error, unsigned(std::abs(int(got[i]) - int(value))));
    }
    return error;
}
void regression(spk_engine* engine, bool legacy) {
    unsigned cases = 0;
    for (const auto& item : {std::array<float, 4>{4, 2, .8f, -3},
                             std::array<float, 4>{-.01f, .18f, .18f, 0}}) {
        const std::vector<float> input{item[0], item[1], item[2]};
        auto session = open(engine, input, 1, 1);
        LookCase look;
        look.set(kExposure, "exposure", item[3]);
        if (item[0] < 0) look.set(kSaturation, "saturation", -100);
        Layer2 layer(look.values);
        const auto expected = linear_exposure_reference(engine, session.get(), input, 1, 1,
                                                        look, "ProPhoto RGB", false, true);
        spk_result shape{};
        std::vector<float> rgb;
        std::string error;
        check(render_view_float(session.get(), "full", shape, rgb, error), error);
        OutputTransform xf;
        check(xf.fetch(engine, "ProPhoto RGB", false, error), error);
        xf.set_exact(true);
        std::vector<uint16_t> got;
        pack_float_rgba16(rgb.data(), shape.row_stride_px, shape.width, shape.height, got, &layer, xf);
        spk_result old{};
        check(render_view(session.get(), "full", true, old, error), error);
        std::vector<uint16_t> clipped;
        pack_rgba16(old.rgba16, old.row_stride_px, old.width, old.height, clipped, &layer, xf);
        spk_result_free(&old);
        const unsigned new_error = max_error16(got, expected), old_error = max_error16(clipped, expected);
        std::cout << "regression " << cases << " new_max16=" << new_error << " old_max16=" << old_error << '\n';
        check(new_error <= 3 && old_error > 1000, "HDR/signed regression did not distinguish the old path");
        if (legacy) check(old_error <= 3, "legacy no-film path loses recoverable channels (expected red)");
        ++cases;
    }
}
void matrix(spk_engine* engine) {
    constexpr uint32_t w = 17, h = 13;
    const float levels[] = {-.025f, -.001f, 0, .00001f, .01f, .05f, .18f, .5f, .9f, 1, 2, 4, 8};
    std::vector<float> input(size_t(w) * h * 3);
    for (size_t i = 0; i < input.size(); ++i) input[i] = levels[(i * 7 + i / 13) % std::size(levels)];
    auto session = open(engine, input, w, h);
    spk_result shape{};
    std::vector<float> rgb;
    std::string error;
    check(render_view_float(session.get(), "full", shape, rgb, error), error);
    check(std::any_of(rgb.begin(), rgb.end(), [](float v) { return v < 0; }) &&
          std::any_of(rgb.begin(), rgb.end(), [](float v) { return v > 1; }), "float boundary clipped the source");
    // Upstream's SDR shadow/highlight weights extrapolate outside 0..1.
    // Compare those controls only within their valid domain; the independent
    // layer2_highlight_domain probe checks signed/HDR behavior and EV recovery.
    auto bounded_input = input;
    for (size_t i = 0; i < bounded_input.size(); ++i)
        bounded_input[i] = .18f + .72f * float(i % 127) / 126.0f;
    auto bounded_session = open(engine, bounded_input, w, h);
    spk_result bounded_shape{};
    std::vector<float> bounded_rgb;
    check(render_view_float(bounded_session.get(), "full", bounded_shape, bounded_rgb, error), error);
    std::vector<LookCase> looks(15);
    looks[1].set(kExposure, "exposure", -3);
    looks[2].set(kExposure, "exposure", 2);
    looks[2].set(kSaturation, "saturation", -60);
    looks[3].set(kBrightness, "brightness", 45);
    looks[3].set(kSaturation, "saturation", -30);
    looks[4].set(kTemperature, "temperature", -32);
    looks[4].set(kTint, "tint", 24);
    looks[4].set(kContrast, "contrast", 18);
    looks[5].values[kVignette] = -40; looks[5].values[kVignetteMid] = 43;
    spk::Json vignette = spk::Json::object();
    vignette.set("amount", spk::Json(-40.0)); vignette.set("midpoint", spk::Json(43.0));
    looks[5].adjustments.set("vignette", std::move(vignette));
    spk::Json curves = spk::Json::object();
    for (const char* key : {"rgb", "luma", "red", "green", "blue"}) {
        looks[6].values.insert(looks[6].values.end(), {3, 0, 0, .5f, .65f, 1, 1});
        spk::Json channel;
        check(spk::Json::parse(R"({"points":[[0,0],[0.5,0.65],[1,1]]})", channel, error), error);
        curves.set(key, std::move(channel));
    }
    looks[6].adjustments.set("curves", std::move(curves));
    looks[7].set(kShadows, "shadows", 100);
    looks[7].set(kHighlights, "highlights", -100);
    looks[8].set(kShadows, "shadows", -100);
    looks[8].set(kHighlights, "highlights", 100);
    looks[9].set(kBlackPoint, "blackPoint", 50);
    looks[9].set(kWhitePoint, "whitePoint", 50);
    looks[10].set(kExposure, "exposure", -2);
    looks[10].set(kContrast, "contrast", 50);
    looks[10].set(kBrightness, "brightness", -50);
    looks[10].set(kSaturation, "saturation", 100);
    looks[10].set(kHighlights, "highlights", -70);
    looks[10].set(kShadows, "shadows", 80);
    looks[10].set(kBlackPoint, "blackPoint", 12);
    looks[10].set(kWhitePoint, "whitePoint", 17);
    spk::Json balance;
    check(spk::Json::parse(R"({"master":{"hue":35,"saturation":0.3,"luminance":-0.12},"shadows":{"hue":218,"saturation":0.7,"luminance":0.24},"midtones":{"hue":87,"saturation":0.25,"luminance":-0.4},"highlights":{"hue":309,"saturation":0.5,"luminance":0.32}})", balance, error), error);
    looks[11].adjustments.set("colorBalance", std::move(balance));
    const float wheels[] = {35, 30, -12, 218, 70, 24, 87, 25, -40, 309, 50, 32};
    std::copy(std::begin(wheels), std::end(wheels), looks[11].values.begin() + kCbMaster);
    looks[12].set(kContrast, "contrast", -50);
    looks[12].set(kTemperature, "temperature", 100);
    looks[12].set(kTint, "tint", -100);
    looks[12].set(kBrightness, "brightness", 50);
    looks[12].set(kSaturation, "saturation", -100);
    looks[13].set(kExposure, "exposure", 3);
    looks[13].set(kBrightness, "brightness", -50);
    spk::Json distinct;
    check(spk::Json::parse(R"({"rgb":{"points":[[0,0],[0.5,0.65],[1,1]]},"luma":{"points":[[0,0],[0.5,0.4],[1,1]]},"red":{"points":[[0,0],[0.5,0.8],[1,1]]},"green":{"points":[[0,0],[0.5,0.3],[1,1]]},"blue":{"points":[[0,0],[0.5,0.55],[1,1]]}})", distinct, error), error);
    looks[14].adjustments.set("curves", std::move(distinct));
    for (float mid : {.65f, .4f, .8f, .3f, .55f})
        looks[14].values.insert(looks[14].values.end(), {3, 0, 0, .5f, mid, 1, 1});
    unsigned cases = 0, worst16 = 0, worst8 = 0;
    for (const auto& look : looks) {
        const bool bounded_tone = look.values[kHighlights] != 0 || look.values[kShadows] != 0;
        const auto& source = bounded_tone ? bounded_input : input;
        const auto& codes = bounded_tone ? bounded_rgb : rgb;
        auto* reference_session = bounded_tone ? bounded_session.get() : session.get();
        Layer2 layer(look.values);
        const Layer2* active = layer.identity() ? nullptr : &layer;
        for (const std::string target : {"sRGB", "Display P3"}) for (bool exporting : {false, true}) {
            OutputTransform xf;
            check(xf.fetch(engine, target, exporting, error), error);
            xf.set_exact(true);
            std::vector<uint16_t> file;
            pack_float_rgba16(codes.data(), w, w, h, file, active, xf);
            const auto expected16 = linear_exposure_reference(engine, reference_session, source, w, h,
                                                               look, target, exporting, true);
            const unsigned e16 = max_error16(file, expected16);
            worst16 = std::max(worst16, e16);
            if (e16 > 8) {
                std::cout << "case=" << (&look - looks.data()) << " target=" << target << " export=" << exporting << '\n';
                for (size_t i = 0; i < file.size(); ++i) {
                    uint16_t expected;
                    std::memcpy(&expected, expected16.pixels.data() + i * 2, 2);
                    if (std::abs(int(file[i]) - int(expected)) == int(e16)) {
                        const size_t pixel = i / 4;
                        std::cout << "worst pixel=" << pixel << " channel=" << i % 4 << " got=" << file[i] << " expected=" << expected
                                  << " source=" << codes[pixel * 3] << ',' << codes[pixel * 3 + 1] << ',' << codes[pixel * 3 + 2] << '\n';
                        break;
                    }
                }
            }
            check(e16 <= 8, "float/reference RGBA16 mismatch: " + target + " max=" + std::to_string(e16));
            // TIFF strips use full-frame coordinates and may arrive out of order.
            Rgba16Rows strips(codes.data(), w, w, h, active, &xf);
            std::vector<uint16_t> rows;
            for (uint32_t y = h; y > 0; --y) {
                strips.pack(y - 1, 1, rows);
                check(std::equal(rows.begin(), rows.end(), file.begin() + size_t(y - 1) * w * 4), "TIFF row/full-frame mismatch");
            }
            // Repacking immutable float pixels must be independent of the previous look.
            std::vector<uint16_t> again;
            pack_float_rgba16(codes.data(), w, w, h, again, active, xf);
            check(file == again, "cached float repack changed bytes");
            std::vector<uint8_t> screen(size_t(w) * h * 4);
            xf.set_exact(false); // use the production display encoding table
            pack_float_rgba8(codes.data(), w, w, h, screen.data(), w * 4, active, xf);
            const auto expected8 = linear_exposure_reference(engine, reference_session, source, w, h,
                                                              look, target, exporting, false);
            check(screen.size() == expected8.pixels.size(), "RGBA8 shape mismatch");
            unsigned e8 = 0;
            for (size_t i = 0; i < screen.size(); ++i)
                e8 = std::max(e8, unsigned(std::abs(int(screen[i]) - int(expected8.pixels[i]))));
            worst8 = std::max(worst8, e8);
            check(e8 <= 1, "float/reference RGBA8 mismatch");
            cases += 2;
        }
    }
    bool rejected = false;
    try { Rgba16Rows invalid(rgb.data(), w, w, h, nullptr, nullptr); }
    catch (const std::invalid_argument&) { rejected = true; }
    check(rejected, "float pixels accepted without a colour transform");
    std::cout << "matrix PASS cases=" << cases << " max16=" << worst16 << " max8=" << worst8
              << " tiff_rows=" << looks.size() * 4 * h << " repack=" << looks.size() * 4 << " missing_transform=refused\n";
}
void geometry(spk_engine* engine) {
    constexpr uint32_t w = 9, h = 7;
    std::vector<float> input(w * h * 3);
    for (size_t i = 0; i < input.size(); ++i) input[i] = .02f + .9f * float(i) / float(input.size() - 1);
    auto session = open(engine, input, w, h,
        R"(,"geometry_crop_x":0.1,"geometry_crop_y":0.1,"geometry_crop_w":0.8,"geometry_crop_h":0.6,"geometry_quarter_turns":1,"geometry_flip_h":true)");
    spk::view::Request request;
    std::string error;
    check(view_request(session.get(), "full", true, request, error), error);
    request.ungraded_float = true; request.rgba16 = false; request.output_channels = 3; request.long_edge = 3;
    spk::view::Result result;
    check(spk::view::render(session.get(), request, result, error), error);
    check(result.width == 2 && result.height == 3 && result.float_pixels.size() == 18 && result.pixels.empty(), "float geometry shape/payload");
    LookCase identity;
    identity.adjustments.set("enabled", spk::Json(false));
    spk::Json expected_geometry;
    check(spk::Json::parse(R"({"crop":{"x":0.1,"y":0.1,"width":0.8,"height":0.6},"quarterTurns":1,"flipH":true})", expected_geometry, error), error);
    const auto expected = reference(engine, session.get(), identity, "ProPhoto RGB", false, true, expected_geometry, 3);
    OutputTransform xf;
    check(xf.fetch(engine, "ProPhoto RGB", false, error), error); xf.set_exact(true);
    std::vector<uint16_t> file;
    pack_float_rgba16(result.float_pixels.data(), 2, 2, 3, file, nullptr, xf);
    // This in-range identity fixture isolates geometry and readback. A
    // non-linear grade/resize order is intentionally not asserted equivalent.
    check(expected.width == result.width && expected.height == result.height, "geometry parity shape");
    check(max_error16(file, expected) <= 3, "geometry identity output mismatch");
    unsigned rejected = 0;
    for (int i = 0; i < 6; ++i) {
        auto bad = request;
        if (i == 0) bad.no_film = false;
        if (i == 1) bad.adjustments.set("enabled", spk::Json(true));
        if (i == 2) bad.target_space = "sRGB";
        if (i == 3) bad.export_intent = true;
        if (i == 4) bad.proof_long_edge = 2;
        if (i == 5) bad.rgba16 = true;
        spk::view::Result invalid;
        check(!spk::view::render(session.get(), bad, invalid, error) && invalid.float_pixels.empty() && invalid.pixels.empty(), "invalid float request accepted");
        ++rejected;
    }
    std::cout << "geometry PASS crop/turn/flip/resize invalid_requests=" << rejected << '\n';
}
}
int main(int argc, char** argv) {
    try {
        app_keys();
        check(argc >= 2, "resources required");
        std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1], nullptr), spk_engine_destroy);
        check(bool(engine), spk_last_error(nullptr));
        regression(engine.get(), argc > 2 && std::string(argv[2]) == "--legacy");
        matrix(engine.get());
        geometry(engine.get());
        std::cout << "no-film float PASS\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
