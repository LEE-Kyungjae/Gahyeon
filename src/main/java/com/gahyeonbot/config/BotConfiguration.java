package com.gahyeonbot.config;

import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.gahyeonbot.adapters.discord.audio.SoundCloudSource;
import com.gahyeonbot.adapters.discord.audio.StreamingSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord 봇의 Spring 설정 클래스.
 * 각종 Bean을 등록하여 의존성 주입을 관리합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Configuration
public class BotConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(BotConfiguration.class);

    /**
     * 서버별 음악 매니저를 관리하는 Map을 Spring Bean으로 등록합니다.
     * ConcurrentHashMap을 사용하여 멀티스레드 환경에서 안전하게 동작합니다.
     *
     * @return 서버 ID와 GuildMusicManager를 매핑하는 Map
     */
    @Bean
    public Map<Long, GuildMusicManager> musicManagers() {
        return new ConcurrentHashMap<>();
    }

    /**
     * 기본 스트리밍 소스를 Spring Bean으로 등록합니다.
     * SoundCloud를 기본 스트리밍 소스로 사용합니다.
     *
     * @return StreamingSource 인스턴스 (SoundCloudSource)
     */
    @Bean
    public StreamingSource streamingSource() {
        return new SoundCloudSource();
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setErrorHandler(t -> logger.error("스케줄 작업 실행 중 오류", t));
        return scheduler;
    }
}
