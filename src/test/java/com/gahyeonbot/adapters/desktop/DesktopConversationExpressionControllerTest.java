package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.speech.ConversationExpressionPlanningService;
import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopConversationExpressionControllerTest {
    @Test
    void delegatesBoundedCharacterWorldAndMessageToCorePlanner() {
        var planning = mock(ConversationExpressionPlanningService.class);
        var identities = mock(IdentityResolutionUseCase.class);
        var authorization = mock(DesktopCredentialAuthorization.class);
        var servletRequest = mock(HttpServletRequest.class);
        var expected = new VoiceExpression("bright", 0.58, "share_positive_affect");
        when(identities.resolveExternal(IdentityProvider.DESKTOP, "install-1", "Tester", null))
                .thenReturn(new ActorId(42));
        when(planning.plan(new CharacterId("gahyeon"), new WorldId("gahyeon-home"), "42", "좋아ㅋㅋ"))
                .thenReturn(expected);
        var controller = new DesktopConversationExpressionController(planning, identities, authorization);

        assertThat(controller.plan(new DesktopConversationExpressionController.PlanRequest(
                "install-1", "Tester", "gahyeon", "gahyeon-home", "좋아ㅋㅋ"), servletRequest))
                .isEqualTo(expected);
        verify(authorization).requireInstallation(servletRequest, "install-1");
        verify(planning).plan(new CharacterId("gahyeon"), new WorldId("gahyeon-home"), "42", "좋아ㅋㅋ");
    }
}
