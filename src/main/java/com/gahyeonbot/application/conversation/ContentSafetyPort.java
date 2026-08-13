package com.gahyeonbot.application.conversation;

/** Provider-neutral, optional pre-admission content safety boundary. */
public interface ContentSafetyPort {
    Decision evaluate(String text);

    enum Decision {
        SAFE,
        UNSAFE,
        UNAVAILABLE
    }
}
