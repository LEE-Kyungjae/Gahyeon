package com.gahyeonbot.application.event;

import com.gahyeonbot.core.event.GahyeonEvent;

import java.util.List;

public interface GahyeonEventQuery {
    List<GahyeonEvent> after(long sequence, int limit);
}
