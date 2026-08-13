package com.gahyeonbot.adapters.discord.bootstrap;

import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.config.AppCredentialsConfig;
import com.gahyeonbot.adapters.discord.command.CommandRegistry;
import com.gahyeonbot.listeners.CommandManager;
import com.gahyeonbot.listeners.ListenerManager;
import com.gahyeonbot.listeners.AssistantVoiceChannelListener;
import com.gahyeonbot.listeners.MessageListener;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Discord 봇의 초기화를 담당하는 Spring CommandLineRunner.
 * Spring Boot 애플리케이션이 시작될 때 자동으로 실행되어 봇을 초기화합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class BotInitializerRunner implements CommandLineRunner {

    public enum ActivationState {
        STARTING(false),
        READY(true),
        STANDBY(true),
        DISABLED(true),
        FAILED(false);

        private final boolean deploymentReady;

        ActivationState(boolean deploymentReady) {
            this.deploymentReady = deploymentReady;
        }

        public boolean isDeploymentReady() {
            return deploymentReady;
        }
    }

    private enum LeadershipOutcome {
        ACQUIRED,
        STANDBY,
        FAILED
    }

    private record LeadershipAttempt(LeadershipOutcome outcome, String reason) {
    }

    private static final Logger logger = LoggerFactory.getLogger(BotInitializerRunner.class);
    private static final long DEFAULT_LOCK_KEY = 1220338955082399845L;

    private final AppCredentialsConfig config;
    private final CommandRegistry commandRegistry;
    private final DataSource dataSource;
    private final MessageListener messageListener;
    private final AssistantVoiceChannelListener assistantVoiceChannelListener;

    @Value("${bot.enabled:true}")
    private boolean botEnabled;
    @Value("${bot.leader-lock.enabled:true}")
    private boolean leaderLockEnabled;
    @Value("${bot.leader-lock.key:" + DEFAULT_LOCK_KEY + "}")
    private long leaderLockKey;

    private ShardManager shardManager;
    private volatile String activationReason = "Discord activation has not started";
    private volatile ActivationState activationState = ActivationState.STARTING;
    private Connection leaderLockConnection;
    private volatile boolean hasLeadership = false;

    /**
     * Discord 봇을 초기화하고 리스너를 등록합니다.
     *
     * @param args 명령줄 인수
     * @throws Exception 초기화 중 발생할 수 있는 예외
     */
    @Override
    public void run(String... args) throws Exception {
        if (!botEnabled) {
            logger.info("bot.enabled=false 설정으로 Discord 봇 초기화를 건너뜁니다.");
            transitionTo(ActivationState.DISABLED, "bot.enabled=false");
            return;
        }
        tryActivateBot("startup");
    }

    @Scheduled(fixedDelayString = "${bot.leader-lock.retry-ms:30000}")
    public void retryLeadership() {
        if (!botEnabled || shardManager != null) {
            return;
        }
        tryActivateBot("retry");
    }

    private synchronized void tryActivateBot(String reason) {
        if (shardManager != null) {
            return;
        }
        if (!BotInitializer.isUsableToken(config.getToken())) {
            transitionTo(ActivationState.FAILED, "Discord token is missing or invalid");
            logger.warn("Discord token이 없거나 placeholder여서 readiness를 DOWN으로 유지합니다.");
            return;
        }
        LeadershipAttempt leadership = acquireLeadershipIfNeeded();
        if (leadership.outcome() == LeadershipOutcome.STANDBY) {
            transitionTo(ActivationState.STANDBY, leadership.reason());
            logger.info("리더십 락 미획득({}) - 정상 standby로 봇 초기화를 대기합니다.", reason);
            return;
        }
        if (leadership.outcome() == LeadershipOutcome.FAILED) {
            transitionTo(ActivationState.FAILED, leadership.reason());
            return;
        }
        transitionTo(ActivationState.STARTING,
                "Discord leadership acquired; initializing (" + reason + ")");
        try {
            startBot();
            if (shardManager == null) {
                throw new IllegalStateException("Discord initialization returned no ShardManager");
            }
            transitionTo(ActivationState.READY, "Discord shards connected");
        } catch (IllegalArgumentException e) {
            logger.warn("Discord 봇 초기화 실패: {}. readiness를 DOWN으로 유지합니다.", e.getMessage());
            transitionTo(ActivationState.FAILED, "Discord token is missing or invalid");
            resetFailedShardManager();
            releaseLeadership();
        } catch (Exception e) {
            logger.error("Discord 봇 초기화 중 예기치 않은 오류 발생", e);
            transitionTo(ActivationState.FAILED, "Discord initialization failed");
            resetFailedShardManager();
            releaseLeadership();
        }
    }

    private void startBot() throws Exception {
        BotInitializer botInitializer = new BotInitializer(config);
        shardManager = botInitializer.initialize();

        logger.info("JDA 준비 대기 중...");
        for (var shard : shardManager.getShards()) {
            shard.awaitReady();
        }
        logger.info("JDA 준비 완료. 총 {}개 길드 감지됨.", shardManager.getGuilds().size());
        EmbedUtil.init(shardManager.getShards().get(0).getSelfUser().getEffectiveAvatarUrl());

        CommandManager commandManager = assembleCommandManager(commandRegistry, shardManager);
        commandManager.synchronizeCommands().join();

        ListenerManager listenerManager = new ListenerManager(
                shardManager, config, commandManager, messageListener, assistantVoiceChannelListener);
        listenerManager.registerListeners();

        logger.info("Discord 봇이 성공적으로 초기화되었습니다. (leadership={})", hasLeadership);
        logger.info("모든 명령어 등록 및 서비스 기동이 완료되었습니다.");
    }

    static CommandManager assembleCommandManager(
            CommandRegistry registry, ShardManager shardManager) {
        CommandManager manager = new CommandManager();
        manager.addCommands(registry.getCommands());
        manager.setShardManager(shardManager);
        if (!manager.registeredStableNames().equals(
                registry.getCommands().stream().map(command -> command.getName())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
            throw new IllegalStateException("Discord command assembly lost a registry command");
        }
        return manager;
    }

    private LeadershipAttempt acquireLeadershipIfNeeded() {
        if (!leaderLockEnabled) {
            hasLeadership = true;
            return new LeadershipAttempt(
                    LeadershipOutcome.ACQUIRED, "Discord leader lock disabled");
        }
        if (hasLeadership) {
            return new LeadershipAttempt(
                    LeadershipOutcome.ACQUIRED, "Discord leadership already held");
        }

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!isPostgres(connection)) {
                logger.warn("PostgreSQL이 아니어서 리더십 락을 건너뜁니다. 단일 인스턴스 운영을 권장합니다.");
                connection.close();
                connection = null;
                hasLeadership = true;
                return new LeadershipAttempt(
                        LeadershipOutcome.ACQUIRED,
                        "Leader lock unsupported by the configured database");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_try_advisory_lock(" + leaderLockKey + ")")) {
                if (rs.next() && rs.getBoolean(1)) {
                    leaderLockConnection = connection;
                    connection = null;
                    hasLeadership = true;
                    logger.info("PostgreSQL advisory lock 획득 성공. key={}", leaderLockKey);
                    return new LeadershipAttempt(
                            LeadershipOutcome.ACQUIRED, "PostgreSQL advisory lock acquired");
                }
            }
            return new LeadershipAttempt(
                    LeadershipOutcome.STANDBY,
                    "PostgreSQL advisory lock is held by the active Discord instance");
        } catch (Exception e) {
            logger.error("리더십 락 획득 시도 중 오류", e);
            return new LeadershipAttempt(
                    LeadershipOutcome.FAILED, "Discord leader lock acquisition failed");
        } finally {
            closeQuietly(connection);
        }
    }

    private void resetFailedShardManager() {
        ShardManager failedManager = shardManager;
        shardManager = null;
        if (failedManager != null) {
            try {
                failedManager.shutdown();
            } catch (RuntimeException cleanupFailure) {
                logger.warn("실패한 ShardManager 정리 중 오류: {}", cleanupFailure.getMessage());
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // The activation outcome has already been decided; cleanup must not replace it.
        }
    }

    private void transitionTo(ActivationState state, String reason) {
        activationReason = reason;
        activationState = state;
    }

    private boolean isPostgres(Connection connection) throws java.sql.SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        return name != null && name.toLowerCase().contains("postgres");
    }

    private synchronized void releaseLeadership() {
        hasLeadership = false;
        if (leaderLockConnection != null) {
            try {
                leaderLockConnection.close();
            } catch (Exception e) {
                logger.warn("리더십 락 연결 종료 중 오류: {}", e.getMessage());
            } finally {
                leaderLockConnection = null;
            }
        }
    }

    public boolean isReady() {
        return activationState.isDeploymentReady();
    }

    public ActivationState getActivationState() {
        return activationState;
    }

    public String getActivationReason() {
        return activationReason;
    }

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public ShardManager getShardManager() {
        return shardManager;
    }

    /**
     * Blue/Green 등 다중 인스턴스에서 스케줄 작업을 1개 인스턴스만 수행하도록 게이트에 사용.
     */
    public boolean hasLeadership() {
        return hasLeadership;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        if (shardManager != null) {
            logger.info("ShardManager 종료 중...");
            shardManager.shutdown();
        }
        releaseLeadership();
    }
}
