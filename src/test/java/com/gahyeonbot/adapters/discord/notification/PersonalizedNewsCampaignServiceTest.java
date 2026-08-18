package com.gahyeonbot.adapters.discord.notification;

import com.gahyeonbot.entity.NewsArticle;
import com.gahyeonbot.services.news.NewsEventRanker;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalizedNewsCampaignServiceTest {
    private final PersonalizedNewsCampaignService service =
            new PersonalizedNewsCampaignService(null, null, null, null, null, null);

    @Test
    void preservesCompleteSourceUrlsInsteadOfTruncatingAnEvent() {
        String longUrl = "https://official.example/" + "a".repeat(1750);
        NewsArticle firstSource = article("https://official.example/first");
        NewsArticle oversizedSource = article(longUrl);
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-18T08:00:00+09:00");
        var first = new NewsEventRanker.NewsEvent("First", "Summary", publishedAt, 10, List.of(firstSource));
        var oversized = new NewsEventRanker.NewsEvent("Oversized", "Summary", publishedAt, 9,
                List.of(oversizedSource));

        String message = service.format(List.of(first, oversized), ZoneId.of("Asia/Seoul"));

        assertThat(message).contains("https://official.example/first");
        assertThat(message).doesNotContain("Oversized").doesNotContain(longUrl);
        assertThat(message.length()).isLessThanOrEqualTo(1900);
    }

    private NewsArticle article(String url) {
        return NewsArticle.builder()
                .sourceName("Official")
                .canonicalUrl(url)
                .build();
    }
}
