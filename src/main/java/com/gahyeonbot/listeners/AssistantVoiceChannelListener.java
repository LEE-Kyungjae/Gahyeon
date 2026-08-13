package com.gahyeonbot.listeners;

import com.gahyeonbot.adapters.discord.config.GuildAssistantChannelsService;
import com.gahyeonbot.adapters.discord.voice.VoiceAssistantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class AssistantVoiceChannelListener extends ListenerAdapter {
    private final GuildAssistantChannelsService channelsService;
    private final VoiceAssistantService voiceAssistantService;

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getMember().getUser().isBot()) return;
        var configured = channelsService.find(event.getGuild().getIdLong()).orElse(null);
        if (configured == null) return;

        AudioChannel joined = event.getChannelJoined();
        if (joined != null && joined.getIdLong() == configured.getVoiceChannelId()) {
            var textChannel = event.getGuild().getTextChannelById(configured.getTextChannelId());
            if (textChannel == null) {
                log.warn("자동 음성 비서 시작 실패: 전용 채팅 채널 없음 guild={}", event.getGuild().getIdLong());
                return;
            }
            var result = voiceAssistantService.start(event.getGuild(), event.getMember(), textChannel);
            if (result.started()) textChannel.sendMessage(result.message()).queue();
            return;
        }

        AudioChannel left = event.getChannelLeft();
        if (left != null && left.getIdLong() == configured.getVoiceChannelId()) {
            boolean stoppedWithOwner = voiceAssistantService.stopWhenOwnerLeaves(
                    event.getGuild(), event.getMember().getIdLong(), left.getIdLong());
            if (!stoppedWithOwner
                    && left.getMembers().stream().noneMatch(member -> !member.getUser().isBot())) {
                voiceAssistantService.stop(event.getGuild());
            }
        }
    }
}
