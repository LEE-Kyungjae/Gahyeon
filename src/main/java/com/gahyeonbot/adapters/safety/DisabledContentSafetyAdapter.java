package com.gahyeonbot.adapters.safety;

import com.gahyeonbot.application.conversation.ContentSafetyPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit no-provider mode; the deterministic local policy remains active. */
@Component
@ConditionalOnProperty(name = "gahyeon.content-safety.provider", havingValue = "none")
public final class DisabledContentSafetyAdapter implements ContentSafetyPort {
    @Override
    public Decision evaluate(String text) {
        return Decision.UNAVAILABLE;
    }
}
