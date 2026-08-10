package com.gahyeonbot.adapters.desktop;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/gahyeon/desktop/events")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class DesktopEventStreamController {
    private final DesktopEventStreamService streams;

    public DesktopEventStreamController(DesktopEventStreamService streams) {
        this.streams = streams;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "0") long afterSequence) {
        return streams.subscribe(sessionId, afterSequence);
    }
}
