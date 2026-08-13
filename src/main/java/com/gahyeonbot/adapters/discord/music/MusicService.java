package com.gahyeonbot.adapters.discord.music;

import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.audio.AudioManager;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 음악 재생을 관리하는 서비스 클래스.
 * 서버별 음악 매니저를 관리하고 음악 로딩 및 재생을 처리합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class MusicService {
    private final Map<Long, GuildMusicManager> musicManagers;
    private final AudioManager audioManager;

    /**
     * 서버의 음악 매니저를 가져오거나 새로 생성합니다.
     * 
     * @param guild Discord 서버
     * @return 서버의 음악 매니저
     */
    public GuildMusicManager getOrCreateGuildMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(
                guild.getIdLong(),
                id -> new GuildMusicManager(audioManager.getPlayerManager(), guild.getAudioManager())
        );
    }

    /**
     * 봇이 보이스 채널에 연결되어 있는지 확인하고, 연결되지 않은 경우 연결합니다.
     * 
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param guild Discord 서버
     * @param musicManager 음악 매니저
     * @return 연결 성공 시 true, 실패 시 false
     */
    public boolean ensureConnectedToVoiceChannel(SlashCommandInteractionEvent event, Guild guild, GuildMusicManager musicManager) {
        if (guild.getAudioManager().isConnected()) return true;

        var voiceChannel = event.getMember().getVoiceState().getChannel();
        if (voiceChannel == null) {
            ResponseUtil.replyError(event, "먼저 보이스 채널에 입장하세요.");
            return false;
        }

        guild.getAudioManager().openAudioConnection(voiceChannel);
        guild.getAudioManager().setSelfDeafened(true);
        guild.getAudioManager().setSendingHandler(musicManager.getSendHandler());
        return true;
    }

    /**
     * 음악을 로드하고 재생합니다.
     * 
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param streamUrl 스트리밍 URL
     * @param musicManager 음악 매니저
     * @param query 검색 쿼리
     * @param albumCoverUrl 앨범 커버 URL
     */
    public void loadAndPlay(SlashCommandInteractionEvent event, String streamUrl, GuildMusicManager musicManager, String query,String albumCoverUrl) {
        audioManager.getPlayerManager().loadItem(streamUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                handleTrackLoaded(event, musicManager, track, albumCoverUrl,streamUrl);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty()) {
                    handleTrackLoaded(event, musicManager, playlist.getTracks().get(0),albumCoverUrl, streamUrl);
                } else {
                    ResponseUtil.replyError(event, "재생 가능한 트랙이 없습니다.");
                }
            }

            @Override
            public void noMatches() {
                ResponseUtil.replyError(event, "노래를 찾을 수 없습니다: **" + query + "**");
            }

            @Override
            public void loadFailed(FriendlyException e) {
                ResponseUtil.replyError(event, "🚨 로딩 오류 발생: " + e.getMessage());
            }
        });
    }

    /**
     * 트랙이 로드되었을 때의 처리를 담당합니다.
     * 
     * @param event 슬래시 명령어 상호작용 이벤트
     * @param musicManager 음악 매니저
     * @param track 오디오 트랙
     * @param albumCoverUrl 앨범 커버 URL
     * @param streamUrl 스트리밍 URL
     */
    private void handleTrackLoaded(SlashCommandInteractionEvent event, GuildMusicManager musicManager, AudioTrack track, String albumCoverUrl,String streamUrl ) {

        if (!musicManager.playOrQueueTrack(track)) {
            ResponseUtil.replyEmbed(event, EmbedUtil.createQueueAddedEmbed(track, event.getUser()));
        } else {
            ResponseUtil.replyEmbed(event, EmbedUtil.createNowPlayingEmbed(event, track, albumCoverUrl, track.getInfo().uri));
        }
    }

}
