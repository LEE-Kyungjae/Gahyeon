package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.core.session.ConversationSessionId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/gahyeon/desktop/events")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopEventStreamController {
    private final DesktopEventStreamService streams;
    private final DesktopSessionOwnership ownership;
    private final DesktopCredentialAuthorization credentialAuthorization;

    public DesktopEventStreamController(DesktopEventStreamService streams,
                                        DesktopSessionOwnership ownership,
                                        DesktopCredentialAuthorization credentialAuthorization) {
        this.streams = streams;
        this.ownership = ownership;
        this.credentialAuthorization = credentialAuthorization;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam @Size(max = ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS)
            String sessionId,
            @RequestParam @jakarta.validation.constraints.NotBlank @Size(max = 200)
            String installationId,
            @RequestParam(defaultValue = "0") long afterSequence,
            HttpServletRequest request) {
        credentialAuthorization.requireInstallation(request, installationId);
        ownership.claim(sessionId, installationId);
        return streams.subscribe(sessionId, afterSequence);
    }
}
