package com.gahyeonbot.adapters.discord.bootstrap;

import com.gahyeonbot.adapters.discord.command.CommandRegistry;
import com.gahyeonbot.config.AppCredentialsConfig;
import com.gahyeonbot.listeners.AssistantVoiceChannelListener;
import com.gahyeonbot.listeners.MessageListener;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BotInitializerRunnerActivationStateTest {
    @Test
    void explicitlyDisabledBotIsDeploymentReadyWithoutCredentials() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        BotInitializerRunner runner = runner("", dataSource, false, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.DISABLED);
        assertThat(runner.getActivationReason()).isEqualTo("bot.enabled=false");
        assertThat(runner.isReady()).isTrue();
        verifyNoInteractions(dataSource);
    }

    @Test
    void enabledBotWithMissingTokenFailsClosedWithoutKillingBoot() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        BotInitializerRunner runner = runner("", dataSource, true, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.FAILED);
        assertThat(runner.getActivationReason()).isEqualTo("Discord token is missing or invalid");
        assertThat(runner.isReady()).isFalse();
        assertThat(runner.getShardManager()).isNull();
        assertThat(runner.hasLeadership()).isFalse();
        verifyNoInteractions(dataSource);
    }

    @Test
    void placeholderTokenNeverBecomesAHealthyStandby() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        BotInitializerRunner runner = runner(
                "your_discord_bot_token_here", dataSource, true, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.FAILED);
        assertThat(runner.isReady()).isFalse();
        verifyNoInteractions(dataSource);
    }

    @Test
    void postgresLockContentionIsAHealthyStandby() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);
        BotInitializerRunner runner = runner("unused-on-standby", dataSource, true, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.STANDBY);
        assertThat(runner.getActivationReason()).contains("advisory lock");
        assertThat(runner.isReady()).isTrue();
        assertThat(runner.hasLeadership()).isFalse();
        verify(connection).close();
    }

    @Test
    void leaderLockFailureIsNotMisreportedAsStandby() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));
        BotInitializerRunner runner = runner("unused-on-lock-failure", dataSource, true, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.FAILED);
        assertThat(runner.getActivationReason()).isEqualTo("Discord leader lock acquisition failed");
        assertThat(runner.isReady()).isFalse();
    }

    @Test
    void databaseMetadataFailureIsNotTreatedAsLockUnsupported() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));
        BotInitializerRunner runner = runner("syntactically-accepted-token", dataSource, true, true);

        runner.run();

        assertThat(runner.getActivationState())
                .isEqualTo(BotInitializerRunner.ActivationState.FAILED);
        assertThat(runner.getActivationReason()).isEqualTo("Discord leader lock acquisition failed");
        assertThat(runner.hasLeadership()).isFalse();
        verify(connection).close();
    }

    @Test
    void h2StillUsesSingleInstanceLeadershipWithoutHoldingAConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        BotInitializerRunner runner = runner("unused", dataSource, true, true);

        ReflectionTestUtils.invokeMethod(runner, "acquireLeadershipIfNeeded");

        assertThat(runner.hasLeadership()).isTrue();
        verify(connection).close();
    }

    private static BotInitializerRunner runner(
            String token, DataSource dataSource, boolean enabled, boolean leaderLockEnabled) {
        AppCredentialsConfig credentials = new AppCredentialsConfig();
        credentials.setToken(token);
        BotInitializerRunner runner = new BotInitializerRunner(
                credentials,
                mock(CommandRegistry.class),
                dataSource,
                mock(MessageListener.class),
                mock(AssistantVoiceChannelListener.class));
        ReflectionTestUtils.setField(runner, "botEnabled", enabled);
        ReflectionTestUtils.setField(runner, "leaderLockEnabled", leaderLockEnabled);
        return runner;
    }
}
