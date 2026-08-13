package com.gahyeonbot.controllers;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
import com.gahyeonbot.adapters.health.AgentRuntimeReadiness;
import com.gahyeonbot.services.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness 기반 헬스체크 엔드포인트.
 * DB 연결과 봇 초기화 완료 여부를 확인하여
 * 배포 시 실제 준비 상태에서만 200을 반환합니다.
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final BotInitializerRunner botRunner;
    private final WeatherService weatherService;
    private final AgentRuntimeReadiness agentRuntimeReadiness;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean ready = true;

        // DB 연결 확인
        try (Connection conn = dataSource.getConnection()) {
            result.put("db", "UP");
        } catch (Exception e) {
            result.put("db", "DOWN");
            ready = false;
        }

        // Discord disabled and leader-standby are intentional ready states. Any activation
        // failure is fail-closed so a rollout cannot silently lose the Discord adapter.
        BotInitializerRunner.ActivationState discordState = botRunner.getActivationState();
        String botStatus = switch (discordState) {
            case READY -> botRunner.getShardManager() == null ? "DOWN" : "UP";
            case STANDBY -> "STANDBY";
            case DISABLED -> "DISABLED";
            case STARTING -> "STARTING";
            case FAILED -> "DOWN";
        };
        result.put("bot", botStatus);
        result.put("botState", discordState.name());
        result.put("botReason", botRunner.getActivationReason());
        if (!botRunner.isReady() || "DOWN".equals(botStatus)) ready = false;

        AgentRuntimeReadiness.Snapshot conversation = agentRuntimeReadiness.snapshot();
        result.put("conversationRequired", conversation.required());
        result.put("conversation", conversation.ready() ? "UP"
                : conversation.required() ? "DOWN" : "OPTIONAL_DISABLED");
        if (!conversation.deploymentReady()) ready = false;

        // Weather update visibility (should not block readiness)
        result.put("weatherCurrentLastAttemptAt", weatherService.getLastCurrentAttemptAt());
        result.put("weatherCurrentLastSuccessAt", weatherService.getLastCurrentSuccessAt());
        result.put("weatherCurrentLastError", weatherService.getLastCurrentError());
        result.put("weatherForecastLastAttemptAt", weatherService.getLastForecastAttemptAt());
        result.put("weatherForecastLastSuccessAt", weatherService.getLastForecastSuccessAt());
        result.put("weatherForecastLastError", weatherService.getLastForecastError());

        result.put("status", ready ? "UP" : "STARTING");
        return ready
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("service", "gahyeon", "status", "running");
    }
}
