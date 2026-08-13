#include "Gahyeon/VoiceActivityDetector.h"

#include <cmath>
#include <stdexcept>

namespace Gahyeon {

VoiceActivityDetector::VoiceActivityDetector(VoiceActivityConfig config)
    : config_(config) {
    if (!std::isfinite(config_.StartThreshold)
        || !std::isfinite(config_.StopThreshold)
        || config_.StartThreshold <= config_.StopThreshold
        || config_.StopThreshold < 0.0
        || config_.StartThreshold > 1.0
        || config_.AttackMs < 0
        || config_.ReleaseMs < 0) {
        throw std::invalid_argument("invalid voice activity configuration");
    }
}

VoiceActivityEvent VoiceActivityDetector::Observe(double level, Millis nowMs) {
    if (!std::isfinite(level) || level < 0.0 || level > 1.0
        || (lastObservedAt_.has_value() && nowMs < lastObservedAt_.value())) {
        return VoiceActivityEvent::Invalid;
    }
    lastObservedAt_ = nowMs;
    const bool candidate = active_
        ? level <= config_.StopThreshold
        : level >= config_.StartThreshold;
    if (!candidate) {
        candidateSince_.reset();
        return VoiceActivityEvent::None;
    }
    if (!candidateSince_.has_value()) candidateSince_ = nowMs;
    const Millis required = active_ ? config_.ReleaseMs : config_.AttackMs;
    if (nowMs - candidateSince_.value() < required) return VoiceActivityEvent::None;

    active_ = !active_;
    candidateSince_.reset();
    return active_ ? VoiceActivityEvent::Started : VoiceActivityEvent::Ended;
}

void VoiceActivityDetector::Reset() {
    active_ = false;
    candidateSince_.reset();
    lastObservedAt_.reset();
}

bool VoiceActivityDetector::Active() const {
    return active_;
}

} // namespace Gahyeon
