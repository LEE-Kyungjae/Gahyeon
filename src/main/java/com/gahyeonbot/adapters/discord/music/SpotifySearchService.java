package com.gahyeonbot.adapters.discord.music;

import com.gahyeonbot.config.AppCredentialsConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.authorization.client_credentials.ClientCredentialsRequest;
import se.michaelthelin.spotify.requests.data.search.simplified.SearchTracksRequest;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify 검색 및 토큰 관리 서비스.
 * Spotify API를 사용하여 트랙 검색 및 앨범 커버를 제공합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SpotifySearchService {
    private static final Logger logger = LoggerFactory.getLogger(SpotifySearchService.class);
    private static final long TOKEN_REFRESH_INTERVAL = 3600; // 초 단위 (1시간)

    private final AppCredentialsConfig config;
    @Value("${bot.enabled:true}")
    private boolean botEnabled;
    private SpotifyApi spotifyApi;
    private ScheduledExecutorService scheduler;
    private boolean isEnabled = false;

    /**
     * SpotifySearchService 초기화.
     * Spring 빈이 생성된 후 자동으로 호출됩니다.
     */
    @PostConstruct
    public void initialize() {
        if (!botEnabled) {
            logger.info("bot.enabled=false: Discord Spotify 검색 초기화를 건너뜁니다.");
            return;
        }
        String clientId = config.getSpotifyClientId();
        String clientSecret = config.getSpotifyClientSecret();

        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty() ||
                clientId.startsWith("your_") || clientSecret.startsWith("your_")) {
            logger.warn("Spotify CLIENT_ID 또는 CLIENT_SECRET이 설정되지 않았습니다. Spotify 검색 기능이 비활성화됩니다.");
            this.isEnabled = false;
            return;
        }

        try {
            this.spotifyApi = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .build();

            this.scheduler = Executors.newScheduledThreadPool(1);

            refreshAccessToken();
            scheduleTokenRefresh();
            this.isEnabled = true;
            logger.info("Spotify 검색 서비스가 활성화되었습니다.");
        } catch (Exception e) {
            logger.error("Spotify 초기화 실패. Spotify 검색 기능이 비활성화됩니다.", e);
            this.isEnabled = false;
        }
    }

    /**
     * Spotify 트랙 검색
     *
     * @param query 검색할 트랙 이름 또는 키워드
     * @return 첫 번째 검색 결과로 반환된 Track 객체, 검색 결과가 없으면 null
     */
    public Track searchTrack(String query) {
        if (!isEnabled) {
            logger.debug("Spotify 서비스가 비활성화되어 있습니다.");
            return null;
        }

        try {
            SearchTracksRequest searchTracksRequest = spotifyApi.searchTracks(query).build();
            var pagingTracks = searchTracksRequest.execute(); // 최신 API는 execute() 호출을 비동기로 제공 가능성 있음

            if (pagingTracks.getItems().length == 0) {
                logger.warn("Spotify 검색 결과가 없습니다. 쿼리: {}", query);
                return null;
            }

            logger.info("Spotify 검색 성공. 첫 번째 결과: {}", pagingTracks.getItems()[0].getName());
            return pagingTracks.getItems()[0];
        } catch (Exception e) {
            logger.error("Spotify 검색 실패. 쿼리: {}", query, e);
            return null;
        }
    }

    public String getAlbumCoverUrl(Track track) {
        if (track.getAlbum() != null && track.getAlbum().getImages().length > 0) {
            return track.getAlbum().getImages()[0].getUrl();
        }
        logger.warn("앨범 커버 이미지를 찾을 수 없습니다. 트랙: {}", track.getName());
        return null;
    }

    /**
     * Spotify API 액세스 토큰 갱신.
     */
    public void refreshAccessToken() {
        try {
            ClientCredentialsRequest clientCredentialsRequest = spotifyApi.clientCredentials().build();
            var credentials = clientCredentialsRequest.execute();

            spotifyApi.setAccessToken(credentials.getAccessToken());
            logger.info("Spotify API Access Token 갱신 성공. 유효 기간: {}초", credentials.getExpiresIn());
        } catch (Exception e) {
            logger.error("Spotify Access Token 갱신 실패", e);
        }
    }

    /**
     * 액세스 토큰 갱신 작업 스케줄링.
     */
    private void scheduleTokenRefresh() {
        scheduler.scheduleAtFixedRate(() -> {
            logger.info("액세스 토큰 갱신 작업 실행");
            refreshAccessToken();
        }, TOKEN_REFRESH_INTERVAL, TOKEN_REFRESH_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * 서비스 종료.
     * Spring 빈이 소멸될 때 자동으로 호출됩니다.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("SpotifySearchService 종료. 스케줄러 종료");
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("스케줄러 종료 실패", e);
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
