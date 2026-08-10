package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.session.ConversationSession;

public interface ConversationPresencePort {
    PresenceLease enter(ConversationSession session);

    @FunctionalInterface
    interface PresenceLease extends AutoCloseable {
        PresenceLease NOOP = () -> {};

        @Override
        void close();
    }
}
