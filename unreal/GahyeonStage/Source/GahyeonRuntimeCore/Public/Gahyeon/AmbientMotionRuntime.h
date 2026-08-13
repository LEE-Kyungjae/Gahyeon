#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstdint>

namespace Gahyeon {

struct AmbientMotionConfig {
    Millis BreathPeriodMs = 4'200;
    Millis BlinkDurationMs = 140;
    Millis BlinkMinIntervalMs = 2'500;
    Millis BlinkMaxIntervalMs = 5'500;
    Millis SaccadeDurationMs = 90;
    Millis SaccadeMinIntervalMs = 450;
    Millis SaccadeMaxIntervalMs = 1'800;
    Millis WeightShiftPeriodMs = 12'000;
};

struct AmbientMotionSample {
    double Breath = 0.0;       // 0..1
    double Blink = 0.0;        // 0=open, 1=closed
    double EyeYaw = 0.0;       // normalized -1..1
    double EyePitch = 0.0;     // normalized -1..1
    double MicroHeadYaw = 0.0; // normalized -1..1
    double MicroHeadPitch = 0.0;
    double WeightShift = 0.0;  // normalized -1..1
};

/** Deterministic always-on secondary motion source; owns no engine or network objects. */
class GAHYEON_RUNTIME_CORE_API AmbientMotionRuntime {
public:
    explicit AmbientMotionRuntime(
        Millis startedAtMs = 0,
        std::uint64_t seed = 0x47414859454f4eULL,
        AmbientMotionConfig config = {});

    AmbientMotionSample Sample(Millis nowMs);

private:
    double NextUnit();
    Millis RandomInterval(Millis minimum, Millis maximum);
    double CurrentEye(double from, double to, Millis nowMs) const;
    void AdvanceBlinkSchedule(Millis nowMs);
    void AdvanceSaccadeSchedule(Millis nowMs);

    AmbientMotionConfig config_;
    Millis startedAtMs_;
    Millis lastSampleAtMs_;
    std::uint64_t randomState_;
    Millis nextBlinkAtMs_;
    Millis saccadeStartedAtMs_;
    Millis nextSaccadeAtMs_;
    double eyeFromYaw_ = 0.0;
    double eyeFromPitch_ = 0.0;
    double eyeTargetYaw_ = 0.0;
    double eyeTargetPitch_ = 0.0;
};

} // namespace Gahyeon
