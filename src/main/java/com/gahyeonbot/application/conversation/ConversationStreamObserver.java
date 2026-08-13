package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationResponse;

public interface ConversationStreamObserver {
    void onTextDelta(String delta);

    default void onCompleted(ConversationResponse response) {}

    default void onFailed(RuntimeException failure) {}

    default void onCancelled() {}

    default boolean isCancelled() { return false; }
}
