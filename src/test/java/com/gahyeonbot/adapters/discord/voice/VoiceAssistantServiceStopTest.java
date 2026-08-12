package com.gahyeonbot.adapters.discord.voice;

import com.gahyeonbot.adapters.discord.DiscordAudioFileMaterializer;
import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.gahyeonbot.adapters.discord.music.MusicService;
import com.gahyeonbot.core.conversation.ConversationReadiness;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import com.gahyeonbot.services.assistant.AssistantProperties;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceAssistantServiceStopTest {
    @Mock TranscriptionUseCase transcription;
    @Mock ConversationUseCase conversation;
    @Mock ConversationReadiness conversationReadiness;
    @Mock DiscordIdentityMapper identityMapper;
    @Mock SpeechSynthesisUseCase speechSynthesis;
    @Mock DiscordAudioFileMaterializer audioFiles;
    @Mock MusicService musicService;
    @Mock com.gahyeonbot.adapters.discord.audio.AudioManager coreAudioManager;
    @Mock Guild guild;
    @Mock Member requester;
    @Mock GuildVoiceState voiceState;
    @Mock AudioChannelUnion channel;
    @Mock MessageChannel textChannel;
    @Mock GuildMusicManager musicManager;
    @Mock net.dv8tion.jda.api.managers.AudioManager discordAudioManager;

    private VoiceAssistantService service;

    @BeforeEach
    void setUp() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        service = new VoiceAssistantService(
                properties, transcription, conversation, conversationReadiness, identityMapper, speechSynthesis,
                audioFiles, musicService, coreAudioManager);
        when(transcription.isReady()).thenReturn(true);
        when(conversationReadiness.isReady()).thenReturn(true);
        when(guild.getIdLong()).thenReturn(101L);
        when(guild.getId()).thenReturn("101");
        when(requester.getVoiceState()).thenReturn(voiceState);
        when(requester.getIdLong()).thenReturn(202L);
        when(voiceState.getChannel()).thenReturn(channel);
        org.mockito.Mockito.lenient().when(channel.getIdLong()).thenReturn(303L);
        when(musicService.getOrCreateGuildMusicManager(guild)).thenReturn(musicManager);
        when(musicManager.getSendHandler()).thenReturn(null);
        when(guild.getAudioManager()).thenReturn(discordAudioManager);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void explicitStopClosesThePhysicalDiscordAudioConnection() {
        assertThat(service.start(guild, requester, textChannel).started()).isTrue();
        assertThat(service.isRunning(101L)).isTrue();

        assertThat(service.stop(guild)).isTrue();

        assertThat(service.isRunning(101L)).isFalse();
        verify(musicManager).interruptTtsPlayback();
        verify(discordAudioManager).setReceivingHandler(null);
        verify(discordAudioManager).closeAudioConnection();
    }

    @Test
    void ownerLeaveUsesTheSamePhysicalStopPath() {
        assertThat(service.start(guild, requester, textChannel).started()).isTrue();

        assertThat(service.stopWhenOwnerLeaves(guild, 202L, 303L)).isTrue();

        verify(discordAudioManager).closeAudioConnection();
        assertThat(service.isRunning(101L)).isFalse();
    }

    @Test
    void nonOwnerCannotStopTheSession() {
        assertThat(service.start(guild, requester, textChannel).started()).isTrue();

        assertThat(service.stopWhenOwnerLeaves(guild, 999L, 303L)).isFalse();

        verify(discordAudioManager, never()).closeAudioConnection();
        assertThat(service.isRunning(101L)).isTrue();
    }
}
