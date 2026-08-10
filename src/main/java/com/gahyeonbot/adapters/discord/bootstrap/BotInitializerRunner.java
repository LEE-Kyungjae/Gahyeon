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
    private volatile boolean ready = false;
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
            ready = true;
            return;
        }
        tryActivateBot("startup");
        ready = true;
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
        if (!acquireLeadershipIfNeeded()) {
            logger.info("리더십 락 미획득({}) - 봇 초기화를 대기합니다.", reason);
            return;
        }
        try {
            startBot();
        } catch (IllegalArgumentException e) {
            logger.warn("Discord 봇 초기화 실패: {}. 애플리케이션은 계속 실행됩니다.", e.getMessage());
            releaseLeadership();
        } catch (Exception e) {
            logger.error("Discord 봇 초기화 중 예기치 않은 오류 발생", e);
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

        CommandManager commandManager = new CommandManager();
        commandManager.addCommands(commandRegistry.getCommands());
        commandManager.setShardManager(shardManager);
        commandManager.synchronizeCommands().join();

        ListenerManager listenerManager = new ListenerManager(
                shardManager, config, commandManager, messageListener, assistantVoiceChannelListener);
        listenerManager.registerListeners();

        logger.info("Discord 봇이 성공적으로 초기화되었습니다. (leadership={})", hasLeadership);
        logger.info("모든 명령어 등록 및 서비스 기동이 완료되었습니다.");
    }

    private boolean acquireLeadershipIfNeeded() {
        if (!leaderLockEnabled) {
            hasLeadership = true;
            return true;
        }
        if (hasLeadership) {
            return true;
        }

        try {
            Connection connection = dataSource.getConnection();
            if (!isPostgres(connection)) {
                logger.warn("PostgreSQL이 아니어서 리더십 락을 건너뜁니다. 단일 인스턴스 운영을 권장합니다.");
                leaderLockConnection = connection;
                hasLeadership = true;
                return true;
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_try_advisory_lock(" + leaderLockKey + ")")) {
                if (rs.next() && rs.getBoolean(1)) {
                    leaderLockConnection = connection;
                    hasLeadership = true;
                    logger.info("PostgreSQL advisory lock 획득 성공. key={}", leaderLockKey);
                    return true;
                }
            }
            connection.close();
            return false;
        } catch (Exception e) {
            logger.error("리더십 락 획득 시도 중 오류", e);
            return false;
        }
    }

    private boolean isPostgres(Connection connection) {
        try {
            String name = connection.getMetaData().getDatabaseProductName();
            return name != null && name.toLowerCase().contains("postgres");
        } catch (Exception e) {
            return false;
        }
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
        return ready;
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
