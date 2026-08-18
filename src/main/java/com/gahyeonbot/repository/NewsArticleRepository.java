package com.gahyeonbot.repository;

import com.gahyeonbot.entity.NewsArticle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, String> {
    boolean existsByCanonicalUrl(String canonicalUrl);
    List<NewsArticle> findByPublishedAtAfterOrderByPublishedAtDesc(OffsetDateTime cutoff);
}
