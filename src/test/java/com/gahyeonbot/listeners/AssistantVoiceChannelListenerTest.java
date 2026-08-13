package com.gahyeonbot.listeners;

import com.gahyeonbot.adapters.discord.config.GuildAssistantChannelsService;
import com.gahyeonbot.adapters.discord.voice.VoiceAssistantService;
import com.gahyeonbot.entity.GuildAssistantChannels;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantVoiceChannelListenerTest {
    private static final long GUILD_ID = 11L;
    private static final long VOICE_CHANNEL_ID = 22L;
    private static final long USER_ID = 33L;

    @Mock GuildAssistantChannelsService channelsService;
    @Mock VoiceAssistantService voiceAssistantService;
    @Mock GuildVoiceUpdateEvent event;
    @Mock Guild guild;
    @Mock Member member;
    @Mock User user;
    @Mock AudioChannelUnion left;

    private AssistantVoiceChannelListener listener;

    @BeforeEach
    void setUp() {
        listener = new AssistantVoiceChannelListener(channelsService, voiceAssistantService);
        when(event.getMember()).thenReturn(member);
        when(member.getUser()).thenReturn(user);
        when(user.isBot()).thenReturn(false);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD_ID);
        when(channelsService.find(GUILD_ID)).thenReturn(Optional.of(
                GuildAssistantChannels.builder()
                        .guildId(GUILD_ID)
                        .voiceChannelId(VOICE_CHANNEL_ID)
                        .textChannelId(44L)
                        .build()));
        when(event.getChannelLeft()).thenReturn(left);
        when(left.getIdLong()).thenReturn(VOICE_CHANNEL_ID);
        org.mockito.Mockito.lenient().when(member.getIdLong()).thenReturn(USER_ID);
    }

    @Test
    void ownerLeavingStopsTheAssistantSessionExactlyOnce() {
        when(voiceAssistantService.stopWhenOwnerLeaves(
                guild, USER_ID, VOICE_CHANNEL_ID)).thenReturn(true);

        listener.onGuildVoiceUpdate(event);

        verify(voiceAssistantService).stopWhenOwnerLeaves(guild, USER_ID, VOICE_CHANNEL_ID);
        verify(voiceAssistantService, never()).stop(guild);
    }

    @Test
    void lastHumanLeavingStopsEvenWhenTheyAreNotTheOwner() {
        when(voiceAssistantService.stopWhenOwnerLeaves(
                guild, USER_ID, VOICE_CHANNEL_ID)).thenReturn(false);
        when(left.getMembers()).thenReturn(List.of());

        listener.onGuildVoiceUpdate(event);

        verify(voiceAssistantService).stop(guild);
    }

    @Test
    void sessionRemainsWhenAnotherHumanIsStillInTheChannel() {
        Member remaining = org.mockito.Mockito.mock(Member.class);
        User remainingUser = org.mockito.Mockito.mock(User.class);
        when(remaining.getUser()).thenReturn(remainingUser);
        when(remainingUser.isBot()).thenReturn(false);
        when(voiceAssistantService.stopWhenOwnerLeaves(
                guild, USER_ID, VOICE_CHANNEL_ID)).thenReturn(false);
        when(left.getMembers()).thenReturn(List.of(remaining));

        listener.onGuildVoiceUpdate(event);

        verify(voiceAssistantService, never()).stop(guild);
    }

    @Test
    void unrelatedVoiceChannelDoesNotTouchTheSession() {
        when(left.getIdLong()).thenReturn(999L);

        listener.onGuildVoiceUpdate(event);

        verify(voiceAssistantService, never()).stopWhenOwnerLeaves(
                guild, USER_ID, VOICE_CHANNEL_ID);
        verify(voiceAssistantService, never()).stop(guild);
    }
}
