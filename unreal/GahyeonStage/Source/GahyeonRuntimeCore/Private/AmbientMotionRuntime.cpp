#include "Gahyeon/AmbientMotionRuntime.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace Gahyeon {
namespace {
constexpr double Pi = 3.14159265358979323846;
constexpr int MaxScheduleCatchUp = 10'000;
}

AmbientMotionRuntime::AmbientMotionRuntime(
    Millis startedAtMs,
    std::uint64_t seed,
    AmbientMotionConfig config)
    : config_(config),
      startedAtMs_(startedAtMs),
      lastSampleAtMs_(startedAtMs),
      randomState_(seed == 0 ? 0x47414859454f4eULL : seed),
      nextBlinkAtMs_(startedAtMs),
      saccadeStartedAtMs_(startedAtMs),
      nextSaccadeAtMs_(startedAtMs) {
    if (config_.BreathPeriodMs <= 0 || config_.BlinkDurationMs <= 0
        || config_.BlinkMinIntervalMs <= 0
        || config_.BlinkMaxIntervalMs < config_.BlinkMinIntervalMs
        || config_.SaccadeDurationMs <= 0
        || config_.SaccadeMinIntervalMs <= 0
        || config_.SaccadeMaxIntervalMs < config_.SaccadeMinIntervalMs
        || config_.WeightShiftPeriodMs <= 0) {
        throw std::invalid_argument("invalid ambient motion configuration");
    }
    nextBlinkAtMs_ += RandomInterval(
        config_.BlinkMinIntervalMs, config_.BlinkMaxIntervalMs);
    nextSaccadeAtMs_ += RandomInterval(
        config_.SaccadeMinIntervalMs, config_.SaccadeMaxIntervalMs);
}

AmbientMotionSample AmbientMotionRuntime::Sample(Millis nowMs) {
    nowMs = std::max(nowMs, lastSampleAtMs_);
    lastSampleAtMs_ = nowMs;
    AdvanceBlinkSchedule(nowMs);
    AdvanceSaccadeSchedule(nowMs);

    const double elapsed = static_cast<double>(nowMs - startedAtMs_);
    const double breathPhase = 2.0 * Pi * elapsed
        / static_cast<double>(config_.BreathPeriodMs);
    const double weightPhase = 2.0 * Pi * elapsed
        / static_cast<double>(config_.WeightShiftPeriodMs);
    double blink = 0.0;
    if (nowMs >= nextBlinkAtMs_
        && nowMs < nextBlinkAtMs_ + config_.BlinkDurationMs) {
        const double progress = static_cast<double>(nowMs - nextBlinkAtMs_)
            / static_cast<double>(config_.BlinkDurationMs);
        blink = std::sin(Pi * progress);
    }
    return AmbientMotionSample{
        .Breath = 0.5 - 0.5 * std::cos(breathPhase),
        .Blink = std::clamp(blink, 0.0, 1.0),
        .EyeYaw = std::clamp(CurrentEye(eyeFromYaw_, eyeTargetYaw_, nowMs), -1.0, 1.0),
        .EyePitch = std::clamp(CurrentEye(eyeFromPitch_, eyeTargetPitch_, nowMs), -1.0, 1.0),
        .MicroHeadYaw = 0.55 * std::sin(weightPhase * 0.73)
            + 0.25 * std::sin(weightPhase * 1.91),
        .MicroHeadPitch = 0.35 * std::sin(breathPhase + 0.4),
        .WeightShift = std::sin(weightPhase),
    };
}

double AmbientMotionRuntime::NextUnit() {
    randomState_ ^= randomState_ >> 12;
    randomState_ ^= randomState_ << 25;
    randomState_ ^= randomState_ >> 27;
    const std::uint64_t value = randomState_ * 2685821657736338717ULL;
    return static_cast<double>(value >> 11) * (1.0 / 9007199254740992.0);
}

Millis AmbientMotionRuntime::RandomInterval(Millis minimum, Millis maximum) {
    if (minimum == maximum) return minimum;
    const double span = static_cast<double>(maximum - minimum + 1);
    return minimum + static_cast<Millis>(NextUnit() * span);
}

double AmbientMotionRuntime::CurrentEye(double from, double to, Millis nowMs) const {
    const double progress = std::clamp(
        static_cast<double>(nowMs - saccadeStartedAtMs_)
            / static_cast<double>(config_.SaccadeDurationMs),
        0.0,
        1.0);
    const double smooth = progress * progress * (3.0 - 2.0 * progress);
    return from + (to - from) * smooth;
}

void AmbientMotionRuntime::AdvanceBlinkSchedule(Millis nowMs) {
    int caughtUp = 0;
    while (nowMs >= nextBlinkAtMs_ + config_.BlinkDurationMs
        && caughtUp++ < MaxScheduleCatchUp) {
        nextBlinkAtMs_ += config_.BlinkDurationMs + RandomInterval(
            config_.BlinkMinIntervalMs, config_.BlinkMaxIntervalMs);
    }
    if (caughtUp >= MaxScheduleCatchUp) {
        nextBlinkAtMs_ = nowMs + RandomInterval(
            config_.BlinkMinIntervalMs, config_.BlinkMaxIntervalMs);
    }
}

void AmbientMotionRuntime::AdvanceSaccadeSchedule(Millis nowMs) {
    int caughtUp = 0;
    while (nowMs >= nextSaccadeAtMs_ && caughtUp++ < MaxScheduleCatchUp) {
        eyeFromYaw_ = eyeTargetYaw_;
        eyeFromPitch_ = eyeTargetPitch_;
        eyeTargetYaw_ = NextUnit() * 1.4 - 0.7;
        eyeTargetPitch_ = NextUnit() * 0.8 - 0.4;
        saccadeStartedAtMs_ = nextSaccadeAtMs_;
        nextSaccadeAtMs_ += RandomInterval(
            config_.SaccadeMinIntervalMs, config_.SaccadeMaxIntervalMs);
    }
    if (caughtUp >= MaxScheduleCatchUp) {
        saccadeStartedAtMs_ = nowMs;
        nextSaccadeAtMs_ = nowMs + RandomInterval(
            config_.SaccadeMinIntervalMs, config_.SaccadeMaxIntervalMs);
    }
}

} // namespace Gahyeon
