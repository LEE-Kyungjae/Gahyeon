package com.gahyeonbot.adapters.headless;

import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.GahyeonEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/gahyeon/events")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class HeadlessEventController {
    private final GahyeonEventQuery events;

    public HeadlessEventController(GahyeonEventQuery events) {
        this.events = events;
    }

    @GetMapping
    public EventPage events(
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "100") int limit) {
        List<GahyeonEvent> items = events.after(afterSequence, limit);
        long nextSequence = items.isEmpty()
                ? afterSequence
                : items.getLast().sequence();
        return new EventPage(items, nextSequence);
    }

    public record EventPage(List<GahyeonEvent> events, long nextSequence) {}
}
