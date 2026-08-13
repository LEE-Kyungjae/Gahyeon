package com.gahyeonbot.adapters.discord.music;

import com.gahyeonbot.adapters.discord.audio.StreamingSource;
import com.gahyeonbot.models.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Combines Spotify metadata with the Discord music streaming source. */
@Service
@RequiredArgsConstructor
public class StreamingService {
    private final SpotifySearchService spotifySearchService;
    private final StreamingSource streamingSource;

    public SearchResult search(String query) {
        var track = spotifySearchService.searchTrack(query);
        String albumCoverUrl = null;
        String streamUrl;
        if (track != null) {
            albumCoverUrl = spotifySearchService.getAlbumCoverUrl(track);
            String fullQuery = track.getName() + " " + track.getArtists()[0].getName();
            streamUrl = streamingSource.getStreamUrl(fullQuery);
        } else {
            streamUrl = streamingSource.getStreamUrl(query);
        }
        return new SearchResult(streamUrl, albumCoverUrl);
    }
}
