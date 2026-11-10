// Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
// Include the production TU to inspect its actual table and mask offset, then
// exercise the same Layer2 public API used by output packing.
#include "layer2.cpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>

using namespace spk::android;
using Points = std::vector<std::array<double, 2>>;

static void require(bool pass, const char* message) {
    if (!pass) { std::fprintf(stderr, "curve wire: %s\n", message); std::exit(1); }
}

static Points points(bool dense, int row) {
    if (dense) return {{0, 0}, {0.5, 0.3}, {std::nextafter(0.5, 1.0), 0.8}, {1, 1}};
    return {{0, 0.01 * row}, {0.25, 0.19}, {0.75, 0.83}, {1, 1}};
}

static void append_points(std::vector<float>& values, const Points& p) {
    values.push_back(float(p.size()));
    for (const auto& q : p) { values.push_back(float(q[0])); values.push_back(float(q[1])); }
}

static void append_mask(std::vector<float>& values) {
    values.insert(values.end(), {9157, 1, 0, 0, 0, 0, 0, 0, 1});
    std::array<float, 22> mask{};
    mask[2] = 1; mask[3] = 1; // Full opacity, one stop.
    mask[13] = 0; mask[14] = 0.5f; mask[15] = 1; mask[16] = 0.5f;
    mask[17] = mask[18] = 0.3f; mask[20] = 0.5f;
    values.insert(values.end(), mask.begin(), mask.end());
}

static std::vector<float> fixture(unsigned sampled, std::vector<float>& expected) {
    std::vector<float> values(kLookSize, 0);
    expected.clear();
    for (int row = 0; row < 5; ++row) {
        const bool dense = (sampled & (1u << row)) != 0;
        auto p = points(dense, row);
        if (!dense) {
            append_points(values, p);
            // Legacy rows are intentionally evaluated from the Float wire.
            for (auto& q : p) { q[0] = float(q[0]); q[1] = float(q[1]); }
        } else {
            require(float(p[1][0]) == float(p[2][0]), "fixture must collide in Float");
            values.push_back(-256.0f);
        }
        const ToneCurve curve(p);
        for (int i = 0; i < 256; ++i) {
            const float y = float(curve.at(double(i) / 255));
            expected.push_back(y);
            if (dense) values.push_back(y);
        }
    }
    return values;
}

static std::array<float, 3> pixel(const Layer2& layer, const Layer2::Frame& frame, float u) {
    std::array<float, 3> c{0.2f, 0.3f, 0.4f};
    layer.apply(c[0], c[1], c[2], u, 0.5f, frame);
    return c;
}

int main() {
    size_t exact = 0, malformed = 0;
    for (unsigned sampled = 0; sampled < 32; ++sampled) {
        std::vector<float> expected;
        auto values = fixture(sampled, expected);
        const auto table = spk::android::curve_table(values);
        require(table.size() == expected.size(), "a row silently dropped the curve table");
        require(std::memcmp(table.data(), expected.data(), expected.size() * sizeof(float)) == 0,
                "table differs from Double samples or legacy Float point math");
        require(spk::android::curves_end(values) == values.size(), "curve row end offset");
        exact += table.size();
        Layer2 plain(values);
        require(!plain.identity(), "active curves became identity");
        const size_t local_at = values.size();
        append_mask(values);
        require(spk::android::curves_end(values) == local_at, "mask tail offset");
        Layer2 masked(values);
        const auto frame = masked.frame(640, 480);
        require(bool(frame.placed), "mask disappeared after sampled rows");
        const auto before = pixel(plain, {}, 0);
        const auto zero = pixel(masked, frame, 0);
        const auto full = pixel(masked, frame, 1);
        require(std::memcmp(before.data(), zero.data(), sizeof(before)) == 0, "zero mask coverage changed RGB");
        const float gain = std::pow(2.0f, 1.0f / 2.2f);
        for (size_t i = 0; i < 3; ++i)
            require(std::isfinite(full[i]) && full[i] > before[i] &&
                    std::fabs(full[i] - before[i] * gain) <= 1e-7f,
                    "mask at full coverage lost its one-stop adjustment");
    }
    for (int row = 0; row < 5; ++row) {
        std::vector<float> expected;
        const auto valid = fixture(1u << row, expected);
        const size_t marker = kLookSize + size_t(row) * 9;
        require(valid[marker] == -256.0f, "bad fixture marker offset");
        for (float bad : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity(), -0.1f, 1.1f}) {
            auto values = valid;
            values[marker + 128] = bad;
            append_mask(values);
            require(spk::android::curve_table(values).empty(), "invalid sampled value accepted");
            require(spk::android::curves_end(values) == 0, "invalid sampled row exposed the local tail");
            require(Layer2(values).identity(), "invalid sampled row affected the image");
            ++malformed;
        }
        for (float bad : {-255.0f, -257.0f}) {
            auto values = valid;
            values[marker] = bad;
            require(spk::android::curve_table(values).empty() && spk::android::curves_end(values) == 0,
                    "unknown sampled row marker accepted");
            ++malformed;
        }
        auto truncated = valid;
        truncated.resize(marker + 256);
        require(spk::android::curve_table(truncated).empty() && spk::android::curves_end(truncated) == 0,
                "truncated sample row accepted");
        ++malformed;
    }
    // Reproduce the old Float ABI failure rather than assuming this fixture is benign.
    std::vector<float> collapsed(kLookSize, 0);
    append_points(collapsed, points(true, 0));
    for (int row = 1; row < 5; ++row) append_points(collapsed, points(false, row));
    require(spk::android::curve_table(collapsed).empty(), "old collision fixture no longer represents the bug");
    std::printf("curve wire: 32 row combinations, %zu exact table floats, 32 active masks, %zu malformed rows rejected; old collision reproduced\n",
                exact, malformed);
}
