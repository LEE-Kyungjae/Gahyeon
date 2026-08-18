package com.gahyeonbot.adapters.discord.notification;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import com.gahyeonbot.entity.DmSubscription;
import com.gahyeonbot.entity.NewsletterTheme;
import com.gahyeonbot.repository.NewsArticleRepository;
import com.gahyeonbot.services.news.NewsEventRanker;
import com.gahyeonbot.services.news.PersonalizedNewsProperties;
import com.gahyeonbot.services.notification.DmSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalizedNewsCampaignService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final PersonalizedNewsProperties properties;
    private final NewsArticleRepository articleRepository;
    private final NewsEventRanker ranker;
    private final DmSubscriptionService subscriptionService;
    private final DmDispatchService dispatchService;
    private final BotInitializerRunner botInitializerRunner;

    @Scheduled(cron = "${news.personalized.digest-cron:0 0 8 * * *}",
            zone = "${news.personalized.schedule-zone:Asia/Seoul}")
    public void deliverDigest() {
        if (!properties.isEnabled() || !botInitializerRunner.hasLeadership()) return;
        OffsetDateTime now = OffsetDateTime.now();
        var articles = articleRepository.findByPublishedAtAfterOrderByPublishedAtDesc(
                now.minusHours(Math.max(1, properties.getLookbackHours())));
        var events = ranker.rank(articles, properties.getTopics(), now, properties.getMaximumItems());
        if (events.isEmpty()) {
            log.info("맞춤뉴스 발송 생략 - 검증된 관련 사건 없음");
            return;
        }
        String message = format(events, ZoneId.of("Asia/Seoul"));
        if (message.isBlank()) {
            log.warn("맞춤뉴스 발송 생략 - 출처를 보존한 채 메시지 길이 제한을 충족할 수 없음");
            return;
        }
        String runId = "personalized-news-" + now.toLocalDate();
        for (DmSubscription subscription : subscriptionService.getOptedInSubscriptions(NewsletterTheme.PERSONALIZED_NEWS)) {
            dispatchService.dispatchGeneratedMessage(runId, subscription.getUserId(), message,
                    runId + "-" + subscription.getUserId());
        }
    }

    String format(List<NewsEventRanker.NewsEvent> events, ZoneId zone) {
        StringBuilder output = new StringBuilder("📊 맞춤뉴스\n");
        int index = 1;
        for (NewsEventRanker.NewsEvent event : events) {
            StringBuilder block = new StringBuilder();
            block.append("\n").append(index).append(". ").append(event.title()).append("\n");
            if (event.summary() != null && !event.summary().isBlank()) {
                String summary = event.summary().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
                block.append(summary, 0, Math.min(summary.length(), 220)).append("\n");
            }
            block.append("발행: ").append(event.publishedAt().atZoneSameInstant(zone).format(TIME)).append("\n");
            for (var source : event.sources()) {
                block.append("- ").append(source.getSourceName()).append(": <")
                        .append(source.getCanonicalUrl()).append(">\n");
            }
            if (output.length() + block.length() > 1900) break;
            output.append(block);
            index++;
        }
        return index == 1 ? "" : output.toString();
    }
}
