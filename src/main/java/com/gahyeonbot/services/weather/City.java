package com.gahyeonbot.services.weather;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 날씨 조회 대상 도시 목록.
 * 유럽 여행 주요 도시들의 좌표 정보를 포함합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Getter
@RequiredArgsConstructor
public enum City {
    // 한국
    SEOUL("서울", "한국", 37.5665, 126.9780, "Asia/Seoul"),

    // 아이슬란드
    REYKJAVIK("레이캬비크", "아이슬란드", 64.1466, -21.9426, "Atlantic/Reykjavik"),

    // 프랑스
    PARIS("파리", "프랑스", 48.8566, 2.3522, "auto"),
    COLMAR("콜마르", "프랑스", 48.0793, 7.3585, "auto"),

    // 스위스
    INTERLAKEN("인터라켄", "스위스", 46.6863, 7.8632, "auto"),
    LUCERNE("루체른", "스위스", 47.0502, 8.3093, "auto"),

    // 독일
    MUNICH("뮌헨", "독일", 48.1351, 11.5820, "auto"),

    // 체코
    PRAGUE("프라하", "체코", 50.0755, 14.4378, "auto"),
    CESKY_KRUMLOV("체스키크롬로프", "체코", 48.8106, 14.3150, "auto"),

    // 오스트리아
    VIENNA("비엔나", "오스트리아", 48.2082, 16.3738, "auto"),

    // 헝가리
    BUDAPEST("부다페스트", "헝가리", 47.4979, 19.0402, "auto"),

    // 슬로베니아
    LJUBLJANA("류블랴나", "슬로베니아", 46.0569, 14.5058, "auto"),

    // 이탈리아
    VENICE("베니스", "이탈리아", 45.4408, 12.3155, "auto"),
    DOLOMITES("돌로미티", "이탈리아", 46.5405, 12.1357, "auto"),
    FLORENCE("피렌체", "이탈리아", 43.7696, 11.2558, "auto"),
    ROME("로마", "이탈리아", 41.9028, 12.4964, "auto"),

    // 스페인 주요 권역
    MADRID("마드리드", "스페인", 40.4168, -3.7038, "Europe/Madrid"),
    BARCELONA("바르셀로나", "스페인", 41.3874, 2.1686, "Europe/Madrid"),
    SEVILLE("세비야", "스페인", 37.3891, -5.9845, "Europe/Madrid"),
    VALENCIA("발렌시아", "스페인", 39.4699, -0.3763, "Europe/Madrid"),
    BILBAO("빌바오", "스페인", 43.2630, -2.9350, "Europe/Madrid"),
    SANTIAGO_DE_COMPOSTELA("산티아고데콤포스텔라", "스페인", 42.8782, -8.5448, "Europe/Madrid"),
    PALMA_DE_MALLORCA("팔마데마요르카", "스페인", 39.5696, 2.6502, "Europe/Madrid"),
    LAS_PALMAS("라스팔마스", "스페인", 28.1235, -15.4363, "Atlantic/Canary");

    private final String koreanName;
    private final String country;
    private final double latitude;
    private final double longitude;
    private final String timezone;

    /**
     * 현재 날씨 API URL 생성
     */
    public String buildCurrentWeatherUrl() {
        return String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,precipitation,weather_code,wind_speed_10m" +
                "&hourly=precipitation_probability" +
                "&forecast_days=1&timezone=%s",
                latitude, longitude, timezone
        );
    }

    /**
     * 최대 16일 예보 API URL 생성
     */
    public String buildForecastUrl() {
        return String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
                "&forecast_days=16&timezone=%s",
                latitude, longitude, timezone
        );
    }

    /**
     * 표시용 이름 (도시명, 국가)
     */
    public String getDisplayName() {
        return koreanName + "(" + country + ")";
    }
}
