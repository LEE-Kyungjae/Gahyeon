#include "Gahyeon/IntentMailbox.h"

#include <algorithm>
#include <utility>

namespace Gahyeon {

namespace {

int LayerRank(IntentLayer layer) {
    switch (layer) {
        case IntentLayer::Reflex: return 3;
        case IntentLayer::Behavior: return 2;
        case IntentLayer::Cognition: return 1;
    }
    return 0;
}

} // namespace

IntentMailbox::IntentMailbox(std::size_t capacity)
    : capacity_(capacity) {}

bool IntentMailbox::TryPush(CharacterIntent intent) {
    std::scoped_lock lock(mutex_);
    if (queue_.size() >= capacity_) {
        const int incomingRank = LayerRank(intent.Layer);
        const auto replaceable = std::find_if(queue_.begin(), queue_.end(), [incomingRank](const auto& queued) {
            return LayerRank(queued.Layer) < incomingRank;
        });
        if (replaceable == queue_.end()) {
            ++droppedCount_;
            return false;
        }
        queue_.erase(replaceable);
        ++droppedCount_;
    }
    queue_.push_back(std::move(intent));
    return true;
}

std::vector<CharacterIntent> IntentMailbox::Drain() {
    std::deque<CharacterIntent> drained;
    {
        std::scoped_lock lock(mutex_);
        drained.swap(queue_);
    }
    std::vector<CharacterIntent> result;
    result.reserve(drained.size());
    while (!drained.empty()) {
        result.push_back(std::move(drained.front()));
        drained.pop_front();
    }
    return result;
}

std::size_t IntentMailbox::Size() const {
    std::scoped_lock lock(mutex_);
    return queue_.size();
}

std::uint64_t IntentMailbox::DroppedCount() const {
    std::scoped_lock lock(mutex_);
    return droppedCount_;
}

} // namespace Gahyeon
