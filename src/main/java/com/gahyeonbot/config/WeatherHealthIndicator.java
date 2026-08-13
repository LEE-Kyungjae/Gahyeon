package com.gahyeonbot.config;

import com.gahyeonbot.services.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Weather update freshness for Actuator health visibility.
 * This should not take the whole app down in normal cases; we expose freshness details instead.
 */
@Component
@ConditionalOnProperty(name = "weather.prefetch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WeatherHealthIndicator implements HealthIndicator {

    private static final Duration CURRENT_STALE_AFTER = Duration.ofHours(2);
    private static final Duration FORECAST_STALE_AFTER = Duration.ofHours(36);

    private final WeatherService weatherService;

    @Override
    public Health health() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentOkAt = weatherService.getLastCurrentSuccessAt();
        LocalDateTime forecastOkAt = weatherService.getLastForecastSuccessAt();

        boolean hasCurrent = currentOkAt != null;
        boolean hasForecast = forecastOkAt != null;
        boolean currentFresh = hasCurrent && currentOkAt.isAfter(now.minus(CURRENT_STALE_AFTER));
        boolean forecastFresh = hasForecast && forecastOkAt.isAfter(now.minus(FORECAST_STALE_AFTER));

        Health.Builder b = Health.up()
                .withDetail("currentLastAttemptAt", detail(weatherService.getLastCurrentAttemptAt()))
                .withDetail("currentLastSuccessAt", detail(currentOkAt))
                .withDetail("currentFresh", currentFresh)
                .withDetail("currentLastError", detail(weatherService.getLastCurrentError()))
                .withDetail("forecastLastAttemptAt", detail(weatherService.getLastForecastAttemptAt()))
                .withDetail("forecastLastSuccessAt", detail(forecastOkAt))
                .withDetail("forecastFresh", forecastFresh)
                .withDetail("forecastLastError", detail(weatherService.getLastForecastError()));

        // If we've never succeeded since boot, call it DOWN to surface setup issues.
        if (!hasCurrent && !hasForecast) {
            return Health.down()
                    .withDetail("reason", "no successful weather updates yet")
                    .withDetails(b.build().getDetails())
                    .build();
        }

        // Otherwise keep UP; freshness is indicated via details to avoid false alarms.
        return b.build();
    }

    private static Object detail(Object value) {
        return value == null ? "not-yet-recorded" : value;
    }
}
