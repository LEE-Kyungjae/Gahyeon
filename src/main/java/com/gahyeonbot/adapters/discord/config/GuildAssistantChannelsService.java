package com.gahyeonbot.adapters.discord.config;

import com.gahyeonbot.entity.GuildAssistantChannels;
import com.gahyeonbot.repository.GuildAssistantChannelsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GuildAssistantChannelsService {
    private final GuildAssistantChannelsRepository repository;

    @Transactional(readOnly = true)
    public Optional<GuildAssistantChannels> find(long guildId) {
        return repository.findById(guildId);
    }

    @Transactional
    public GuildAssistantChannels save(
            long guildId,
            long categoryId,
            long textChannelId,
            long voiceChannelId,
            long createdBy) {
        LocalDateTime now = LocalDateTime.now();
        GuildAssistantChannels channels = repository.findById(guildId)
                .orElseGet(() -> GuildAssistantChannels.builder()
                        .guildId(guildId)
                        .createdBy(createdBy)
                        .createdAt(now)
                        .build());
        channels.setCategoryId(categoryId);
        channels.setTextChannelId(textChannelId);
        channels.setVoiceChannelId(voiceChannelId);
        channels.setUpdatedAt(now);
        return repository.save(channels);
    }
}
