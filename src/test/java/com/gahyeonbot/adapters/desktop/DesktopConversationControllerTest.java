package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityResolutionService;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.session.ClientSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopConversationControllerTest {
    @Test
    void resolvesDesktopIdentityAndBuildsDesktopSession() {
        ConversationUseCase conversation = mock(ConversationUseCase.class);
        IdentityResolutionService identities = mock(IdentityResolutionService.class);
        when(identities.resolveExternal(IdentityProvider.DESKTOP, "install-1", "Zaeze", null))
                .thenReturn(new ActorId(42));
        when(conversation.converse(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ConversationResponse(
                        "run-1", "반가워요", List.of(), Duration.ofMillis(10)));
        var controller = new DesktopConversationController(conversation, identities);

        var response = controller.converse("room-1", new DesktopConversationController.MessageRequest(
                "request-1", "install-1", "Zaeze", "안녕"));

        assertThat(response.getBody()).isEqualTo(
                new DesktopConversationController.MessageResponse("run-1", "반가워요"));
        ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(conversation).converse(request.capture());
        assertThat(request.getValue().session().source()).isEqualTo(ClientSource.DESKTOP);
        assertThat(request.getValue().session().actorId()).isEqualTo(new ActorId(42));
        assertThat(request.getValue().session().clientContext())
                .containsEntry("desktop.installationId", "install-1");
    }
}
