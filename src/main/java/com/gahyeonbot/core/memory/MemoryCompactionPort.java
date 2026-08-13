package com.gahyeonbot.core.memory;

import com.gahyeonbot.core.identity.ActorId;

/**
 * Requests maintenance of durable conversation memory.
 *
 * <p>The request must never perform provider I/O on the caller thread. Implementations are
 * responsible for deferring work until the surrounding transaction commits and for bounding
 * their own capacity.</p>
 */
@FunctionalInterface
public interface MemoryCompactionPort {

    void requestCompaction(ActorId actorId);
}
