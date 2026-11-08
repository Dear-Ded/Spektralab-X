// Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
// Copyright (C) 2026 Spektralab-X contributors
// Additional terms under GPL-3.0 section 7 apply; see NOTICE.
#pragma once

#include "layer2.hpp"

#include <utility>

namespace spk::android {

// Build before committing: allocation failures must leave the last usable look
// intact. Sessions serialize these calls with their own render/pack operations.
class LookState {
public:
    const Layer2* active() const { return look_.get(); }

    void set_values(std::vector<float> values) {
        auto candidate = build(values, lut_, strength_);
        values_.swap(values);
        look_.swap(candidate);
    }

    void set_lut(std::shared_ptr<const ColourTable> lut, float strength) {
        auto candidate = build(values_, lut, strength);
        lut_ = std::move(lut);
        strength_ = strength;
        look_.swap(candidate);
    }

private:
    static std::unique_ptr<Layer2> build(const std::vector<float>& values,
                                        const std::shared_ptr<const ColourTable>& lut, float strength) {
        auto result = std::make_unique<Layer2>(values);
        if (lut) result->set_lut(lut, strength);
        if (result->identity()) return nullptr;
        return result;
    }

    std::unique_ptr<Layer2> look_;
    std::vector<float> values_;
    std::shared_ptr<const ColourTable> lut_;
    float strength_ = 0;
};

}  // namespace spk::android
