package com.gahyeonbot.adapters.discord.config;

import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.gahyeonbot.adapters.discord.audio.SoundCloudSource;
import com.gahyeonbot.adapters.discord.audio.StreamingSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Discord-only audio and music adapter composition. */
@Configuration
public class DiscordAudioConfiguration {
    @Bean
    public Map<Long, GuildMusicManager> musicManagers() {
        return new ConcurrentHashMap<>();
    }

    @Bean
    public StreamingSource streamingSource() {
        return new SoundCloudSource();
    }
}
