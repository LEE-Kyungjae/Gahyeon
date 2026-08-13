#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstdint>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace Gahyeon {

struct GestureDefinition {
    std::string Semantic;
    std::string VariantId;
    std::optional<std::string> RequiredPosture;
    double MinIntensity = 0.0;
    double MaxIntensity = 1.0;
    Millis DurationMs = 1'000;
    Millis CooldownMs = 0;
    bool Interruptible = true;
    double SelectionWeight = 1.0;
};

struct GestureIntent {
    std::string Semantic;
    double Intensity = 1.0;
    std::string CurrentPosture;
    int Priority = 0;
    std::optional<Generation> GenerationId;
};

struct ActiveGesture {
    std::string Semantic;
    std::string VariantId;
    double Intensity = 0.0;
    Millis StartedAtMs = 0;
    Millis EndsAtMs = 0;
    int Priority = 0;
    bool Interruptible = true;
    std::optional<Generation> GenerationId;
};

enum class GestureRequestResult {
    Selected,
    Busy,
    NoCandidate,
    Stale,
    Invalid,
    NonMonotonic,
};

/** Deterministic, data-driven semantic gesture variant selector. */
class GAHYEON_RUNTIME_CORE_API GestureRuntime {
public:
    explicit GestureRuntime(
        std::vector<GestureDefinition> definitions,
        std::uint64_t seed = 1);

    GestureRequestResult Request(const GestureIntent& intent, Millis nowMs);
    /** Atomically replaces character-local presentation definitions. */
    bool ConfigureDefinitions(std::vector<GestureDefinition> definitions);
    bool Advance(Millis nowMs);
    std::optional<std::string> SetGeneration(Generation generation);

    Generation CurrentGeneration() const;
    const std::optional<ActiveGesture>& Active() const;

private:
    static bool ValidDefinition(const GestureDefinition& definition);
    std::uint64_t NextRandom();

    std::vector<GestureDefinition> definitions_;
    std::uint64_t randomState_;
    Generation generation_ = 0;
    std::optional<Millis> lastObservedAtMs_;
    std::optional<ActiveGesture> active_;
    std::unordered_map<std::string, Millis> lastUsedAtMs_;
};

} // namespace Gahyeon
