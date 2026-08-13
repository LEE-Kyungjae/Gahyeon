#include "Gahyeon/IntentRuntime.h"

#include <limits>

namespace Gahyeon {

const CharacterIntent* ResolvedIntents::Find(IntentChannel channel) const {
    const auto found = Channels.find(channel);
    return found == Channels.end() ? nullptr : &found->second;
}

Generation IntentRuntime::CurrentGeneration() const {
    return generation_;
}

Generation IntentRuntime::BeginGeneration() {
    if (generation_ < std::numeric_limits<Generation>::max()) {
        ++generation_;
    }
    return generation_;
}

GenerationSyncResult IntentRuntime::SynchronizeGeneration(Generation generation) {
    if (generation < generation_) {
        return GenerationSyncResult::Stale;
    }
    if (generation == generation_) {
        return GenerationSyncResult::Unchanged;
    }
    generation_ = generation;
    return GenerationSyncResult::Advanced;
}

PublishResult IntentRuntime::Publish(CharacterIntent intent) {
    if (intent.Id.empty() || intent.ExpiresAfterMs.value_or(0) < 0) {
        return PublishResult::Invalid;
    }
    if (intent.GenerationId.has_value() && intent.GenerationId.value() < generation_) {
        return PublishResult::Stale;
    }
    intents_.insert_or_assign(intent.Id, std::move(intent));
    return PublishResult::Accepted;
}

ResolvedIntents IntentRuntime::Resolve(Millis nowMs) const {
    ResolvedIntents result;
    result.CurrentGeneration = generation_;
    for (const auto& [id, intent] : intents_) {
        if (intent.GenerationId.has_value() && intent.GenerationId.value() != generation_) {
            continue;
        }
        if (IsExpired(intent, nowMs)) {
            continue;
        }
        const auto selected = result.Channels.find(intent.Channel);
        if (selected == result.Channels.end() || Wins(intent, selected->second)) {
            result.Channels.insert_or_assign(intent.Channel, intent);
        }
    }
    return result;
}

void IntentRuntime::Compact(Millis nowMs) {
    for (auto iterator = intents_.begin(); iterator != intents_.end();) {
        const CharacterIntent& intent = iterator->second;
        const bool stale = intent.GenerationId.has_value()
            && intent.GenerationId.value() < generation_;
        if (stale || IsExpired(intent, nowMs)) {
            iterator = intents_.erase(iterator);
        } else {
            ++iterator;
        }
    }
}

bool IntentRuntime::IsExpired(const CharacterIntent& intent, Millis nowMs) {
    if (!intent.ExpiresAfterMs.has_value()) {
        return false;
    }
    const Millis duration = intent.ExpiresAfterMs.value();
    if (duration < 0) {
        return true;
    }
    if (intent.CreatedAtMs > std::numeric_limits<Millis>::max() - duration) {
        return false;
    }
    return nowMs >= intent.CreatedAtMs + duration;
}

bool IntentRuntime::Wins(
    const CharacterIntent& candidate,
    const CharacterIntent& selected) {
    if (candidate.Layer != selected.Layer) {
        return candidate.Layer == IntentLayer::Reflex
            || (candidate.Layer == IntentLayer::Behavior
                && selected.Layer == IntentLayer::Cognition);
    }
    if (candidate.Priority != selected.Priority) {
        return candidate.Priority > selected.Priority;
    }
    if (candidate.CreatedAtMs != selected.CreatedAtMs) {
        return candidate.CreatedAtMs > selected.CreatedAtMs;
    }
    return candidate.Id > selected.Id;
}

} // namespace Gahyeon
