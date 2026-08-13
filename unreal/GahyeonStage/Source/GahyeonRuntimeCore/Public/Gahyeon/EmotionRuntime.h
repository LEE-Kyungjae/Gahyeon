#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <map>
#include <optional>
#include <string>

namespace Gahyeon {

struct EmotionTarget {
    std::map<std::string, double> Dimensions;
    std::optional<double> Valence;
    std::optional<double> Arousal;
    std::optional<double> Dominance;
    Millis BlendMs = 250;
    std::optional<Millis> HoldMs;
};

struct EmotionSample {
    std::map<std::string, double> Dimensions;
    double Valence = 0.0;
    double Arousal = 0.0;
    double Dominance = 0.0;
    bool Releasing = false;
};

enum class EmotionApplyResult {
    Applied,
    Invalid,
    NonMonotonic,
};

/** Continuous, phase-independent semantic emotion blending. */
class GAHYEON_RUNTIME_CORE_API EmotionRuntime {
public:
    explicit EmotionRuntime(std::size_t maxDimensions = 16);

    EmotionApplyResult ApplyTarget(EmotionTarget target, Millis nowMs);
    EmotionSample Sample(Millis nowMs);

private:
    static bool ValidUnit(double value);
    static bool ValidSignedUnit(const std::optional<double>& value);
    static double Lerp(double from, double to, double alpha);
    static double Clamp01(double value);
    EmotionSample Evaluate(Millis nowMs) const;

    std::size_t maxDimensions_;
    EmotionSample from_;
    EmotionTarget target_;
    std::optional<Millis> targetStartedAtMs_;
    std::optional<Millis> lastObservedAtMs_;
    EmotionSample lastSample_;
};

} // namespace Gahyeon
