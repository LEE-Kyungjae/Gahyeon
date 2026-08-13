package com.gahyeonbot.controllers;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import com.gahyeonbot.adapters.health.AgentRuntimeReadiness;
import com.gahyeonbot.services.weather.WeatherService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    @Test
    void exposesTheCurrentProductNameAtThePublicRoot() {
        var controller = new HealthController(
                mock(DataSource.class),
                mock(BotInitializerRunner.class),
                mock(WeatherService.class),
                mock(AgentRuntimeReadiness.class));

        assertThat(controller.root())
                .containsEntry("service", "gahyeon")
                .containsEntry("status", "running")
                .doesNotContainValue("gahyeonbot");
    }

    @Test
    void optionalDisabledConversationRemainsHealthyAndVisible() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        BotInitializerRunner bot = mock(BotInitializerRunner.class);
        when(bot.isReady()).thenReturn(true);
        when(bot.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.DISABLED);
        when(bot.getActivationReason()).thenReturn("bot.enabled=false");
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(false, false));
        var controller = new HealthController(
                dataSource, bot, mock(WeatherService.class), readiness);

        var response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("bot", "DISABLED")
                .containsEntry("botState", "DISABLED")
                .containsEntry("botReason", "bot.enabled=false")
                .containsEntry("conversationRequired", false)
                .containsEntry("conversation", "OPTIONAL_DISABLED");
    }

    @Test
    void requiredUnavailableConversationFailsReadiness() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        BotInitializerRunner bot = mock(BotInitializerRunner.class);
        when(bot.isReady()).thenReturn(true);
        when(bot.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.DISABLED);
        when(bot.getActivationReason()).thenReturn("bot.enabled=false");
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(true, false));
        var controller = new HealthController(
                dataSource, bot, mock(WeatherService.class), readiness);

        var response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .containsEntry("conversationRequired", true)
                .containsEntry("conversation", "DOWN")
                .containsEntry("status", "STARTING");
    }

    @Test
    void enabledDiscordActivationFailureFailsReadiness() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        BotInitializerRunner bot = mock(BotInitializerRunner.class);
        when(bot.isReady()).thenReturn(false);
        when(bot.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.FAILED);
        when(bot.getActivationReason()).thenReturn("Discord token is missing or invalid");
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(false, false));
        var controller = new HealthController(
                dataSource, bot, mock(WeatherService.class), readiness);

        var response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .containsEntry("bot", "DOWN")
                .containsEntry("botState", "FAILED")
                .containsEntry("botReason", "Discord token is missing or invalid")
                .containsEntry("status", "STARTING");
    }

    @Test
    void leaderStandbyRemainsDeploymentReady() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        BotInitializerRunner bot = mock(BotInitializerRunner.class);
        when(bot.isReady()).thenReturn(true);
        when(bot.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.STANDBY);
        when(bot.getActivationReason()).thenReturn(
                "PostgreSQL advisory lock is held by the active Discord instance");
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(false, false));
        var controller = new HealthController(
                dataSource, bot, mock(WeatherService.class), readiness);

        var response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("bot", "STANDBY")
                .containsEntry("botState", "STANDBY")
                .containsEntry("status", "UP");
    }

    @Test
    void readyStateWithoutShardManagerFailsReadiness() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        BotInitializerRunner bot = mock(BotInitializerRunner.class);
        when(bot.isReady()).thenReturn(true);
        when(bot.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.READY);
        when(bot.getActivationReason()).thenReturn("Discord shards connected");
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(false, false));
        var controller = new HealthController(
                dataSource, bot, mock(WeatherService.class), readiness);

        var response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .containsEntry("bot", "DOWN")
                .containsEntry("botState", "READY")
                .containsEntry("status", "STARTING");
    }
}
