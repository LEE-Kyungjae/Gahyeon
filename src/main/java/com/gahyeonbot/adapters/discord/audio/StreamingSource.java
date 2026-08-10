package com.gahyeonbot.adapters.discord.audio;

/**
 * 음악 스트리밍 소스를 정의하는 인터페이스.
 * 다양한 음악 플랫폼의 스트리밍 기능을 통일된 방식으로 제공합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public interface StreamingSource {
    /**
     * 스트리밍 URL 가져오기
     * @param query 검색할 트랙 정보
     * @return 스트리밍 URL
     */
    String getStreamUrl(String query);

    /**
     * 트랙의 메타데이터 가져오기 (선택사항)
     * @param query 검색할 트랙 정보
     * @return 트랙 메타데이터
     */
    String getTrackMetadata(String query);

    /**
     * 음악을 검색하고 스트리밍 URL을 반환합니다.
     * 
     * @param query 검색 쿼리
     * @return 스트리밍 URL
     */
    String search(String query);
}
