package com.gahyeonbot.adapters.discord.audio;

public class YouTubeSource implements StreamingSource {
    @Override
    public String getStreamUrl(String query) {
        // 유튜브 URL 추출 로직 (예: yt-dlp 활용)
        return "ytsearch:" + query; // 유튜브 검색 URL 생성
    }

    @Override
    public String getTrackMetadata(String query) {
        // 유튜브 메타데이터 로직 (선택사항)
        return "YouTube track metadata for: " + query;
    }

    @Override
    public String search(String query) {
        return "";
    }
}
