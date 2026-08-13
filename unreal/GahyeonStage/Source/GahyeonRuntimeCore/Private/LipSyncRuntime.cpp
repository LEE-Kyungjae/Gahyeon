#include "Gahyeon/LipSyncRuntime.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <utility>

namespace Gahyeon {

LipSyncRuntime::LipSyncRuntime(LipSyncConfig config, LatencyTrace* latency)
    : config_(config), latency_(latency) {
    if (!std::isfinite(config_.NoiseFloor) || config_.NoiseFloor < 0.0
        || config_.NoiseFloor >= 1.0 || !std::isfinite(config_.AmplitudeGain)
        || config_.AmplitudeGain <= 0.0 || config_.AttackMs <= 0
        || config_.ReleaseMs <= 0 || config_.VisemeAttackMs < 0
        || config_.VisemeReleaseMs < 0 || config_.MaxVisemeCues == 0) {
        throw std::invalid_argument("invalid lip sync configuration");
    }
}

void LipSyncRuntime::SetGeneration(Generation generation) {
    if (generation <= generation_) return;
    generation_ = generation;
    utteranceId_.clear();
    cues_.clear();
    observedCues_.clear();
    lastAudioPositionMs_.reset();
    smoothedAmplitude_ = 0.0;
    lastSample_ = {};
}

LipSyncPrepareResult LipSyncRuntime::BeginPlayback(const PreparedSpeech& speech) {
    if (speech.GenerationId < generation_) return LipSyncPrepareResult::Stale;
    if (speech.GenerationId > generation_ || speech.UtteranceId.empty()
        || speech.Visemes.size() > config_.MaxVisemeCues) {
        return LipSyncPrepareResult::Invalid;
    }
    Millis previousAt = -1;
    for (const VisemeCue& cue : speech.Visemes) {
        if (!ValidCue(cue) || cue.AtMs < previousAt) {
            return LipSyncPrepareResult::Invalid;
        }
        previousAt = cue.AtMs;
    }
    utteranceId_ = speech.UtteranceId;
    cues_ = speech.Visemes;
    observedCues_.assign(cues_.size(), false);
    lastAudioPositionMs_.reset();
    smoothedAmplitude_ = 0.0;
    lastSample_ = LipSyncSample{
        .Active = true,
        .UsingTimeline = !cues_.empty(),
    };
    return LipSyncPrepareResult::Accepted;
}

LipSyncSample LipSyncRuntime::Sample(Millis audioPositionMs, double normalizedRms) {
    if (utteranceId_.empty() || audioPositionMs < 0 || !std::isfinite(normalizedRms)
        || (lastAudioPositionMs_.has_value()
            && audioPositionMs < lastAudioPositionMs_.value())) {
        return lastSample_;
    }

    LipSyncSample sample{
        .Active = true,
        .UsingTimeline = !cues_.empty(),
    };
    if (!cues_.empty()) {
        for (std::size_t index = 0; index < cues_.size(); ++index) {
            const VisemeCue& cue = cues_[index];
            const double influence = CueInfluence(cue, audioPositionMs);
            if (influence > sample.PrimaryWeight) {
                sample.SecondaryViseme = std::move(sample.PrimaryViseme);
                sample.SecondaryWeight = sample.PrimaryWeight;
                sample.PrimaryViseme = cue.Semantic;
                sample.PrimaryWeight = influence;
            } else if (influence > sample.SecondaryWeight) {
                sample.SecondaryViseme = cue.Semantic;
                sample.SecondaryWeight = influence;
            }
        }
    } else {
        const double rms = Clamp01(normalizedRms);
        const double target = Clamp01((rms - config_.NoiseFloor) * config_.AmplitudeGain);
        const Millis delta = lastAudioPositionMs_.has_value()
            ? audioPositionMs - lastAudioPositionMs_.value()
            : 0;
        const Millis response = target >= smoothedAmplitude_
            ? config_.AttackMs
            : config_.ReleaseMs;
        const double alpha = delta <= 0
            ? 0.0
            : 1.0 - std::exp(-static_cast<double>(delta) / static_cast<double>(response));
        smoothedAmplitude_ += (target - smoothedAmplitude_) * alpha;
        sample.JawOpen = Clamp01(smoothedAmplitude_);
    }
    lastAudioPositionMs_ = audioPositionMs;
    lastSample_ = sample;
    return sample;
}

bool LipSyncRuntime::ConfirmVisemePresented(
    const std::string& semantic,
    Millis audioPositionMs) {
    if (utteranceId_.empty() || semantic.empty() || audioPositionMs < 0
        || !lastAudioPositionMs_.has_value()
        || audioPositionMs < lastAudioPositionMs_.value()) {
        return false;
    }
    for (std::size_t reverse = cues_.size(); reverse > 0; --reverse) {
        const std::size_t index = reverse - 1;
        const VisemeCue& cue = cues_[index];
        if (!observedCues_[index] && cue.Semantic == semantic
            && CueInfluence(cue, lastAudioPositionMs_.value()) > 0.0
            && audioPositionMs >= cue.AtMs) {
            observedCues_[index] = true;
            if (latency_ != nullptr) {
                latency_->Record(
                    LatencyMetric::VisemeOnsetOffset,
                    audioPositionMs - cue.AtMs);
            }
            return true;
        }
    }
    return false;
}

bool LipSyncRuntime::EndPlayback(const std::string& utteranceId) {
    if (utteranceId_.empty() || utteranceId != utteranceId_) return false;
    utteranceId_.clear();
    cues_.clear();
    observedCues_.clear();
    lastAudioPositionMs_.reset();
    smoothedAmplitude_ = 0.0;
    lastSample_ = {};
    return true;
}

Generation LipSyncRuntime::CurrentGeneration() const { return generation_; }

bool LipSyncRuntime::IsActive() const { return !utteranceId_.empty(); }

bool LipSyncRuntime::ValidCue(const VisemeCue& cue) {
    return !cue.Semantic.empty() && cue.AtMs >= 0 && cue.DurationMs > 0
        && std::isfinite(cue.Weight) && cue.Weight > 0.0 && cue.Weight <= 1.0;
}

double LipSyncRuntime::Clamp01(double value) {
    return std::max(0.0, std::min(1.0, value));
}

double LipSyncRuntime::CueInfluence(const VisemeCue& cue, Millis audioPositionMs) const {
    if (audioPositionMs < cue.AtMs) return 0.0;
    const Millis local = audioPositionMs - cue.AtMs;
    if (local >= cue.DurationMs) return 0.0;
    const Millis remaining = cue.DurationMs - local;
    double envelope = 1.0;
    if (config_.VisemeAttackMs > 0 && local < config_.VisemeAttackMs) {
        envelope = std::min(envelope,
            static_cast<double>(local) / static_cast<double>(config_.VisemeAttackMs));
    }
    if (config_.VisemeReleaseMs > 0 && remaining < config_.VisemeReleaseMs) {
        envelope = std::min(envelope,
            static_cast<double>(remaining) / static_cast<double>(config_.VisemeReleaseMs));
    }
    return Clamp01(envelope * cue.Weight);
}

} // namespace Gahyeon
