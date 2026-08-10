package com.gahyeonbot.adapters.discord.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

/**
 * 음악 트랙 대기열을 관리하는 클래스.
 * 음악 트랙의 추가, 제거, 조회 기능을 제공합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class TrackQueue {
    private final BlockingQueue<AudioTrack> queue;

    /**
     * TrackQueue 생성자.
     */
    public TrackQueue() {
        this.queue = new LinkedBlockingQueue<>();
    }

    /**
     * 트랙을 대기열에 추가합니다.
     * 
     * @param track 추가할 트랙
     */
    public void addTrack(AudioTrack track) {
        queue.offer(track);
    }

    /**
     * 대기열에서 다음 트랙을 가져옵니다.
     * 
     * @return 다음 트랙, 대기열이 비어있으면 null
     */
    public AudioTrack pollTrack() {
        return queue.poll();
    }

    /**
     * 대기열의 모든 트랙을 반환합니다.
     * 
     * @return 트랙 목록
     */
    public List<AudioTrack> getTracks() {
        return new ArrayList<>(queue);
    }

    /**
     * 대기열이 비어있는지 확인합니다.
     * 
     * @return 대기열이 비어있으면 true
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * 대기열을 초기화합니다.
     */
    public void clear() {
        queue.clear();
    }

    /**
     * 조건에 맞는 대기 트랙을 제거하고 반환합니다.
     */
    public List<AudioTrack> removeIf(Predicate<AudioTrack> predicate) {
        List<AudioTrack> removed = new ArrayList<>();
        for (AudioTrack track : queue) {
            if (predicate.test(track) && queue.remove(track)) {
                removed.add(track);
            }
        }
        return removed;
    }
}
