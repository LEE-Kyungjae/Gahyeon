package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.behavior.WorldActionCoordinator;
import com.gahyeonbot.core.world.WorldStateUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopWorldControllerTest {
    @Test
    void reportsRendererArrivalThroughTheCoreOwnedActionCoordinator() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        DesktopCredentialAuthorization authorization = mock(DesktopCredentialAuthorization.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(actions.complete(org.mockito.ArgumentMatchers.any()))
                .thenReturn(WorldActionCoordinator.CompletionResult.COMMITTED);
        var controller = new DesktopWorldController(worlds, actions, authorization);

        var response = controller.completeAction(
                "gahyeon-home",
                "action-18",
                new DesktopWorldController.CompleteActionRequest(
                        "install-1", 7, 4.5, 0, -2.25),
                httpRequest);

        verify(authorization).requireInstallation(httpRequest, "install-1");
        var completion = ArgumentCaptor.forClass(
                WorldActionCoordinator.ActionCompletion.class);
        verify(actions).complete(completion.capture());
        assertThat(completion.getValue().worldId().value()).isEqualTo("gahyeon-home");
        assertThat(completion.getValue().actionId()).isEqualTo("action-18");
        assertThat(completion.getValue().expectedRevision()).isEqualTo(7);
        assertThat(completion.getValue().outcome()).isEqualTo("completed");
        assertThat(completion.getValue().reason()).isEqualTo("desktop_navigation_arrived");
        assertThat(completion.getValue().finalPosition().x()).isEqualTo(4.5);
        assertThat(completion.getValue().finalPosition().z()).isEqualTo(-2.25);
        assertThat(response.result()).isEqualTo(
                WorldActionCoordinator.CompletionResult.COMMITTED);
    }
}
