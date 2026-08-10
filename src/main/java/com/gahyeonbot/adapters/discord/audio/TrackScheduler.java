package com.gahyeonbot.adapters.discord.audio;

import net.dv8tion.jda.api.managers.AudioManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.gahyeonbot.services.tts.TtsTrackMetadata;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.file.Files;

/**
 * 음악 트랙 스케줄링을 관리하는 클래스.
 * 음악 대기열 관리, 자동 재생 전환, 트랙 종료 이벤트 처리를 담당합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class TrackScheduler extends AudioEventAdapter {

    private final AudioPlayer player;
    private final TrackQueue trackQueue; // 대기열 관리 클래스
    private final AudioManager audioManager;

    /**
     * TrackScheduler 생성자.
     * 
     * @param player 오디오 플레이어
     * @param audioManager Discord 오디오 매니저
     */
    public TrackScheduler(AudioPlayer player, AudioManager audioManager) {
        this.player = player;
        this.audioManager = audioManager;
        this.trackQueue = new TrackQueue();
    }

    /**
     * 트랙을 대기열에 추가하거나 즉시 재생합니다.
     * 
     * @param track 추가할 트랙
     */
    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            trackQueue.addTrack(track);
        }
    }

    /**
     * 대기열의 모든 트랙을 반환합니다.
     * 
     * @return 대기열의 트랙 목록
     */
    public List<AudioTrack> getQueue() {
        return trackQueue.getTracks();
    }

    /**
     * 대기열이 비어있는지 확인합니다.
     * 
     * @return 대기열이 비어있으면 true
     */
    public boolean isQueueEmpty() {
        return trackQueue.isEmpty();
    }

    /**
     * 다음 트랙으로 넘어갑니다.
     */
    public void nextTrack() {
        nextTrack(true);
    }

    private void nextTrack(boolean closeWhenEmpty) {
        AudioTrack nextTrack = trackQueue.pollTrack();
        if (nextTrack != null) {
            player.startTrack(nextTrack, false);
        } else if (closeWhenEmpty) {
            closeConnection();
        }
    }

    /**
     * 대기열을 초기화합니다.
     */
    public void clearQueue() {
        trackQueue.clear();
    }

    /** 제거된 TTS 파일까지 정리하면서 대기 중인 TTS만 버립니다. */
    public void clearTtsQueue() {
        trackQueue.removeIf(this::isTts).forEach(this::cleanupIfTts);
    }

    /** 음악은 보존하고 현재/대기 중인 비서 TTS만 중단합니다. */
    public void interruptTtsPlayback() {
        clearTtsQueue();
        AudioTrack current = player.getPlayingTrack();
        if (isTts(current)) {
            player.stopTrack();
        }
    }

    /**
     * 보이스 채널 연결을 종료합니다.
     */
    private void closeConnection() {
        if (audioManager != null && audioManager.getConnectedChannel() != null) {
            audioManager.closeAudioConnection();
            System.out.println("보이스 채널 연결이 종료되었습니다.");
        }
    }

    /**
     * 트랙 종료 이벤트를 처리합니다.
     * 
     * @param player 오디오 플레이어
     * @param track 종료된 트랙
     * @param endReason 종료 이유
     */
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        cleanupIfTts(track);
        boolean keepConnection = track != null
                && track.getUserData() instanceof TtsTrackMetadata meta
                && meta.keepConnection();
        if (!endReason.mayStartNext) {
            System.out.println("트랙 종료: " + track.getInfo().title + " (이유: " + endReason + ")");
        }
        nextTrack(!keepConnection);
    }

    private void cleanupIfTts(AudioTrack track) {
        if (track == null) return;
        Object data = track.getUserData();
        if (data instanceof TtsTrackMetadata meta && meta.deleteOnEnd() && meta.wavPath() != null) {
            try {
                Files.deleteIfExists(meta.wavPath());
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private boolean isTts(AudioTrack track) {
        return track != null && track.getUserData() instanceof TtsTrackMetadata;
    }
}
