package com.gahyeonbot.adapters.headless;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeadlessConversationControllerTest {
    @Test
    void namespacesClientLocalSessionBeforeCallingCore() {
        ConversationUseCase conversation = mock(ConversationUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        when(identities.resolveExternal(IdentityProvider.HEADLESS, "client-user-42", "tester", null))
                .thenReturn(new ActorId(942));
        when(conversation.converse(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ConversationResponse("run-1", "안녕하세요", List.of(), Duration.ZERO));
        var controller = new HeadlessConversationController(conversation, identities);

        var response = controller.converse("shared-room", new HeadlessConversationController.MessageRequest(
                "request-1", "client-user-42", "tester", "안녕"));

        assertThat(response.getBody()).isEqualTo(
                new HeadlessConversationController.MessageResponse("run-1", "안녕하세요"));
        ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(conversation).converse(request.capture());
        assertThat(request.getValue().session().source()).isEqualTo(ClientSource.HEADLESS);
        assertThat(request.getValue().session().id().value()).isEqualTo("headless:shared-room");
        assertThat(request.getValue().session().actorId()).isEqualTo(new ActorId(942));
        verify(identities).resolveExternal(
                IdentityProvider.HEADLESS, "client-user-42", "tester", null);
    }

    @Test
    void treatsLegacyNumericActorIdAsExternalIdentityInsteadOfInternalPrincipalId() {
        ConversationUseCase conversation = mock(ConversationUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        when(identities.resolveExternal(IdentityProvider.HEADLESS, "legacy-numeric:42", "legacy", null))
                .thenReturn(new ActorId(842));
        when(conversation.converse(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ConversationResponse("run-legacy", "확인", List.of(), Duration.ZERO));
        var controller = new HeadlessConversationController(conversation, identities);

        controller.converse("legacy-room", new HeadlessConversationController.MessageRequest(
                "legacy-request", null, 42L, "legacy", "안녕"));

        ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
        verify(conversation).converse(request.capture());
        assertThat(request.getValue().session().actorId()).isEqualTo(new ActorId(842));
        verify(identities).resolveExternal(
                IdentityProvider.HEADLESS, "legacy-numeric:42", "legacy", null);
    }
}
