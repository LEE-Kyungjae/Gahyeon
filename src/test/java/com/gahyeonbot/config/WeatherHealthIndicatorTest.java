package com.gahyeonbot.config;

import com.gahyeonbot.services.weather.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeatherHealthIndicatorTest {
    @Test
    void reportsDownWithoutThrowingBeforeTheFirstWeatherUpdate() {
        var indicator = new WeatherHealthIndicator(mock(WeatherService.class));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("currentLastAttemptAt", "not-yet-recorded")
                .containsEntry("forecastLastSuccessAt", "not-yet-recorded");
    }
}
