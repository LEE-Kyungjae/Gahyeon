package com.gahyeonbot.adapters.discord.health;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordHealthIndicatorTest {
    @Test
    void disabledAndStandbyStatesRemainHealthyWithExplicitReasons() {
        BotInitializerRunner runner = mock(BotInitializerRunner.class);
        DiscordHealthIndicator indicator = new DiscordHealthIndicator(runner);

        when(runner.getActivationState())
                .thenReturn(BotInitializerRunner.ActivationState.DISABLED)
                .thenReturn(BotInitializerRunner.ActivationState.STANDBY);
        when(runner.getActivationReason())
                .thenReturn("bot.enabled=false")
                .thenReturn("PostgreSQL advisory lock is held by the active Discord instance");

        var disabled = indicator.health();
        var standby = indicator.health();

        assertThat(disabled.getStatus()).isEqualTo(Status.UP);
        assertThat(disabled.getDetails())
                .containsEntry("state", "DISABLED")
                .containsEntry("reason", "bot.enabled=false");
        assertThat(standby.getStatus()).isEqualTo(Status.UP);
        assertThat(standby.getDetails())
                .containsEntry("state", "STANDBY")
                .containsEntry("reason",
                        "PostgreSQL advisory lock is held by the active Discord instance");
    }

    @Test
    void activationFailureIsDown() {
        BotInitializerRunner runner = mock(BotInitializerRunner.class);
        when(runner.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.FAILED);
        when(runner.getActivationReason()).thenReturn("Discord token is missing or invalid");

        var health = new DiscordHealthIndicator(runner).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("state", "FAILED")
                .containsEntry("reason", "Discord token is missing or invalid");
    }

    @Test
    void readyStateWithoutShardManagerFailsClosed() {
        BotInitializerRunner runner = mock(BotInitializerRunner.class);
        when(runner.getActivationState()).thenReturn(BotInitializerRunner.ActivationState.READY);
        when(runner.getActivationReason()).thenReturn("Discord shards connected");

        var health = new DiscordHealthIndicator(runner).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("state", "FAILED")
                .containsEntry("reason", "Discord READY state has no ShardManager");
    }
}
