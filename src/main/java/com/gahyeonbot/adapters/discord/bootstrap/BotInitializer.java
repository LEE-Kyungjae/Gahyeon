package com.gahyeonbot.adapters.discord.bootstrap;

import com.gahyeonbot.config.AppCredentialsConfig;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

/**
 * Discord 봇의 초기화를 담당하는 클래스.
 * ShardManager를 설정하고 봇의 기본 상태를 구성합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class BotInitializer {

    private final AppCredentialsConfig config;

    //private final ConfigLoader config;
    
    /**
     * BotInitializer 생성자.
     * 
     * @param config 설정 로더
     */
    public BotInitializer(AppCredentialsConfig config) {
        this.config = config;
    }
    
    /**
     * Discord ShardManager를 초기화합니다.
     * 봇의 토큰을 사용하여 ShardManager를 생성하고 기본 설정을 적용합니다.
     * 
     * @return 초기화된 ShardManager 인스턴스
     * @throws IllegalArgumentException 토큰이 설정되지 않은 경우
     * @throws RuntimeException ShardManager 초기화 실패 시
     */
    public ShardManager initialize() throws IllegalArgumentException {
        String token = config.getToken();
        if (!isUsableToken(token)) {
            throw new IllegalArgumentException("TOKEN이 설정되지 않았거나 테스트 토큰입니다. Discord 봇이 비활성화됩니다.");
        }

        try {
            var daveSessionFactory = new LDJDADaveSessionFactory(new NativeDaveFactory());
            return DefaultShardManagerBuilder.createDefault(token)
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(daveSessionFactory))
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("데이트"))
                    .enableIntents(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_PRESENCES
                    )
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .enableCache(CacheFlag.ONLINE_STATUS)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("ShardManager 초기화 실패", e);
        }
    }

    static boolean isUsableToken(String token) {
        return token != null
                && !token.isBlank()
                && !token.startsWith("test_")
                && !token.equals("your_discord_bot_token_here");
    }

}
