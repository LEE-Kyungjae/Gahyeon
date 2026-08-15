package com.gahyeonbot.services.ai;

import com.gahyeonbot.services.weather.City;
import com.gahyeonbot.services.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.support.ToolCallbacks;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherToolsTest {

    @Mock
    private WeatherService weatherService;

    private WeatherTools tools;

    @BeforeEach
    void setUp() {
        tools = new WeatherTools(weatherService);
    }

    @Test
    void currentWeatherUsesCanonicalCityCodeWithoutNaturalLanguageHeuristics() {
        when(weatherService.buildCurrentWeatherMessage(City.COLMAR)).thenReturn("콜마르 현재 날씨");

        assertThat(tools.getCurrentWeather("colmar")).isEqualTo("콜마르 현재 날씨");

        verify(weatherService).buildCurrentWeatherMessage(City.COLMAR);
    }

    @Test
    void forecastRequiresExplicitIsoDateContract() {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        LocalDate end = start.plusDays(2);
        when(weatherService.buildForecastMessage(City.PRAGUE, start, end)).thenReturn("프라하 예보");

        assertThat(tools.getForecast(City.PRAGUE.name(), start.toString(), end.toString()))
                .isEqualTo("프라하 예보");

        verify(weatherService).buildForecastMessage(City.PRAGUE, start, end);
    }

    @Test
    void forecastRejectsNaturalLanguageDatesInsteadOfGuessing() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.getForecast("SEOUL", "다음주", "다음주"))
                .withMessageContaining("yyyy-MM-dd");
    }

    @Test
    void unknownLocationRequestsCanonicalLocationResolution() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.getCurrentWeather("콜마르"))
                .withMessageContaining("get_supported_weather_locations");
    }

    @Test
    void supportedLocationsExposeCodesForTheAgent() {
        assertThat(tools.getSupportedLocations())
                .contains("SEOUL | 서울 | 한국")
                .contains("COLMAR | 콜마르 | 프랑스");
    }

    @Test
    void springAiDiscoversTheThreeAgentTools() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "get_current_weather",
                        "get_weather_forecast",
                        "get_supported_weather_locations"
                );
    }

    @Test
    void supportedLocationsIncludeRepresentativeSpanishRegions() {
        String locations = tools.getSupportedLocations();

        assertThat(locations)
                .contains("MADRID | 마드리드 | 스페인")
                .contains("BARCELONA | 바르셀로나 | 스페인")
                .contains("SEVILLE | 세비야 | 스페인")
                .contains("LAS_PALMAS | 라스팔마스 | 스페인");
    }
}
