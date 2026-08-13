#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <optional>

namespace Gahyeon {

struct VoiceActivityConfig {
    double StartThreshold = 0.04;
    double StopThreshold = 0.02;
    Millis AttackMs = 30;
    Millis ReleaseMs = 350;
};

enum class VoiceActivityEvent {
    None,
    Started,
    Ended,
    Invalid,
};

/** Deterministic RMS gate with hysteresis; no network/model dependency. */
class GAHYEON_RUNTIME_CORE_API VoiceActivityDetector {
public:
    explicit VoiceActivityDetector(VoiceActivityConfig config = {});

    VoiceActivityEvent Observe(double level, Millis nowMs);
    void Reset();
    bool Active() const;

private:
    VoiceActivityConfig config_;
    bool active_ = false;
    std::optional<Millis> candidateSince_;
    std::optional<Millis> lastObservedAt_;
};

} // namespace Gahyeon
