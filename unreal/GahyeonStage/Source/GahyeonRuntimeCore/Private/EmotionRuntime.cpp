#include "Gahyeon/EmotionRuntime.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>

namespace Gahyeon {

EmotionRuntime::EmotionRuntime(std::size_t maxDimensions)
    : maxDimensions_(maxDimensions) {
    if (maxDimensions_ == 0) {
        throw std::invalid_argument("emotion dimension capacity must be positive");
    }
}

EmotionApplyResult EmotionRuntime::ApplyTarget(EmotionTarget target, Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return EmotionApplyResult::NonMonotonic;
    }
    if (target.Dimensions.empty() || target.Dimensions.size() > maxDimensions_
        || target.BlendMs < 0 || target.BlendMs > 5'000
        || (target.HoldMs.has_value()
            && (target.HoldMs.value() < 0 || target.HoldMs.value() > 600'000))
        || !ValidSignedUnit(target.Valence) || !ValidSignedUnit(target.Arousal)
        || !ValidSignedUnit(target.Dominance)) {
        return EmotionApplyResult::Invalid;
    }
    for (const auto& [name, value] : target.Dimensions) {
        if (name.empty() || !ValidUnit(value)) return EmotionApplyResult::Invalid;
    }
    from_ = Evaluate(nowMs);
    target_ = std::move(target);
    targetStartedAtMs_ = nowMs;
    lastObservedAtMs_ = nowMs;
    lastSample_ = from_;
    return EmotionApplyResult::Applied;
}

EmotionSample EmotionRuntime::Sample(Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return lastSample_;
    }
    lastObservedAtMs_ = nowMs;
    lastSample_ = Evaluate(nowMs);
    return lastSample_;
}

bool EmotionRuntime::ValidUnit(double value) {
    return std::isfinite(value) && value >= 0.0 && value <= 1.0;
}

bool EmotionRuntime::ValidSignedUnit(const std::optional<double>& value) {
    return !value.has_value() || (std::isfinite(value.value())
        && value.value() >= -1.0 && value.value() <= 1.0);
}

double EmotionRuntime::Lerp(double from, double to, double alpha) {
    return from + (to - from) * Clamp01(alpha);
}

double EmotionRuntime::Clamp01(double value) {
    return std::max(0.0, std::min(1.0, value));
}

EmotionSample EmotionRuntime::Evaluate(Millis nowMs) const {
    if (!targetStartedAtMs_.has_value()) return lastSample_;
    const Millis elapsed = nowMs >= targetStartedAtMs_.value()
        ? nowMs - targetStartedAtMs_.value()
        : 0;
    const Millis blend = target_.BlendMs;
    const double blendAlpha = blend == 0
        ? 1.0
        : Clamp01(static_cast<double>(elapsed) / static_cast<double>(blend));

    EmotionSample targetSample;
    targetSample.Dimensions = target_.Dimensions;
    targetSample.Valence = target_.Valence.value_or(0.0);
    targetSample.Arousal = target_.Arousal.value_or(0.0);
    targetSample.Dominance = target_.Dominance.value_or(0.0);

    EmotionSample blended;
    blended.Valence = Lerp(from_.Valence, targetSample.Valence, blendAlpha);
    blended.Arousal = Lerp(from_.Arousal, targetSample.Arousal, blendAlpha);
    blended.Dominance = Lerp(from_.Dominance, targetSample.Dominance, blendAlpha);
    std::map<std::string, double> names = from_.Dimensions;
    names.insert(targetSample.Dimensions.begin(), targetSample.Dimensions.end());
    for (const auto& [name, ignored] : names) {
        (void)ignored;
        const double from = from_.Dimensions.contains(name) ? from_.Dimensions.at(name) : 0.0;
        const double to = targetSample.Dimensions.contains(name)
            ? targetSample.Dimensions.at(name) : 0.0;
        const double value = Lerp(from, to, blendAlpha);
        if (value > 0.0001) blended.Dimensions.emplace(name, value);
    }

    if (!target_.HoldMs.has_value()) return blended;
    const Millis holdStart = blend;
    const Millis hold = target_.HoldMs.value();
    const Millis releaseStart = holdStart > std::numeric_limits<Millis>::max() - hold
        ? std::numeric_limits<Millis>::max()
        : holdStart + hold;
    if (elapsed <= releaseStart) return blended;

    const Millis releasingFor = elapsed - releaseStart;
    const Millis releaseDuration = std::max<Millis>(1, blend);
    const double remaining = 1.0 - Clamp01(
        static_cast<double>(releasingFor) / static_cast<double>(releaseDuration));
    blended.Releasing = remaining > 0.0;
    blended.Valence *= remaining;
    blended.Arousal *= remaining;
    blended.Dominance *= remaining;
    for (auto iterator = blended.Dimensions.begin(); iterator != blended.Dimensions.end();) {
        iterator->second *= remaining;
        if (iterator->second <= 0.0001) iterator = blended.Dimensions.erase(iterator);
        else ++iterator;
    }
    return blended;
}

} // namespace Gahyeon
