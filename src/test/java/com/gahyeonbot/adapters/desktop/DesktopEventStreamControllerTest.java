package com.gahyeonbot.adapters.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopEventStreamControllerTest {
    @Test
    void delegatesCursorAndSessionToStreamService() {
        DesktopEventStreamService streams = mock(DesktopEventStreamService.class);
        SseEmitter emitter = new SseEmitter();
        when(streams.subscribe("desktop-session", 17)).thenReturn(emitter);

        var controller = new DesktopEventStreamController(streams);

        assertThat(controller.stream("desktop-session", 17)).isSameAs(emitter);
        verify(streams).subscribe("desktop-session", 17);
    }
}
