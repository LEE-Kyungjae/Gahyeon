#include "Gahyeon/GestureRuntime.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_set>

namespace Gahyeon {

GestureRuntime::GestureRuntime(
    std::vector<GestureDefinition> definitions,
    std::uint64_t seed)
    : definitions_(std::move(definitions)),
      randomState_(seed == 0 ? 0x9E3779B97F4A7C15ULL : seed) {
    std::unordered_set<std::string> variants;
    for (const GestureDefinition& definition : definitions_) {
        if (!ValidDefinition(definition) || !variants.insert(definition.VariantId).second) {
            throw std::invalid_argument("gesture definition is invalid or duplicated");
        }
    }
}

GestureRequestResult GestureRuntime::Request(
    const GestureIntent& intent,
    Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return GestureRequestResult::NonMonotonic;
    }
    lastObservedAtMs_ = nowMs;
    Advance(nowMs);
    if (intent.Semantic.empty() || !std::isfinite(intent.Intensity)
        || intent.Intensity < 0.0 || intent.Intensity > 1.0) {
        return GestureRequestResult::Invalid;
    }
    if (intent.GenerationId.has_value()) {
        if (intent.GenerationId.value() < generation_) return GestureRequestResult::Stale;
        if (intent.GenerationId.value() > generation_) return GestureRequestResult::Invalid;
    }
    if (active_.has_value()
        && (!active_->Interruptible || intent.Priority < active_->Priority)) {
        return GestureRequestResult::Busy;
    }

    std::vector<const GestureDefinition*> candidates;
    double totalWeight = 0.0;
    for (const GestureDefinition& definition : definitions_) {
        if (definition.Semantic != intent.Semantic
            || intent.Intensity < definition.MinIntensity
            || intent.Intensity > definition.MaxIntensity
            || (definition.RequiredPosture.has_value()
                && definition.RequiredPosture.value() != intent.CurrentPosture)) {
            continue;
        }
        const auto lastUsed = lastUsedAtMs_.find(definition.VariantId);
        if (lastUsed != lastUsedAtMs_.end()
            && nowMs - lastUsed->second < definition.CooldownMs) {
            continue;
        }
        candidates.push_back(&definition);
        totalWeight += definition.SelectionWeight;
    }
    if (candidates.empty()) return GestureRequestResult::NoCandidate;

    const double unit = static_cast<double>(NextRandom() >> 11)
        * (1.0 / 9007199254740992.0);
    double choice = unit * totalWeight;
    const GestureDefinition* selected = candidates.back();
    for (const GestureDefinition* candidate : candidates) {
        choice -= candidate->SelectionWeight;
        if (choice <= 0.0) {
            selected = candidate;
            break;
        }
    }
    const Millis endsAt = nowMs > std::numeric_limits<Millis>::max() - selected->DurationMs
        ? std::numeric_limits<Millis>::max()
        : nowMs + selected->DurationMs;
    active_ = ActiveGesture{
        .Semantic = intent.Semantic,
        .VariantId = selected->VariantId,
        .Intensity = intent.Intensity,
        .StartedAtMs = nowMs,
        .EndsAtMs = endsAt,
        .Priority = intent.Priority,
        .Interruptible = selected->Interruptible,
        .GenerationId = intent.GenerationId,
    };
    lastUsedAtMs_.insert_or_assign(selected->VariantId, nowMs);
    return GestureRequestResult::Selected;
}

bool GestureRuntime::ConfigureDefinitions(std::vector<GestureDefinition> definitions) {
    std::unordered_set<std::string> variants;
    for (const GestureDefinition& definition : definitions) {
        if (!ValidDefinition(definition) || !variants.insert(definition.VariantId).second) {
            return false;
        }
    }
    definitions_ = std::move(definitions);
    active_.reset();
    lastUsedAtMs_.clear();
    return true;
}

bool GestureRuntime::Advance(Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return false;
    }
    lastObservedAtMs_ = nowMs;
    if (!active_.has_value() || nowMs < active_->EndsAtMs) return false;
    active_.reset();
    return true;
}

std::optional<std::string> GestureRuntime::SetGeneration(Generation generation) {
    if (generation <= generation_) return std::nullopt;
    generation_ = generation;
    if (!active_.has_value() || !active_->GenerationId.has_value()
        || active_->GenerationId.value() >= generation_) {
        return std::nullopt;
    }
    std::string interrupted = active_->VariantId;
    active_.reset();
    return interrupted;
}

Generation GestureRuntime::CurrentGeneration() const { return generation_; }

const std::optional<ActiveGesture>& GestureRuntime::Active() const { return active_; }

bool GestureRuntime::ValidDefinition(const GestureDefinition& definition) {
    return !definition.Semantic.empty() && !definition.VariantId.empty()
        && std::isfinite(definition.MinIntensity)
        && std::isfinite(definition.MaxIntensity)
        && definition.MinIntensity >= 0.0
        && definition.MaxIntensity <= 1.0
        && definition.MinIntensity <= definition.MaxIntensity
        && definition.DurationMs > 0 && definition.CooldownMs >= 0
        && std::isfinite(definition.SelectionWeight)
        && definition.SelectionWeight > 0.0;
}

std::uint64_t GestureRuntime::NextRandom() {
    randomState_ ^= randomState_ >> 12;
    randomState_ ^= randomState_ << 25;
    randomState_ ^= randomState_ >> 27;
    return randomState_ * 2685821657736338717ULL;
}

} // namespace Gahyeon
