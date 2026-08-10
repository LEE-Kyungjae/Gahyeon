package com.gahyeonbot.config;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Discord 봇 상태를 Actuator 헬스체크에 통합.
 * /api/actuator/health 에서 discord 상태를 확인할 수 있습니다.
 */
@Component
@RequiredArgsConstructor
public class DiscordHealthIndicator implements HealthIndicator {

    private final BotInitializerRunner botRunner;

    @Override
    public Health health() {
        if (!botRunner.isBotEnabled()) {
            return Health.up().withDetail("reason", "bot.enabled=false").build();
        }

        if (!botRunner.isReady()) {
            return Health.down().withDetail("reason", "initializing").build();
        }

        ShardManager sm = botRunner.getShardManager();
        if (sm == null) {
            return Health.up().withDetail("reason", "token invalid, bot disabled").build();
        }

        long connectedShards = sm.getShards().stream()
                .filter(shard -> shard.getStatus() == JDA.Status.CONNECTED)
                .count();
        long totalShards = sm.getShards().size();

        if (connectedShards == totalShards) {
            return Health.up()
                    .withDetail("shards", connectedShards + "/" + totalShards)
                    .withDetail("guilds", sm.getGuilds().size())
                    .build();
        }

        return Health.down()
                .withDetail("shards", connectedShards + "/" + totalShards)
                .build();
    }
}
