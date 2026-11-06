// layer2_local.hpp -- the local half of Layer 2: masks (蒙版) and dust (灰尘 /
// 划痕). Included by layer2.cpp only, so the build lists no new source.
//
// Both arrive after the curve rows in Look.kt's array, which then always
// carries all five rows (identity rows are still "no curves"):
//
//   kMagic, kVersion,
//   dust: strength, count, size, dark share, scratches (0..100 each), seed,
//   mask count n (0..kMaxMasks), then per mask 22 values:
//     kind (0 linear, 1 radial, 2 brush), inverted, amount (0..1),
//     exposure, contrast, brightness, saturation, highlights, shadows,
//     black point, white point, temperature, tint (Look.kt's units),
//     ax, ay, bx, by (frame 0..1), rx, ry (long edge 0..1), angle (deg), feather (0..1),
//     stamp count m (0..kMaxStamps), then m x (x, y, radius, softness, flow);
//     a negative flow erases.
//
// Anything malformed drops the whole block (the global adjustments stay);
// anything after it is ignored, so a later version can append.
//
// Masks are upstream SpektraLab's (Model/Mask.swift, `maskCoverage` and step 8
// of the `layer2` kernel in Canvas/Shaders.metal): a region plus its own tone
// adjustments, the same arithmetic as the global ones, coverage evaluated per
// pixel from the geometry, never rasterised. Distances are in long-edge units
// so an ellipse stays an ellipse on a 3:2 frame. The brush is the one upstream
// left unbuilt; here its stamps are evaluated per pixel through a grid, so the
// preview and the export read the same geometry.
//
// Dust is procedural: every speck, hair and scratch comes from the seed and
// its own index through an integer hash, in long-edge units, so a tier, a
// zoom and the export all show the same set. A speck smaller than a pixel is
// drawn half a pixel wide and fainter by the area it lost, so a preview keeps
// what the export shows. See tools/work/edit-effects_research.md.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace spk::android::local {

constexpr float kMagic = 9157.0f;
constexpr float kVersion = 1.0f;
constexpr int kMaxMasks = 8;
constexpr int kMaxStamps = 4000;
constexpr int kMaskFields = 22;

namespace detail {

constexpr float kMidGrey = 0.3857f;   // layer2.cpp's: ProPhoto's encoded 0.18
constexpr float kTau = 6.28318530718f;

inline float sat01(float x) { return std::min(1.0f, std::max(0.0f, x)); }
inline float luma(const float c[3]) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; }
inline float smooth(float e0, float e1, float x) {
    const float t = sat01((x - e0) / (e1 - e0));
    return t * t * (3.0f - 2.0f * t);
}
inline float tone_position(float l) {
    return l <= kMidGrey ? 0.5f * l / kMidGrey : 0.5f + 0.5f * (l - kMidGrey) / (1.0f - kMidGrey);
}
inline float clampf(float x, float lo, float hi) { return std::min(hi, std::max(lo, x)); }

// lowbias32, as upstream's overscan dust (engine/src/shaders/overscan.metal hash_u).
inline uint32_t mix32(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
    return x;
}

// One stream per item: item i of a kind draws the same numbers whatever the
// count, so more dust adds specks and never moves the ones already there.
struct Rng {
    uint32_t s;
    Rng(uint32_t seed, uint32_t kind, uint32_t i) : s(mix32(mix32(seed * 0x9E3779B9u ^ kind) + i * 0x85EBCA6Bu)) {}
    float next() { s = mix32(s + 0x9E3779B9u); return float(s >> 8) * (1.0f / 16777216.0f); }
};

// Item ids in a cell list (CSR), each item in every cell its box touches.
struct Grid {
    int n = 0;
    std::vector<uint32_t> start;   // n*n + 1
    std::vector<uint32_t> items;

    // `box(i, b)` fills b = {x0, y0, x1, y1} in long-edge units for item i.
    template <typename Box>
    void build(int cells, size_t count, Box&& box) {
        n = cells;
        start.assign(size_t(n) * n + 1, 0);
        items.clear();
        std::vector<std::array<int, 4>> span(count);
        for (size_t i = 0; i < count; ++i) {
            float b[4];
            box(i, b);
            auto& s = span[i];
            s = {std::max(0, int(std::floor(b[0] * n))), std::max(0, int(std::floor(b[1] * n))),
                 std::min(n - 1, int(std::floor(b[2] * n))), std::min(n - 1, int(std::floor(b[3] * n)))};
            for (int y = s[1]; y <= s[3]; ++y)
                for (int x = s[0]; x <= s[2]; ++x) ++start[size_t(y) * n + x + 1];
        }
        for (size_t c = 1; c < start.size(); ++c) start[c] += start[c - 1];
        items.resize(start.back());
        std::vector<uint32_t> fill(start.begin(), start.end() - 1);
        // In item order, so a cell lists its stamps in the order they were painted.
        for (size_t i = 0; i < count; ++i) {
            const auto& s = span[i];
            for (int y = s[1]; y <= s[3]; ++y)
                for (int x = s[0]; x <= s[2]; ++x) items[fill[size_t(y) * n + x]++] = uint32_t(i);
        }
    }
    // The [begin, end) of the cell holding (x, y); empty outside the grid.
    std::pair<const uint32_t*, const uint32_t*> at(float x, float y) const {
        if (n == 0 || !(x >= 0 && y >= 0 && x < 1 && y < 1)) return {nullptr, nullptr};
        const size_t c = size_t(int(y * n)) * n + size_t(int(x * n));
        return {items.data() + start[c], items.data() + start[c + 1]};
    }
};

}  // namespace detail

// The part of Layer 2 a mask carries (upstream MaskAdjustments): white
// balance, exposure, contrast, brightness, highlights/shadows, black/white
// point and saturation, with layer2.cpp's constants and order. Curves,
// colour balance and the vignette are global statements and stay out.
struct Tone {
    float wb[3] = {1, 1, 1};
    float gain = 1, contrast = 0, bright = 0, bright_e = 1;
    float highlights = 0, shadows = 0, black = 0, span = 1, saturation = 1;

    Tone() = default;
    // exposure, contrast, brightness, saturation, highlights, shadows, black, white, temperature, tint
    explicit Tone(const float* a) {
        const float t = a[8] / 100, g = a[9] / 100;
        wb[0] = 1 + 0.18f * t; wb[1] = 1 - 0.12f * g; wb[2] = 1 - 0.18f * t;
        gain = std::pow(2.0f, a[0] / 2.2f);
        contrast = a[1] / 100;
        bright = a[2] / 100;
        bright_e = 1.0f / (1.0f + bright * 0.8f);
        saturation = 1 + a[3] / 100;
        highlights = a[4] / 100;
        shadows = a[5] / 100;
        black = a[6] / 100;
        span = std::max(1 - black - a[7] / 100, 0.05f);
    }

    void apply(float c[3]) const {
        using namespace detail;
        for (int i = 0; i < 3; ++i) c[i] = std::max(0.0f, c[i] * wb[i]) * gain;
        if (contrast != 0) {
            const float k = 1 + contrast * 1.2f;
            for (int i = 0; i < 3; ++i) c[i] = (c[i] - kMidGrey) * k + kMidGrey;
        }
        if (bright != 0)
            for (int i = 0; i < 3; ++i) c[i] = std::pow(std::min(2.0f, std::max(0.0f, c[i])), bright_e);
        const float l = tone_position(luma(c));
        if (shadows != 0) {
            const float w = (1 - l) * (1 - l);
            for (int i = 0; i < 3; ++i) c[i] += shadows * 0.25f * w * (1 - c[i]);
        }
        if (highlights != 0) {
            const float w = l * l;
            for (int i = 0; i < 3; ++i) c[i] += highlights * 0.25f * w * (highlights > 0 ? 1 - c[i] : c[i]);
        }
        for (int i = 0; i < 3; ++i) c[i] = (c[i] - black) / span;
        if (saturation != 1) {
            const float y = luma(c);
            for (int i = 0; i < 3; ++i) c[i] = y + (c[i] - y) * saturation;
        }
    }
};

struct Stamp { float x, y, r, soft, flow; };

// What one frame size needs, built once per pack by Local::place and only
// read while the rows run. Nothing in Local changes after parse, so two packs
// of one Layer 2 (the editor's and an export's) never share mutable state.
struct Placed {
    float aspect = 1.5f, px = 1.0f / 1024, short_side = 2.0f / 3;
    struct MaskGeo {
        float la[2] = {0, 0}, axis[2] = {0, 1}, inv_len2 = 1;   // long-edge units
        std::vector<Stamp> le;
        detail::Grid grid;
    };
    std::vector<MaskGeo> masks;
    std::vector<std::array<float, 4>> hair_box;
    detail::Grid dust;

    void to_le(float u, float v, float& x, float& y) const {
        if (aspect >= 1) { x = u; y = v / aspect; } else { x = u * aspect; y = v; }
    }
};

class Local {
public:
    // The block starting at v[at], or null when there is none or it is malformed.
    static std::shared_ptr<const Local> parse(const std::vector<float>& v, size_t at) {
        using detail::clampf;
        auto have = [&](size_t n) { return at <= v.size() && v.size() - at >= n; };
        auto finite = [&](size_t n) {
            for (size_t i = 0; i < n; ++i) if (!std::isfinite(v[at + i])) return false;
            return true;
        };
        if (!have(9) || v[at] != kMagic || v[at + 1] != kVersion) return nullptr;
        at += 2;
        if (!finite(7)) return nullptr;
        auto out = std::make_shared<Local>();
        out->make_dust(&v[at]);
        at += 6;
        const float n = v[at++];
        if (!(n >= 0 && n <= kMaxMasks) || n != std::floor(n)) return nullptr;
        for (int i = 0; i < int(n); ++i) {
            if (!have(kMaskFields) || !finite(kMaskFields)) return nullptr;
            const float* a = &v[at];
            Mask m;
            if (!(a[0] == 0 || a[0] == 1 || a[0] == 2)) return nullptr;
            m.kind = int(a[0]);
            m.inverted = a[1] != 0;
            m.amount = detail::sat01(a[2]);
            m.tone = Tone(a + 3);
            m.a[0] = a[13]; m.a[1] = a[14]; m.b[0] = a[15]; m.b[1] = a[16];
            m.radii[0] = clampf(a[17], 1e-4f, 4.0f); m.radii[1] = clampf(a[18], 1e-4f, 4.0f);
            const float ang = a[19] * detail::kTau / 360;
            m.cs = std::cos(ang); m.sn = std::sin(ang);
            m.feather = clampf(a[20], 0.001f, 1.0f);
            const float ms = a[21];
            if (!(ms >= 0 && ms <= kMaxStamps) || ms != std::floor(ms)) return nullptr;
            at += kMaskFields;
            const size_t count = size_t(ms);
            if (!have(5 * count) || !finite(5 * count)) return nullptr;
            m.stamps.resize(count);
            for (size_t k = 0; k < count; ++k) {
                const float* s = &v[at + 5 * k];
                // x, y kept to -2..3 like the gradient ends, so Grid's float-to-int cell index stays defined.
                m.stamps[k] = {clampf(s[0], -2.0f, 3.0f), clampf(s[1], -2.0f, 3.0f), clampf(s[2], 1e-4f, 1.0f), clampf(s[3], 0.001f, 1.0f), clampf(s[4], -1.0f, 1.0f)};
            }
            at += 5 * count;
            out->masks_.push_back(std::move(m));
        }
        if (out->masks_.empty() && !out->dusty()) return nullptr;
        return out;
    }

    // The geometry for a w x h frame: masks and dust in long-edge units, their
    // cell lists, and the pixel a sub-pixel speck is widened to.
    std::shared_ptr<const Placed> place(uint32_t w, uint32_t h) const {
        auto p = std::make_shared<Placed>();
        if (w == 0 || h == 0) return p;
        p->aspect = float(w) / float(h);
        p->px = 1.0f / float(std::max(w, h));
        p->short_side = float(std::min(w, h)) / float(std::max(w, h));
        p->masks.resize(masks_.size());
        for (size_t i = 0; i < masks_.size(); ++i) place_mask(*p, masks_[i], p->masks[i]);
        if (dusty()) place_dust(*p);
        return p;
    }

    // Step 8 of upstream's kernel: each mask mixes in its own tone by its coverage.
    void masks(const Placed& p, float c[3], float u, float v) const {
        if (masks_.empty() || p.masks.size() != masks_.size()) return;
        float x, y;
        p.to_le(u, v, x, y);
        for (size_t i = 0; i < masks_.size(); ++i) {
            const float cover = coverage(masks_[i], p.masks[i], x, y);
            if (cover > 0.0005f) {
                float t[3] = {c[0], c[1], c[2]};
                masks_[i].tone.apply(t);
                for (int k = 0; k < 3; ++k) c[k] += (t[k] - c[k]) * cover;
            }
        }
    }

    // White specks go toward the paper's white, dark ones toward black.
    void dust(const Placed& p, float c[3], float u, float v) const {
        if (!dusty() || p.hair_box.size() != hairs_.size()) return;
        using detail::smooth;
        float x, y;
        p.to_le(u, v, x, y);
        float keep_white = 1, keep_dark = 1;   // products of (1 - coverage)
        const float half = 0.5f * p.px;
        auto add = [&](bool dark, float cover) {
            cover *= strength_;
            if (cover <= 0) return;
            (dark ? keep_dark : keep_white) *= 1 - std::min(1.0f, cover);
        };
        auto [b, e] = p.dust.at(x, y);
        for (const uint32_t* it = b; it != e; ++it) {
            const uint32_t id = *it;
            if (id < specks_.size()) {
                const Speck& s = specks_[id];
                const float dx = x - s.x, dy = y - s.y, d2 = dx * dx + dy * dy;
                const float re = std::max(s.r, half), w = std::max(half, s.soft * s.r);
                const float reach = re * 1.6f + w;
                if (d2 >= reach * reach) continue;
                const float d = std::sqrt(d2);
                float edge = re;
                if (d > 1e-9f) {
                    // A ragged outline: the second and third harmonics of the angle, without atan2.
                    const float cx = dx / d, sy = dy / d;
                    const float c2 = cx * cx - sy * sy, s2 = 2 * cx * sy;
                    const float c3 = cx * (4 * cx * cx - 3), s3 = sy * (3 - 4 * sy * sy);
                    edge = re * (1 + s.h2c * c2 + s.h2s * s2 + s.h3c * c3 + s.h3s * s3);
                }
                const float area = (s.r / re) * (s.r / re);
                add(s.dark, s.o * area * (1 - smooth(-w, w, d - edge)));
            } else {
                const size_t hi = id - specks_.size();
                const Hair& h = hairs_[hi];
                const auto& box = p.hair_box[hi];
                if (x < box[0] || y < box[1] || x > box[2] || y > box[3]) continue;
                float best = 1e9f;
                for (int k = 0; k < 4; ++k) {
                    const float ax = h.p[k][0], ay = h.p[k][1];
                    const float ex = h.p[k + 1][0] - ax, ey = h.p[k + 1][1] - ay;
                    const float t = detail::sat01(((x - ax) * ex + (y - ay) * ey) / std::max(ex * ex + ey * ey, 1e-12f));
                    const float qx = x - ax - ex * t, qy = y - ay - ey * t;
                    best = std::min(best, qx * qx + qy * qy);
                }
                const float re = std::max(h.hw, half), w = std::max(half, 0.3f * h.hw);
                add(h.dark, h.o * (h.hw / re) * (1 - smooth(-w, w, std::sqrt(best) - re)));
            }
        }
        if (!scratches_.empty()) {
            // Along the film's transport: the frame's long edge.
            const float t = p.aspect >= 1 ? u : v, s = p.aspect >= 1 ? v : u;
            const float k = detail::sat01(t) * (kScratchSamples - 1);
            const int i0 = std::min(kScratchSamples - 2, int(k));
            const float f = k - float(i0);
            for (const auto& sc : scratches_) {
                const float vis = sc.vis[i0] + (sc.vis[i0 + 1] - sc.vis[i0]) * f;
                if (vis <= 0) continue;
                const float pos = sc.pos[i0] + (sc.pos[i0 + 1] - sc.pos[i0]) * f;
                const float re = std::max(sc.hw, half), w = std::max(half, 0.3f * sc.hw);
                const float d = std::fabs(s - pos) * p.short_side;
                if (d > re + w) continue;
                add(sc.dark, sc.o * vis * (sc.hw / re) * (1 - smooth(-w, w, d - re)));
            }
        }
        if (keep_white < 1) for (int i = 0; i < 3; ++i) c[i] = c[i] * keep_white + kWhite * (1 - keep_white);
        if (keep_dark < 1) for (int i = 0; i < 3; ++i) c[i] = c[i] * keep_dark + kDark * (1 - keep_dark);
    }

private:
    static constexpr int kScratchSamples = 512;
    static constexpr float kWhite = 0.97f, kDark = 0.03f;   // encoded ProPhoto: the paper's white, a print's black

    struct Mask {
        int kind = 0;
        bool inverted = false;
        float amount = 1;
        Tone tone;
        float a[2] = {0, 0}, b[2] = {0, 0}, radii[2] = {0.3f, 0.3f}, cs = 1, sn = 0, feather = 0.5f;
        std::vector<Stamp> stamps;   // x, y in the frame's 0..1
    };
    struct Speck { float x, y, r, o, soft, h2c, h2s, h3c, h3s; bool dark; };
    struct Hair { float p[5][2]; float hw, o; bool dark; };
    struct Scratch { std::array<float, kScratchSamples> pos, vis; float hw, o; bool dark; };

    bool dusty() const { return strength_ > 0 && (!specks_.empty() || !scratches_.empty()); }

    static float coverage(const Mask& m, const Placed::MaskGeo& g, float x, float y) {
        using detail::smooth;
        float c = 0;
        if (m.kind == 0) {
            // Upstream's linearCoverage: 0 before a, 1 past b.
            const float t = ((x - g.la[0]) * g.axis[0] + (y - g.la[1]) * g.axis[1]) * g.inv_len2;
            c = smooth(0.0f, 1.0f, detail::sat01(t));
        } else if (m.kind == 1) {
            // radialCoverage: into the ellipse's frame, normalised so r is 1 on its edge.
            const float dx = x - g.la[0], dy = y - g.la[1];
            const float ex = (dx * m.cs + dy * m.sn) / m.radii[0], ey = (-dx * m.sn + dy * m.cs) / m.radii[1];
            c = 1 - smooth(1 - m.feather, 1.0f, std::sqrt(ex * ex + ey * ey));
        } else {
            // Stamps in the order painted: paint goes over, erasing takes away.
            auto [b, e] = g.grid.at(x, y);
            for (const uint32_t* it = b; it != e; ++it) {
                const Stamp& s = g.le[*it];
                const float dx = x - s.x, dy = y - s.y, d2 = dx * dx + dy * dy;
                if (d2 >= s.r * s.r) continue;
                const float a = std::fabs(s.flow) * (1 - smooth(1 - s.soft, 1.0f, std::sqrt(d2) / s.r));
                c = s.flow > 0 ? c + a * (1 - c) : c * (1 - a);
            }
        }
        if (m.inverted) c = 1 - c;
        return detail::sat01(c) * m.amount;
    }

    static void place_mask(const Placed& p, const Mask& m, Placed::MaskGeo& g) {
        p.to_le(m.a[0], m.a[1], g.la[0], g.la[1]);
        float lb[2];
        p.to_le(m.b[0], m.b[1], lb[0], lb[1]);
        g.axis[0] = lb[0] - g.la[0]; g.axis[1] = lb[1] - g.la[1];
        g.inv_len2 = 1.0f / std::max(g.axis[0] * g.axis[0] + g.axis[1] * g.axis[1], 1e-9f);
        if (m.kind != 2) return;
        g.le = m.stamps;
        for (auto& s : g.le) p.to_le(s.x, s.y, s.x, s.y);
        g.grid.build(48, g.le.size(), [&](size_t i, float b[4]) {
            const Stamp& s = g.le[i];
            b[0] = s.x - s.r; b[1] = s.y - s.r; b[2] = s.x + s.r; b[3] = s.y + s.r;
        });
    }

    // From the array's six dust values. Positions are over the long-edge unit
    // square, which every frame shape fits inside: the count per area is the
    // same whatever the crop.
    void make_dust(const float* d) {
        using detail::clampf;
        strength_ = clampf(d[0], 0, 100) / 100;
        if (strength_ <= 0) return;
        const float count = clampf(d[1], 0, 100) / 100;
        const float size = std::pow(2.0f, (clampf(d[2], 0, 100) - 50) / 25);   // 1/4x .. 4x
        const float dark = clampf(d[3], 0, 100) / 100;
        const float scratches = clampf(d[4], 0, 100) / 100;
        const uint32_t seed = uint32_t(clampf(d[5], 0, 16777215));

        // Specks: many small, few large (a Pareto tail; the defaults put most
        // between 0.06 % and 0.4 % of the long edge across), a few soft clumps.
        const int specks = int(std::lround(800 * count * count));
        specks_.reserve(size_t(specks));
        for (int i = 0; i < specks; ++i) {
            detail::Rng g(seed, 1, uint32_t(i));
            Speck s{};
            s.x = g.next(); s.y = g.next();
            float r = std::min(0.002f, 0.0003f * std::pow(1 - 0.999f * g.next(), -1 / 2.2f));
            const bool clump = g.next() < 0.06f;
            s.dark = g.next() < dark;
            s.o = s.dark ? 0.55f + 0.3f * g.next() : 0.75f + 0.25f * g.next();
            const float a2 = 0.35f * g.next(), p2 = detail::kTau * g.next();
            const float a3 = 0.2f * g.next(), p3 = detail::kTau * g.next();
            if (clump) { r *= 2.5f; s.o *= 0.55f; }
            s.r = r * size;
            s.soft = clump ? 0.5f : s.dark ? 0.45f : 0.18f;   // dark dust sits off the image plane
            s.h2c = a2 * std::cos(p2); s.h2s = a2 * std::sin(p2);
            s.h3c = a3 * std::cos(p3); s.h3s = a3 * std::sin(p3);
            specks_.push_back(s);
        }
        // Hairs and lint: a curved strand, a quadratic Bezier as four segments.
        const int hairs = specks > 0 ? std::max(1, int(std::lround(specks / 20.0))) : 0;
        hairs_.reserve(size_t(hairs));
        for (int i = 0; i < hairs; ++i) {
            detail::Rng g(seed, 2, uint32_t(i));
            Hair h{};
            const float x0 = g.next(), y0 = g.next(), th = detail::kTau * g.next();
            const float len = size * (0.012f + 0.05f * g.next() * g.next());
            const float bend = (g.next() - 0.5f) * 0.7f * len;
            h.hw = size * (0.0002f + 0.00025f * g.next());
            h.dark = g.next() < dark;
            h.o = 0.5f + 0.4f * g.next();
            const float ux = std::cos(th), uy = std::sin(th);
            const float x1 = x0 + len * ux, y1 = y0 + len * uy;
            const float cx = (x0 + x1) / 2 - bend * uy, cy = (y0 + y1) / 2 + bend * ux;
            for (int k = 0; k < 5; ++k) {
                const float t = k / 4.0f, a = (1 - t) * (1 - t), b = 2 * (1 - t) * t, c = t * t;
                h.p[k][0] = a * x0 + b * cx + c * x1;
                h.p[k][1] = a * y0 + b * cy + c * y1;
            }
            hairs_.push_back(h);
        }
        // Scratches run along the transport with a slow sideways wander and
        // break up along their length; base-side ones print white, so few are dark.
        const int lines = int(std::lround(8 * scratches));
        scratches_.reserve(size_t(lines));
        for (int i = 0; i < lines; ++i) {
            detail::Rng g(seed, 3, uint32_t(i));
            Scratch sc{};
            const float s0 = 0.03f + 0.94f * g.next();
            const float a1 = 0.004f * g.next(), f1 = 0.5f + 1.5f * g.next(), q1 = detail::kTau * g.next();
            const float a2 = 0.0015f * g.next(), f2 = 3 + 6 * g.next(), q2 = detail::kTau * g.next();
            sc.hw = std::sqrt(size) * (0.00012f + 0.0003f * g.next());
            sc.o = 0.3f + 0.45f * g.next();
            sc.dark = g.next() < dark * 0.5f;
            const float t0 = g.next() * 0.5f - 0.1f, t1 = t0 + 0.4f + 0.8f * g.next();
            float noise[10];
            for (float& n : noise) n = g.next();
            for (int k = 0; k < kScratchSamples; ++k) {
                const float t = float(k) / (kScratchSamples - 1);
                sc.pos[k] = s0 + a1 * std::sin(detail::kTau * f1 * t + q1) + a2 * std::sin(detail::kTau * f2 * t + q2);
                const float nx = t * 8, nf = nx - std::floor(nx);
                const int ni = std::min(8, int(nx));
                const float w = nf * nf * (3 - 2 * nf);
                const float n = noise[ni] + (noise[ni + 1] - noise[ni]) * w;
                sc.vis[k] = detail::smooth(t0, t0 + 0.02f, t) * (1 - detail::smooth(t1 - 0.02f, t1, t)) *
                            detail::smooth(0.3f, 0.55f, n);
            }
            scratches_.push_back(sc);
        }
    }

    // The grid's reach depends on the pixel (a sub-pixel speck is drawn half a pixel wide).
    void place_dust(Placed& p) const {
        const float half = 0.5f * p.px;
        p.hair_box.resize(hairs_.size());
        for (size_t i = 0; i < hairs_.size(); ++i) {
            const Hair& h = hairs_[i];
            auto& box = p.hair_box[i];
            const float m = std::max(h.hw, half) + std::max(half, 0.3f * h.hw);
            box = {1e9f, 1e9f, -1e9f, -1e9f};
            for (const auto& q : h.p) {
                box[0] = std::min(box[0], q[0] - m); box[1] = std::min(box[1], q[1] - m);
                box[2] = std::max(box[2], q[0] + m); box[3] = std::max(box[3], q[1] + m);
            }
        }
        p.dust.build(32, specks_.size() + hairs_.size(), [&](size_t i, float b[4]) {
            if (i < specks_.size()) {
                const Speck& s = specks_[i];
                const float reach = std::max(s.r, half) * 1.6f + std::max(half, s.soft * s.r);
                b[0] = s.x - reach; b[1] = s.y - reach; b[2] = s.x + reach; b[3] = s.y + reach;
            } else {
                for (int k = 0; k < 4; ++k) b[k] = p.hair_box[i - specks_.size()][k];
            }
        });
    }

    std::vector<Mask> masks_;
    float strength_ = 0;
    std::vector<Speck> specks_;
    std::vector<Hair> hairs_;
    std::vector<Scratch> scratches_;
};

}  // namespace spk::android::local
