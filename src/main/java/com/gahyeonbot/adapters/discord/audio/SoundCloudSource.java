package com.gahyeonbot.adapters.discord.audio;

public class SoundCloudSource implements StreamingSource {
    @Override
    public String getStreamUrl(String query) {
        // 사운드클라우드 스트리밍 URL 추출 로직
        return "scsearch:" + query;
    }

    @Override
    public String getTrackMetadata(String query) {
        // 사운드클라우드 메타데이터 로직 (선택사항)
        return "SoundCloud track metadata for: " + query;
    }

    @Override
    public String search(String query) {
        return "";
    }
}
