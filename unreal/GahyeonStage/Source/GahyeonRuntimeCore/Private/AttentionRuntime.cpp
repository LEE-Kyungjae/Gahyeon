#include "Gahyeon/AttentionRuntime.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace Gahyeon {
namespace {
constexpr double RadiansToDegrees = 57.2957795130823208768;
}

AttentionRuntime::AttentionRuntime(Millis startedAtMs, AttentionConfig config)
    : config_(config), lastSampleAtMs_(startedAtMs) {
    if (!std::isfinite(config_.MaxEyeYawDegrees) || config_.MaxEyeYawDegrees <= 0.0
        || !std::isfinite(config_.MaxEyePitchDegrees) || config_.MaxEyePitchDegrees <= 0.0
        || !std::isfinite(config_.MaxHeadYawDegrees) || config_.MaxHeadYawDegrees <= 0.0
        || !std::isfinite(config_.MaxHeadPitchDegrees) || config_.MaxHeadPitchDegrees <= 0.0
        || config_.EyeResponseMs <= 0 || config_.HeadResponseMs <= 0
        || config_.TrackingHoldMs < 0 || config_.TrackingFadeMs <= 0) {
        throw std::invalid_argument("invalid attention configuration");
    }
}

bool AttentionRuntime::SetUserTarget(
    AttentionTargetPosition position,
    double confidence,
    Millis nowMs) {
    if (!std::isfinite(position.Forward) || !std::isfinite(position.Right)
        || !std::isfinite(position.Up) || !std::isfinite(confidence)
        || confidence < 0.0 || confidence > 1.0
        || nowMs < lastSampleAtMs_) {
        return false;
    }
    const double distance = std::hypot(
        std::hypot(position.Forward, position.Right), position.Up);
    if (distance < 1e-6) return false;
    Advance(nowMs);
    const double yawDegrees = std::atan2(position.Right, position.Forward) * RadiansToDegrees;
    const double pitchDegrees = std::atan2(
        position.Up, std::hypot(position.Forward, position.Right)) * RadiansToDegrees;
    targetEyeYaw_ = std::clamp(
        yawDegrees / config_.MaxEyeYawDegrees, -1.0, 1.0);
    targetEyePitch_ = std::clamp(
        pitchDegrees / config_.MaxEyePitchDegrees, -1.0, 1.0);
    targetHeadYaw_ = std::clamp(
        yawDegrees / config_.MaxHeadYawDegrees, -1.0, 1.0);
    targetHeadPitch_ = std::clamp(
        pitchDegrees / config_.MaxHeadPitchDegrees, -1.0, 1.0);
    confidence_ = confidence;
    targetReceivedAtMs_ = nowMs;
    hasTarget_ = true;
    return true;
}

void AttentionRuntime::ClearTarget(Millis nowMs) {
    if (nowMs < lastSampleAtMs_) return;
    Advance(nowMs);
    hasTarget_ = false;
    confidence_ = 0.0;
}

AttentionSample AttentionRuntime::Sample(Millis nowMs) {
    Advance(std::max(nowMs, lastSampleAtMs_));
    return AttentionSample{
        .EyeYaw = std::clamp(eyeYaw_, -1.0, 1.0),
        .EyePitch = std::clamp(eyePitch_, -1.0, 1.0),
        .HeadYaw = std::clamp(headYaw_, -1.0, 1.0),
        .HeadPitch = std::clamp(headPitch_, -1.0, 1.0),
        .TrackingWeight = TrackingWeight(lastSampleAtMs_),
    };
}

void AttentionRuntime::Advance(Millis nowMs) {
    const Millis elapsed = nowMs - lastSampleAtMs_;
    const double weight = TrackingWeight(nowMs);
    eyeYaw_ = Smooth(eyeYaw_, targetEyeYaw_ * weight, elapsed, config_.EyeResponseMs);
    eyePitch_ = Smooth(eyePitch_, targetEyePitch_ * weight, elapsed, config_.EyeResponseMs);
    headYaw_ = Smooth(headYaw_, targetHeadYaw_ * weight, elapsed, config_.HeadResponseMs);
    headPitch_ = Smooth(headPitch_, targetHeadPitch_ * weight, elapsed, config_.HeadResponseMs);
    lastSampleAtMs_ = nowMs;
}

double AttentionRuntime::TrackingWeight(Millis nowMs) const {
    if (!hasTarget_) return 0.0;
    const Millis age = std::max<Millis>(0, nowMs - targetReceivedAtMs_);
    if (age <= config_.TrackingHoldMs) return confidence_;
    const double fade = 1.0 - static_cast<double>(age - config_.TrackingHoldMs)
        / static_cast<double>(config_.TrackingFadeMs);
    return confidence_ * std::clamp(fade, 0.0, 1.0);
}

double AttentionRuntime::Smooth(
    double current,
    double target,
    Millis elapsedMs,
    Millis responseMs) {
    if (elapsedMs <= 0) return current;
    const double alpha = 1.0 - std::exp(
        -static_cast<double>(elapsedMs) / static_cast<double>(responseMs));
    return current + (target - current) * alpha;
}

} // namespace Gahyeon
