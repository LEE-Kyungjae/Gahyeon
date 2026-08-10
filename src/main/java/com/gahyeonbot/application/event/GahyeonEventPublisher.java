package com.gahyeonbot.application.event;

import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.GahyeonEventDraft;

public interface GahyeonEventPublisher {
    GahyeonEvent publish(GahyeonEventDraft event);
}
