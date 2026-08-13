#pragma once

#include "Gahyeon/IntentRuntime.h"

namespace Gahyeon {

struct AttentionConfig {
    double MaxEyeYawDegrees = 35.0;
    double MaxEyePitchDegrees = 20.0;
    double MaxHeadYawDegrees = 55.0;
    double MaxHeadPitchDegrees = 30.0;
    Millis EyeResponseMs = 60;
    Millis HeadResponseMs = 180;
    Millis TrackingHoldMs = 500;
    Millis TrackingFadeMs = 300;
};

/** Character-local target: Unreal X=forward, Y=right, Z=up after transform. */
struct AttentionTargetPosition {
    double Forward = 0.0;
    double Right = 0.0;
    double Up = 0.0;
};

struct AttentionSample {
    double EyeYaw = 0.0;
    double EyePitch = 0.0;
    double HeadYaw = 0.0;
    double HeadPitch = 0.0;
    double TrackingWeight = 0.0;
};

/** Local eye-first/head-follow LookAt controller, independent of Backend and Cognition. */
class GAHYEON_RUNTIME_CORE_API AttentionRuntime {
public:
    explicit AttentionRuntime(Millis startedAtMs = 0, AttentionConfig config = {});

    bool SetUserTarget(
        AttentionTargetPosition position,
        double confidence,
        Millis nowMs);
    void ClearTarget(Millis nowMs);
    AttentionSample Sample(Millis nowMs);

private:
    void Advance(Millis nowMs);
    double TrackingWeight(Millis nowMs) const;
    static double Smooth(double current, double target, Millis elapsedMs, Millis responseMs);

    AttentionConfig config_;
    Millis lastSampleAtMs_;
    Millis targetReceivedAtMs_ = 0;
    bool hasTarget_ = false;
    double confidence_ = 0.0;
    double targetEyeYaw_ = 0.0;
    double targetEyePitch_ = 0.0;
    double targetHeadYaw_ = 0.0;
    double targetHeadPitch_ = 0.0;
    double eyeYaw_ = 0.0;
    double eyePitch_ = 0.0;
    double headYaw_ = 0.0;
    double headPitch_ = 0.0;
};

} // namespace Gahyeon
