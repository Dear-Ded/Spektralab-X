// passthrough.hpp -- 无: the photo without a film, and a negative without a paper.
//
// Two renders the engine's public spk_reprint does not make, both taken from
// upstream's own desktop view path (pipeline/view.hpp, `spk::view::render`):
//
//   no_film  胶片「无」, upstream's Sidecar.noFilm. The session's decoded
//            source (linear ProPhoto, as every session here is opened) encoded
//            as ProPhoto and quantised at the same 16-bit boundary a print
//            leaves at. Crop, quarter turns and flip come with the request,
//            since the engine's own geometry runs inside the film stages.
//   di       相纸「无」on a negative: the Digital Intermediate (RFC-028). The
//            print stage writes Cineon codes; the view reads them through
//            upstream's DI view table (resources/DI, spk_view_grade_di) back
//            into encoded ProPhoto, as the Mac and desktop canvases show it.
//
// Both are asked for in encoded ProPhoto RGBA16 with the view's own Layer 2
// off, so the result is what spk_reprint returns for a print: everything after
// (layer2.cpp, output_transform.cpp, repack, TIFF) is unchanged. Header-only,
// so the build needs no new source file.
#pragma once

#include <cstdlib>
#include <cstring>
#include <string>

#include "json.hpp"
#include "pipeline/view.hpp"
#include "spektrafilm/spk_engine.h"

namespace spk::android {

// The app-side key carrying 胶片「无」(Edit.noFilm). Never sent to the engine,
// which refuses a parameter it does not know.
constexpr const char* kNoFilmKey = "x_no_film";

// What the JNI needs to know about a session's params to pick its render.
struct ViewFlags {
    bool no_film = false;
    bool di_switch = false;   // digital_intermediate, as last sent
    bool scan = false;        // scan_film, as last sent; it wins over the DI
    // The app clears digital_intermediate on a slide (Catalog.normalize), so
    // the switch and scan_film alone say whether the engine prints a DI.
    bool di() const { return di_switch && !scan; }
};

// Takes kNoFilmKey off `json` (an open's params or a set_params delta) and
// notes it and the DI switches in `flags`. `json` is rewritten only when the
// key is there; the rewrite is the engine's own Json, whose numbers round-trip
// at 17 digits, so what the engine reads is the same either way.
inline bool take_app_keys(std::string& json, ViewFlags& flags, std::string& error) {
    if (json.find(kNoFilmKey) == std::string::npos &&
        json.find("digital_intermediate") == std::string::npos &&
        json.find("scan_film") == std::string::npos) return true;
    spk::Json parsed;
    if (!spk::Json::parse(json, parsed, error)) return false;
    if (!parsed.is_object()) { error = "params must be an object"; return false; }
    const spk::Json& di = parsed.at("digital_intermediate");
    if (di.is_bool()) flags.di_switch = di.as_bool();
    const spk::Json& scan = parsed.at("scan_film");
    if (scan.is_bool()) flags.scan = scan.as_bool();
    if (!parsed.has(kNoFilmKey)) return true;
    flags.no_film = parsed.at(kNoFilmKey).as_bool(false);
    spk::Json rest = spk::Json::object();
    for (const auto& kv : parsed.fields())
        if (kv.first != kNoFilmKey) rest.set(kv.first, kv.second);
    json = rest.dump();
    return true;
}

// `reply` (spk_get_params JSON) with kNoFilmKey added, so Edit.fromParams
// reads the session's 无 back.
inline std::string with_app_keys(const std::string& reply, const ViewFlags& flags) {
    if (reply.size() < 2 || reply.front() != '{') return reply;
    const std::string field = std::string("\"") + kNoFilmKey + "\":" + (flags.no_film ? "true" : "false");
    return "{" + field + (reply.size() > 2 ? "," : "") + reply.substr(1);
}

// One view render of `tier` into `out`, shaped as spk_reprint's result: tightly
// packed encoded ProPhoto RGBA16. On the Vulkan backend a result's texture is
// a malloc'd CPU copy that spk_result_free std::free's (vulkan_gpu.cpp,
// Gpu::release_texture_static), and rgba16 points at it; this result is
// allocated the same way.
inline bool render_view(spk_session* session, const std::string& tier, bool no_film,
                        spk_result& out, std::string& error) {
    std::memset(&out, 0, sizeof out);
    spk::view::Request request;
    request.tier = tier;
    request.adjustments = spk::Json::object();
    request.adjustments.set("enabled", spk::Json(false));
    request.target_space = "ProPhoto RGB";   // identity: the codes leave as encoded ProPhoto
    request.export_intent = false;
    request.rgba16 = true;
    request.output_channels = 4;
    request.no_film = no_film;
    request.geometry = spk::Json::object();
    if (no_film) {
        // The session's own crop and turns: on the film path the engine applies
        // them before the film; the decoded source has not been through them.
        char* text = nullptr;
        if (spk_get_params(session, &text) != SPK_OK || !text) { error = "no film: cannot read the geometry"; return false; }
        spk::Json p;
        const bool parsed = spk::Json::parse(text, p, error);
        spk_string_free(text);
        if (!parsed) return false;
        spk::Json crop = spk::Json::object();
        crop.set("x", spk::Json(p.at("geometry_crop_x").as_double(0)));
        crop.set("y", spk::Json(p.at("geometry_crop_y").as_double(0)));
        crop.set("width", spk::Json(p.at("geometry_crop_w").as_double(1)));
        crop.set("height", spk::Json(p.at("geometry_crop_h").as_double(1)));
        request.geometry.set("crop", std::move(crop));
        request.geometry.set("angle", spk::Json(p.at("geometry_rotation_deg").as_double(0)));
        request.geometry.set("quarterTurns", spk::Json(double(p.at("geometry_quarter_turns").as_int(0))));
        request.geometry.set("flipH", spk::Json(p.at("geometry_flip_h").as_bool(false)));
        request.geometry.set("flipV", spk::Json(p.at("geometry_flip_v").as_bool(false)));
    }
    spk::view::Result result;
    if (!spk::view::render(session, request, result, error)) return false;
    const size_t bytes = size_t(result.width) * result.height * 4 * sizeof(uint16_t);
    if (!result.width || !result.height || result.pixels.size() != bytes) {
        error = "view returned no pixels"; return false;
    }
    void* pixels = std::malloc(bytes);
    if (!pixels) { error = "cannot allocate the view's pixels"; return false; }
    std::memcpy(pixels, result.pixels.data(), bytes);
    out.texture = pixels;
    out.rgba16 = static_cast<const uint16_t*>(pixels);
    out.width = result.width;
    out.height = result.height;
    out.row_stride_px = result.width;
    out.elapsed_ms = result.print_ms + result.post_ms + result.readback_ms;
    return true;
}

}  // namespace spk::android
