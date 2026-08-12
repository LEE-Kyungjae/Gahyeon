package com.gahyeonbot.services.ai;

import com.gahyeonbot.core.identity.ActorId;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Fixed-size actor lock stripes for a long-running agent process. */
final class ActorLockRegistry {
    private final Lock[] stripes;

    ActorLockRegistry(int stripeCount) {
        if (stripeCount < 2 || (stripeCount & (stripeCount - 1)) != 0) {
            throw new IllegalArgumentException("stripeCount must be a power of two greater than one");
        }
        stripes = new Lock[stripeCount];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    Lock lockFor(ActorId actorId) {
        if (actorId == null) throw new IllegalArgumentException("actorId가 필요합니다.");
        int hash = Long.hashCode(actorId.value());
        hash ^= hash >>> 16;
        return stripes[hash & (stripes.length - 1)];
    }

    int capacity() {
        return stripes.length;
    }
}
