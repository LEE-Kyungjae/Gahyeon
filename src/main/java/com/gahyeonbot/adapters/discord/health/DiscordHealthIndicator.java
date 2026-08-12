package com.gahyeonbot.adapters.discord.health;

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
        BotInitializerRunner.ActivationState state = botRunner.getActivationState();
        String reason = botRunner.getActivationReason();

        if (state == BotInitializerRunner.ActivationState.DISABLED
                || state == BotInitializerRunner.ActivationState.STANDBY) {
            return Health.up()
                    .withDetail("state", state.name())
                    .withDetail("reason", reason)
                    .build();
        }
        if (state != BotInitializerRunner.ActivationState.READY) {
            return Health.down()
                    .withDetail("state", state.name())
                    .withDetail("reason", reason)
                    .build();
        }

        ShardManager sm = botRunner.getShardManager();
        if (sm == null) {
            return Health.down()
                    .withDetail("state", BotInitializerRunner.ActivationState.FAILED.name())
                    .withDetail("reason", "Discord READY state has no ShardManager")
                    .build();
        }
        long connectedShards = sm.getShards().stream()
                .filter(shard -> shard.getStatus() == JDA.Status.CONNECTED)
                .count();
        long totalShards = sm.getShards().size();

        if (totalShards > 0 && connectedShards == totalShards) {
            return Health.up()
                    .withDetail("state", state.name())
                    .withDetail("reason", reason)
                    .withDetail("shards", connectedShards + "/" + totalShards)
                    .withDetail("guilds", sm.getGuilds().size())
                    .build();
        }

        return Health.down()
                .withDetail("state", state.name())
                .withDetail("reason", "Not all Discord shards are connected")
                .withDetail("shards", connectedShards + "/" + totalShards)
                .build();
    }
}
