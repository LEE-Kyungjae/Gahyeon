package com.gahyeonbot.adapters.discord.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 서버별 음악 재생을 관리하는 클래스.
 * 각 Discord 서버의 음악 재생 상태와 대기열을 관리합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class GuildMusicManager {
    private static final Logger logger = LoggerFactory.getLogger(GuildMusicManager.class);

    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final AudioSendHandler sendHandler;

    /**
     * GuildMusicManager 생성자.
     * 
     * @param manager 오디오 플레이어 매니저
     * @param audioManager Discord 오디오 매니저
     */
    public GuildMusicManager(AudioPlayerManager manager, AudioManager audioManager) {
        if (manager == null) {
            throw new IllegalArgumentException("AudioPlayerManager는 null일 수 없습니다.");
        }

        this.player = manager.createPlayer();
        this.scheduler = new TrackScheduler(player, audioManager);
        this.player.addListener(scheduler);

        // AudioSendHandler 캐싱
        this.sendHandler = new AudioPlayerSendHandler(player);

        String connectedChannelName = audioManager.getConnectedChannel() != null
                ? audioManager.getConnectedChannel().getName()
                : "연결 없음";

        logger.info("GuildMusicManager 초기화됨 - AudioPlayer 상태: {}, AudioManager 채널: {}",
                player.getPlayingTrack() != null ? "재생 중" : "대기 중", connectedChannelName);
    }

    /**
     * 오디오 플레이어를 반환합니다.
     * 
     * @return AudioPlayer 인스턴스
     */
    public AudioPlayer getPlayer() {
        return player;
    }

    /**
     * 트랙 스케줄러를 반환합니다.
     * 
     * @return TrackScheduler 인스턴스
     */
    public TrackScheduler getScheduler() {
        return scheduler;
    }

    /**
     * 오디오 전송 핸들러를 반환합니다.
     * 
     * @return AudioSendHandler 인스턴스
     */
    public AudioSendHandler getSendHandler() {
        return sendHandler;
    }

    /**
     * 현재 재생 중인 트랙을 반환합니다.
     * 
     * @return 현재 재생 중인 트랙
     */
    public AudioTrack getCurrentTrack() {
        return player.getPlayingTrack();
    }

    /**
     * 음악 대기열을 반환합니다.
     * 
     * @return 음악 대기열
     */
    public List<AudioTrack> getQueue() {
        return scheduler.getQueue();
    }

    /**
     * 음악을 일시정지합니다.
     */
    public void pause() {
        player.setPaused(true);
    }

    /**
     * 음악을 재생합니다.
     */
    public void resume() {
        player.setPaused(false);
    }

    /**
     * 음악을 정지합니다.
     */
    public void stop() {
        player.stopTrack();
        scheduler.clearQueue();
        logger.info("음악 재생 중단 및 대기열 초기화");
    }

    /**
     * 다음 곡으로 넘어갑니다.
     */
    public void skip() {
        scheduler.nextTrack();
    }

    /**
     * 트랙을 재생하거나 대기열에 추가합니다.
     * 
     * @param track 재생할 트랙
     * @return 즉시 재생되면 true, 대기열에 추가되면 false
     */
    public boolean playOrQueueTrack(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            scheduler.queue(track);
            return false; // 대기열에 추가됨
        }
        return true; // 즉시 재생됨
    }

    /**
     * 음악이 일시정지 상태인지 확인합니다.
     * 
     * @return 일시정지 상태이면 true
     */
    public boolean isPaused() {
        return player.isPaused();
    }

    public String getCurrentTrackTitle() {
        AudioTrack currentTrack = player.getPlayingTrack();
        return (currentTrack != null) ? currentTrack.getInfo().title : "현재 재생 중인 트랙이 없습니다.";
    }

    public void queueTrack(AudioTrack track) {
        scheduler.queue(track);
    }

    public boolean skipCurrentTrack() {
        if (isPlaying()) {
            scheduler.nextTrack();
            return true;
        }
        return false;
    }

    public boolean isQueueEmpty() {
        return scheduler.isQueueEmpty();
    }

    public void clearQueue() {
        scheduler.clearQueue();
    }

    public void interruptTtsPlayback() {
        scheduler.interruptTtsPlayback();
    }

    public void stopPlayback(AudioManager audioManager) {
        stop(); // 음악 정지 및 대기열 초기화
        if (audioManager != null && audioManager.isConnected()) {
            audioManager.closeAudioConnection();
        }
    }

    public boolean isPlaying() {
        return player.getPlayingTrack() != null;
    }

    public void setPaused(boolean b) {
    }
}
