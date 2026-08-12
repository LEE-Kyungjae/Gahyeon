package com.gahyeonbot.adapters.discord.notification;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import com.gahyeonbot.entity.GitHubTrendingEvent;
import com.gahyeonbot.entity.RepoReadmeCache;
import com.gahyeonbot.repository.GitHubTrendingEventRepository;
import com.gahyeonbot.repository.RepoReadmeCacheRepository;
import com.gahyeonbot.services.ai.GlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Airflow가 수집/정규화한 README(readme_text)를 기반으로 summary_ko를 미리 채웁니다.
 * (DM 발송 스케줄 전에 돌려서 DM은 조립만 하도록)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoReadmeSummaryScheduler {

    private final BotInitializerRunner botInitializerRunner;
    private final GitHubTrendingEventRepository trendingEventRepository;
    private final RepoReadmeCacheRepository repoReadmeCacheRepository;
    private final GlmService glmService;

    @Value("${notifications.dm.trending-summary-enabled:true}")
    private boolean summaryEnabled;

    @Value("${notifications.dm.schedule-zone:Asia/Seoul}")
    private String scheduleZone;

    @Scheduled(
            cron = "${notifications.dm.trending-summary-cron:0 30 6 * * *}",
            zone = "${notifications.dm.schedule-zone:Asia/Seoul}"
    )
    public void fillKoreanSummaries() {
        if (!summaryEnabled) {
            return;
        }
        if (!glmService.isEnabled()) {
            log.warn("README summary 스케줄 중단 - GLM 비활성 상태(reason={})", glmService.getDisabledReason());
            return;
        }
        // Blue/Green 환경에서 1개 인스턴스만 수행.
        if (!botInitializerRunner.hasLeadership()) {
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of(scheduleZone));
        LocalDate snapshotDate = today;
        List<GitHubTrendingEvent> events =
                trendingEventRepository.findBySnapshotDateAndSentAtIsNullOrderByCreatedAtAsc(snapshotDate);

        if (events.isEmpty()) {
            snapshotDate = today.minusDays(1);
            events = trendingEventRepository.findBySnapshotDateAndSentAtIsNullOrderByCreatedAtAsc(snapshotDate);
        }

        if (events.isEmpty()) {
            log.info("README summary: 이벤트 없음 - today: {}, yesterday: {}", today, today.minusDays(1));
            return;
        }

        Set<String> repos = new LinkedHashSet<>();
        for (GitHubTrendingEvent e : events) {
            if (e.getRepoFullName() != null && !e.getRepoFullName().isBlank()) {
                repos.add(e.getRepoFullName().trim());
            }
        }

        int total = repos.size();
        int updated = 0;
        int skippedNoReadme = 0;
        int skippedAlready = 0;
        int failed = 0;

        for (String repoFullName : repos) {
            try {
                RepoReadmeCache latest = repoReadmeCacheRepository.findTopByRepoFullNameOrderByReadmeFetchedAtDescIdDesc(repoFullName)
                        .orElse(null);
                if (latest == null || latest.getReadmeText() == null || latest.getReadmeText().isBlank()) {
                    skippedNoReadme++;
                    continue;
                }
                if (latest.getSummaryKo() != null && !latest.getSummaryKo().isBlank()
                        && glmService.isCurrentReadmeSummaryModel(latest.getSummaryKoModel())) {
                    skippedAlready++;
                    continue;
                }
                if (fillOne(repoFullName)) {
                    updated++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("README summary fill 실패 - repo: {}, reason: {}", repoFullName, e.getMessage());
            }
        }

        log.info("README summary: snapshotDate={}, repos={}, updated={}, skippedAlready={}, skippedNoReadme={}, failed={}",
                snapshotDate, total, updated, skippedAlready, skippedNoReadme, failed);
    }

    @Transactional
    protected boolean fillOne(String repoFullName) {
        RepoReadmeCache latest = repoReadmeCacheRepository.findTopByRepoFullNameOrderByReadmeFetchedAtDescIdDesc(repoFullName)
                .orElse(null);
        if (latest == null) {
            return false;
        }
        if (latest.getSummaryKo() != null && !latest.getSummaryKo().isBlank()
                && glmService.isCurrentReadmeSummaryModel(latest.getSummaryKoModel())) {
            return false;
        }
        String readmeText = latest.getReadmeText();
        if (readmeText == null || readmeText.isBlank()) {
            return false;
        }

        String summary = glmService.summarizeRepoReadmeKo(repoFullName, readmeText);
        if (summary == null || summary.isBlank()) {
            return false;
        }

        latest.setSummaryKo(summary.trim());
        latest.setSummaryKoModel(glmService.getReadmeSummaryModel());
        latest.setSummaryKoUpdatedAt(OffsetDateTime.now());
        repoReadmeCacheRepository.save(latest);
        return true;
    }
}
