package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

class DesktopConversationControllerTest {
    @Test
    void resolvesDesktopIdentityAndBuildsDesktopSession() {
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        DesktopEventStreamService streams = mock(DesktopEventStreamService.class);
        DesktopSessionOwnership ownership = mock(DesktopSessionOwnership.class);
        DesktopCredentialAuthorization authorization = mock(DesktopCredentialAuthorization.class);
        jakarta.servlet.http.HttpServletRequest httpRequest = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(identities.resolveExternal(IdentityProvider.DESKTOP, "install-1", "Zaeze", null))
                .thenReturn(new ActorId(42));
        when(streams.beginConversation("room-1")).thenReturn("generation-1");
        when(streams.isCurrentConversation("room-1", "generation-1")).thenReturn(true);
        doAnswer(invocation -> {
            var observer = (com.gahyeonbot.application.conversation.ConversationStreamObserver) invocation.getArgument(1);
            observer.onTextDelta("반가");
            observer.onTextDelta("워요");
            return new ConversationResponse("run-1", "반가워요", List.of(), Duration.ofMillis(10));
        }).when(conversation).converseStreaming(any(), any());
        var controller = new DesktopConversationController(
                conversation, identities, streams, ownership, authorization);

        var response = controller.converse("room-1", new DesktopConversationController.MessageRequest(
                "request-1", "install-1", "Zaeze", "diana", "안녕"), httpRequest);
        verify(authorization).requireInstallation(httpRequest, "install-1");
        verify(ownership).claim("room-1", "install-1");

        assertThat(response.getBody()).isEqualTo(
                new DesktopConversationController.MessageResponse("run-1", "반가워요"));
        ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(conversation).converseStreaming(request.capture(), any());
        assertThat(request.getValue().session().source()).isEqualTo(ClientSource.DESKTOP);
        assertThat(request.getValue().session().id().value()).isEqualTo("desktop:room-1");
        assertThat(request.getValue().session().actorId()).isEqualTo(new ActorId(42));
        assertThat(request.getValue().session().clientContext())
                .containsEntry("desktop.installationId", "install-1")
                .containsEntry("character.id", "diana")
                .containsEntry("world.id", "gahyeon-home");
        verify(streams).publishConversationDelta("room-1", "request-1", "반가");
        verify(streams).publishConversationDelta("room-1", "request-1", "워요");
        verify(streams).finishConversation("room-1", "generation-1");

        assertThat(controller.cancel("room-1", "install-1", httpRequest).getStatusCode().value()).isEqualTo(204);
        verify(ownership).requireOwner("room-1", "install-1");
        verify(streams).cancelConversation("room-1");
    }
}
