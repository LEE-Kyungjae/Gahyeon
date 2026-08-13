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
        when(streams.subscribe("desktop-session", "gahyeon-home", 17)).thenReturn(emitter);
        DesktopSessionOwnership ownership = mock(DesktopSessionOwnership.class);
        DesktopCredentialAuthorization authorization = mock(DesktopCredentialAuthorization.class);
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);

        var controller = new DesktopEventStreamController(streams, ownership, authorization);

        assertThat(controller.stream(
                "desktop-session", "install-1", "gahyeon-home", 17, request)).isSameAs(emitter);
        verify(authorization).requireInstallation(request, "install-1");
        verify(ownership).claim("desktop-session", "install-1");
        verify(streams).subscribe("desktop-session", "gahyeon-home", 17);
    }
}
