package com.gahyeonbot.controllers;

import com.gahyeonbot.adapters.discord.bootstrap.BotInitializerRunner;
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

        // 봇 초기화 완료 확인
        if (!botRunner.isReady()) {
            result.put("bot", "STARTING");
            ready = false;
        } else {
            result.put("bot", botRunner.getShardManager() != null ? "UP" : "DISABLED");
        }

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
        return Map.of("service", "gahyeonbot", "status", "running");
    }
}
