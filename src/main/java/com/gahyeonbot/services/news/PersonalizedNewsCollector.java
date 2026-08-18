package com.gahyeonbot.services.news;

import com.gahyeonbot.entity.NewsArticle;
import com.gahyeonbot.repository.NewsArticleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class PersonalizedNewsCollector {
    private static final int MAXIMUM_FEED_BYTES = 2 * 1024 * 1024;

    private final PersonalizedNewsProperties properties;
    private final NewsFeedParser parser;
    private final NewsArticleRepository articleRepository;
    private final RestTemplate http;

    public PersonalizedNewsCollector(PersonalizedNewsProperties properties, NewsFeedParser parser,
                                     NewsArticleRepository articleRepository) {
        this.properties = properties;
        this.parser = parser;
        this.articleRepository = articleRepository;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(10_000);
        this.http = new RestTemplate(requestFactory);
    }

    @Scheduled(fixedDelayString = "${news.personalized.poll-millis:1800000}",
            initialDelayString = "${news.personalized.initial-delay-millis:60000}")
    public void collect() {
        if (!properties.isEnabled()) return;
        for (PersonalizedNewsProperties.Source source : properties.getSources()) {
            if (!valid(source)) {
                log.warn("맞춤뉴스 출처 설정 건너뜀 - id/url 필수");
                continue;
            }
            try {
                byte[] feed = http.execute(source.getUrl(), org.springframework.http.HttpMethod.GET, null, response -> {
                    byte[] bytes = response.getBody().readNBytes(MAXIMUM_FEED_BYTES + 1);
                    if (bytes.length > MAXIMUM_FEED_BYTES) throw new IllegalArgumentException("feed exceeds 2 MiB");
                    return bytes;
                });
                int saved = ingest(feed == null ? new byte[0] : feed, source);
                log.info("맞춤뉴스 수집 완료 - source={}, saved={}", source.getId(), saved);
            } catch (Exception error) {
                log.warn("맞춤뉴스 수집 실패 - source={}, reason={}", source.getId(), error.getMessage());
            }
        }
    }

    int ingest(byte[] feed, PersonalizedNewsProperties.Source source) {
        int saved = 0;
        for (NewsFeedParser.ParsedArticle parsed : parser.parse(feed, source)) {
            if (articleRepository.existsByCanonicalUrl(parsed.canonicalUrl())) continue;
            try {
                articleRepository.save(NewsArticle.builder()
                        .id(UUID.randomUUID().toString())
                        .canonicalUrl(parsed.canonicalUrl())
                        .sourceId(parsed.sourceId())
                        .sourceName(parsed.sourceName())
                        .sourceDomain(parsed.sourceDomain())
                        .official(parsed.official())
                        .trustTier(Math.max(1, parsed.trustTier()))
                        .title(parsed.title())
                        .summary(parsed.summary())
                        .publishedAt(parsed.publishedAt())
                        .discoveredAt(OffsetDateTime.now())
                        .eventFingerprint(parsed.eventFingerprint())
                        .build());
                saved++;
            } catch (DataIntegrityViolationException duplicate) {
                log.debug("맞춤뉴스 중복 URL 경쟁 무시 - url={}", parsed.canonicalUrl());
            }
        }
        return saved;
    }

    private boolean valid(PersonalizedNewsProperties.Source source) {
        return source != null && source.getId() != null && !source.getId().isBlank()
                && source.getName() != null && !source.getName().isBlank()
                && source.getUrl() != null && !source.getUrl().isBlank();
    }
}
