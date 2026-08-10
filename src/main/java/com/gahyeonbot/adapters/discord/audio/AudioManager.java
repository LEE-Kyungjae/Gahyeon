package com.gahyeonbot.adapters.discord.audio;

import com.gahyeonbot.config.AppCredentialsConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.search.simplified.SearchTracksRequest;

/**
 * 오디오 재생을 관리하는 클래스.
 * LavaPlayer와 Spotify API를 통합하여 음악 재생 기능을 제공합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class AudioManager {
    private static final Logger logger = LoggerFactory.getLogger(AudioManager.class);
    private final AppCredentialsConfig config;
    @Value("${bot.enabled:true}")
    private boolean botEnabled;
    private AudioPlayerManager playerManager;
    private SpotifyApi spotifyApi;

    /**
     * AudioManager 초기화 메서드.
     * Spring 빈이 생성된 후 자동으로 호출됩니다.
     */
    @PostConstruct
    public void initialize() {
        if (!botEnabled) {
            logger.info("bot.enabled=false: Discord LavaPlayer/Spotify 초기화를 건너뜁니다.");
            return;
        }
        this.playerManager = new DefaultAudioPlayerManager();
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);

        spotifyApi = new SpotifyApi.Builder()
                .setClientId(config.getSpotifyClientId())
                .setClientSecret(config.getSpotifyClientSecret())
                .build();

        authenticateSpotify();
    }

    /**
     * Spotify API 인증을 수행합니다.
     */
    private void authenticateSpotify() {
        try {
            var credentials = spotifyApi.clientCredentials().build().execute();
            spotifyApi.setAccessToken(credentials.getAccessToken());
            logger.info("Spotify 인증 성공");
        } catch (Exception e) {
            logger.error("Spotify 인증 실패: LavaPlayer만 활성화됩니다.", e);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        if (playerManager != null) {
            logger.info("AudioPlayerManager 종료 중...");
            playerManager.shutdown();
        }
    }

    /**
     * 오디오 플레이어 매니저를 반환합니다.
     * 
     * @return AudioPlayerManager 인스턴스
     */
    public AudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    /**
     * Spotify 쿼리를 기반으로 SoundCloud 트랙을 검색합니다.
     * 
     * @param spotifyQuery Spotify 검색 쿼리
     * @return SoundCloud 검색 쿼리 문자열
     */
    public String getSoundCloudTrackFromSpotify(String spotifyQuery) {
        if (spotifyQuery == null || spotifyQuery.trim().isEmpty()) {
            logger.warn("유효하지 않은 Spotify 쿼리");
            return null;
        }

        try {
            SearchTracksRequest request = spotifyApi.searchTracks(spotifyQuery).build();
            Track[] tracks = request.execute().getItems();
            if (tracks.length == 0) {
                logger.info("Spotify에서 결과를 찾을 수 없습니다. 쿼리: {}", spotifyQuery);
                return null;
            }
            return buildSoundCloudQuery(tracks[0]);
        } catch (Exception e) {
            logger.error("Spotify API 오류", e);
            return null;
        }
    }

    /**
     * Spotify 트랙을 기반으로 SoundCloud 검색 쿼리를 생성합니다.
     * 
     * @param track Spotify 트랙
     * @return SoundCloud 검색 쿼리
     */
    private String buildSoundCloudQuery(Track track) {
        String artist = track.getArtists()[0].getName();
        String title = track.getName();
        String soundCloudQuery = "scsearch:" + artist + " " + title;
        logger.info("SoundCloud 쿼리 생성: {}", soundCloudQuery);
        return soundCloudQuery;
    }
}
