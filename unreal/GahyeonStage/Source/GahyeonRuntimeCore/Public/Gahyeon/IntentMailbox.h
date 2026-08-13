#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstddef>
#include <deque>
#include <mutex>
#include <vector>

namespace Gahyeon {

/** Multi-producer, single-consumer handoff into the game-thread-owned arbiter. */
class GAHYEON_RUNTIME_CORE_API IntentMailbox {
public:
    explicit IntentMailbox(std::size_t capacity = 1024);

    bool TryPush(CharacterIntent intent);
    std::vector<CharacterIntent> Drain();
    std::size_t Size() const;
    std::uint64_t DroppedCount() const;

private:
    const std::size_t capacity_;
    mutable std::mutex mutex_;
    std::deque<CharacterIntent> queue_;
    std::uint64_t droppedCount_ = 0;
};

} // namespace Gahyeon
