package com.gahyeonbot.services.news;

import com.gahyeonbot.entity.NewsArticle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class NewsEventRanker {
    public List<NewsEvent> rank(List<NewsArticle> articles, List<String> topics,
                                OffsetDateTime now, int maximumItems) {
        Map<String, List<NewsArticle>> clusters = new LinkedHashMap<>();
        for (NewsArticle article : articles) {
            if (article.getPublishedAt() == null || article.getPublishedAt().isAfter(now.plusMinutes(10))) continue;
            if (!matchesTopic(article, topics)) continue;
            clusters.computeIfAbsent(article.getEventFingerprint(), ignored -> new ArrayList<>()).add(article);
        }
        return clusters.values().stream()
                .filter(this::verified)
                .map(cluster -> event(cluster, now))
                .sorted(Comparator.comparingDouble(NewsEvent::score).reversed()
                        .thenComparing(NewsEvent::publishedAt, Comparator.reverseOrder()))
                .limit(Math.max(1, maximumItems))
                .toList();
    }

    private boolean verified(List<NewsArticle> cluster) {
        if (cluster.stream().anyMatch(NewsArticle::isOfficial)) return true;
        return cluster.stream().map(NewsArticle::getSourceDomain).filter(domain -> domain != null && !domain.isBlank())
                .distinct().count() >= 2;
    }

    private boolean matchesTopic(NewsArticle article, List<String> topics) {
        if (topics == null || topics.isEmpty()) return true;
        String haystack = (article.getTitle() + " " + (article.getSummary() == null ? "" : article.getSummary()))
                .toLowerCase(Locale.ROOT);
        return topics.stream().filter(topic -> topic != null && !topic.isBlank())
                .map(topic -> topic.toLowerCase(Locale.ROOT).strip()).anyMatch(haystack::contains);
    }

    private NewsEvent event(List<NewsArticle> cluster, OffsetDateTime now) {
        List<NewsArticle> ordered = cluster.stream().sorted(
                Comparator.comparing(NewsArticle::isOfficial).reversed()
                        .thenComparingInt(NewsArticle::getTrustTier)
                        .thenComparing(NewsArticle::getPublishedAt, Comparator.reverseOrder())).toList();
        NewsArticle lead = ordered.getFirst();
        Set<String> domains = new LinkedHashSet<>();
        ordered.forEach(article -> domains.add(article.getSourceDomain()));
        double hours = Math.max(0, Duration.between(lead.getPublishedAt(), now).toMinutes() / 60.0);
        double score = (lead.isOfficial() ? 5 : 0) + domains.size() * 2.0
                + Math.max(0, 4 - lead.getTrustTier()) + Math.max(0, 3 - hours / 12.0);
        return new NewsEvent(lead.getTitle(), lead.getSummary(), lead.getPublishedAt(), score, ordered);
    }

    public record NewsEvent(String title, String summary, OffsetDateTime publishedAt,
                            double score, List<NewsArticle> sources) { }
}
