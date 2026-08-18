package com.gahyeonbot.services.news;

import com.gahyeonbot.entity.NewsArticle;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsEventRankerTest {
    private final NewsEventRanker ranker = new NewsEventRanker();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-18T12:00:00+09:00");

    @Test
    void acceptsOfficialOrTwoIndependentDomainsAndRejectsSingleSecondarySource() {
        List<NewsArticle> articles = List.of(
                article("official", "official.example", true, "event-a", "AI agent released"),
                article("report-a", "one.example", false, "event-b", "New GPU announced"),
                article("report-b", "two.example", false, "event-b", "New GPU announced"),
                article("rumor", "rumor.example", false, "event-c", "Unverified model rumor")
        );

        var events = ranker.rank(articles, List.of("AI", "GPU", "model"), now, 10);

        assertThat(events).hasSize(2);
        assertThat(events).anySatisfy(event -> assertThat(event.sources()).hasSize(2));
        assertThat(events).noneMatch(event -> event.title().contains("rumor"));
    }

    @Test
    void filtersUnrelatedTopicsAndFutureDatedArticles() {
        NewsArticle future = article("future", "official.example", true, "future", "AI from the future");
        future.setPublishedAt(now.plusHours(2));
        var events = ranker.rank(List.of(
                article("weather", "official.example", true, "weather", "Local weather"),
                future
        ), List.of("AI"), now, 5);

        assertThat(events).isEmpty();
    }

    @Test
    void ordersHigherScoredEventsFirstAndOfficialSourcesFirst() {
        NewsArticle secondary = article("secondary", "secondary.example", false, "shared", "AI release");
        NewsArticle official = article("official", "official.example", true, "shared", "AI release");
        NewsArticle lowerScore = article("older", "official.example", true, "older", "AI maintenance");
        lowerScore.setPublishedAt(now.minusHours(36));

        var events = ranker.rank(List.of(lowerScore, secondary, official), List.of("AI"), now, 10);

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().title()).isEqualTo("AI release");
        assertThat(events.getFirst().sources().getFirst().isOfficial()).isTrue();
    }

    private NewsArticle article(String id, String domain, boolean official, String fingerprint, String title) {
        return NewsArticle.builder()
                .id(id).canonicalUrl("https://" + domain + "/" + id)
                .sourceId(id).sourceName(id).sourceDomain(domain).official(official).trustTier(official ? 1 : 2)
                .title(title).summary(title).publishedAt(now.minusHours(1)).discoveredAt(now)
                .eventFingerprint(fingerprint).build();
    }
}
