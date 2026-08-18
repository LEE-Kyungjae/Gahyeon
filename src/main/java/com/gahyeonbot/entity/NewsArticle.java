package com.gahyeonbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "news_article", indexes = {
        @Index(name = "idx_news_article_published_at", columnList = "published_at"),
        @Index(name = "idx_news_article_event_fingerprint", columnList = "event_fingerprint")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "canonical_url", nullable = false, unique = true, length = 2048)
    private String canonicalUrl;

    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    @Column(name = "source_name", nullable = false, length = 200)
    private String sourceName;

    @Column(name = "source_domain", nullable = false, length = 255)
    private String sourceDomain;

    @Column(nullable = false)
    private boolean official;

    @Column(name = "trust_tier", nullable = false)
    private int trustTier;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(length = 4000)
    private String summary;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "discovered_at", nullable = false)
    private OffsetDateTime discoveredAt;

    @Column(name = "event_fingerprint", nullable = false, length = 64)
    private String eventFingerprint;
}
