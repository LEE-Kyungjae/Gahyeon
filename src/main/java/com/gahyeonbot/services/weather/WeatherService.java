package com.gahyeonbot.services.weather;

import com.gahyeonbot.entity.WeatherData;
import com.gahyeonbot.entity.WeatherForecast;
import com.gahyeonbot.repository.WeatherForecastRepository;
import com.gahyeonbot.repository.WeatherRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 날씨 데이터 서비스.
 * Open-Meteo API를 사용하여 여러 도시의 현재 날씨와 7일 예보를 조회합니다.
 *
 * @author GahyeonBot Team
 * @version 3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private static final int CACHE_DURATION_MINUTES = 30;
    private static final int NEAR_DAYS_THRESHOLD = 2; // 2일 이내는 최신 예보 사용
    private static final int WEATHER_CONNECT_TIMEOUT_MS = 5000;
    private static final int WEATHER_READ_TIMEOUT_MS = 15000;

    private final WeatherRepository weatherRepository;
    private final WeatherForecastRepository forecastRepository;
    @Value("${weather.prefetch.enabled:${bot.enabled:true}}")
    private boolean prefetchEnabled;
    private RestTemplate restTemplate;

    // 메모리 캐시 (도시별)
    private final Map<City, WeatherData> weatherCache = new ConcurrentHashMap<>();
    private LocalDateTime cacheTime;

    // Ops/health visibility
    private volatile LocalDateTime lastCurrentAttemptAt;
    private volatile LocalDateTime lastCurrentSuccessAt;
    private volatile String lastCurrentError;
    private volatile LocalDateTime lastForecastAttemptAt;
    private volatile LocalDateTime lastForecastSuccessAt;
    private volatile String lastForecastError;

    @PostConstruct
    public void initialize() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(WEATHER_CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(WEATHER_READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(requestFactory);
        log.info("날씨 서비스 초기화 완료 - 대상 도시: {}개", City.values().length);
    }

    /**
     * 앱 부팅 완료 후 초기 날씨/예보를 비동기로 로드합니다.
     * 부팅 중 외부 API 호출로 헬스체크가 지연되는 문제를 방지합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupWeatherAsync() {
        if (!prefetchEnabled) {
            log.info("weather.prefetch.enabled=false: 날씨 워밍업을 건너뜁니다.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                fetchAllCitiesWeather();
                fetchAllCitiesForecasts();
            } catch (Exception e) {
                log.warn("초기 날씨 워밍업 실패: {}", e.getMessage());
            }
        });
    }

    /**
     * 모든 도시의 현재 날씨 정보를 가져옵니다.
     */
    public Map<City, WeatherData> getAllCitiesWeather() {
        // 캐시 확인
        if (!weatherCache.isEmpty() && cacheTime != null &&
                cacheTime.plusMinutes(CACHE_DURATION_MINUTES).isAfter(LocalDateTime.now())) {
            return new HashMap<>(weatherCache);
        }

        // DB에서 최신 데이터 조회
        List<WeatherData> latestData = weatherRepository.findLatestForAllCities();
        Map<City, WeatherData> result = new HashMap<>();

        for (WeatherData weather : latestData) {
            try {
                City city = City.valueOf(weather.getCity());
                result.put(city, weather);
                weatherCache.put(city, weather);
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 도시 코드: {}", weather.getCity());
            }
        }

        if (!result.isEmpty()) {
            this.cacheTime = LocalDateTime.now();
        }

        return result;
    }

    /**
     * 대화에 사용할 날씨 컨텍스트 문자열 생성 (현재 날씨 + 7일 예보)
     */
    public String getWeatherContext() {
        StringBuilder sb = new StringBuilder();

        // 현재 날씨
        String currentWeather = getCurrentWeatherContext();
        if (!currentWeather.isEmpty()) {
            sb.append(currentWeather).append("\n\n");
        }

        // 7일 예보
        String forecast = getForecastContext();
        if (!forecast.isEmpty()) {
            sb.append(forecast);
        }

        return sb.toString().trim();
    }

    /**
     * 현재 날씨 컨텍스트 생성
     */
    private String getCurrentWeatherContext() {
        Map<City, WeatherData> allWeather = getAllCitiesWeather();

        if (allWeather.isEmpty()) {
            return "";
        }

        // 국가별로 그룹화
        Map<String, List<Map.Entry<City, WeatherData>>> byCountry = allWeather.entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getKey().getCountry()));

        StringBuilder sb = new StringBuilder("[현재 날씨]\n");

        // 한국을 먼저, 나머지는 알파벳 순
        List<String> countries = new ArrayList<>(byCountry.keySet());
        countries.sort((a, b) -> {
            if (a.equals("한국")) return -1;
            if (b.equals("한국")) return 1;
            return a.compareTo(b);
        });

        for (String country : countries) {
            sb.append("▸ ").append(country).append(": ");
            List<String> cityWeathers = byCountry.get(country).stream()
                    .map(e -> {
                        WeatherData w = e.getValue();
                        return String.format("%s %.1f°C %s",
                                w.getCityName(), w.getTemperature(), w.getWeatherDescription());
                    })
                    .collect(Collectors.toList());
            sb.append(String.join(" / ", cityWeathers)).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 7일 예보 컨텍스트 생성
     * - 가까운 날짜(0-2일): 최신 예보 사용
     * - 먼 날짜(3-6일): 첫 예보 사용
     */
    private String getForecastContext() {
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder("[7일 예보]\n");

        // 서울 기준으로 날짜별 예보 표시 (대표)
        City representativeCity = City.SEOUL;
        List<WeatherForecast> forecasts = getSmartForecasts(representativeCity, today, today.plusDays(6));

        if (forecasts.isEmpty()) {
            return "";
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN);

        for (WeatherForecast f : forecasts) {
            sb.append(String.format("▸ %s: %s %.0f~%.0f°C 강수확률%d%%\n",
                    f.getForecastDate().format(formatter),
                    f.getWeatherDescription(),
                    f.getTempMin(), f.getTempMax(),
                    f.getPrecipitationProbability() != null ? f.getPrecipitationProbability() : 0));
        }

        // 여행 도시들 요약 (오늘~3일 후)
        sb.append("\n[여행지 날씨 요약 (3일간)]\n");
        for (City city : City.values()) {
            if (city == City.SEOUL) continue;

            List<WeatherForecast> cityForecasts = getSmartForecasts(city, today, today.plusDays(2));
            if (!cityForecasts.isEmpty()) {
                String summary = cityForecasts.stream()
                        .map(f -> String.format("%.0f~%.0f°C", f.getTempMin(), f.getTempMax()))
                        .collect(Collectors.joining(" → "));
                sb.append(String.format("▸ %s(%s): %s\n",
                        city.getKoreanName(), city.getCountry(), summary));
            }
        }

        return sb.toString().trim();
    }

    /**
     * 스마트 예보 조회: 날짜 거리에 따라 최신/첫 예보 선택
     */
    private List<WeatherForecast> getSmartForecasts(City city, LocalDate startDate, LocalDate endDate) {
        List<WeatherForecast> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            long daysUntil = ChronoUnit.DAYS.between(today, date);
            Optional<WeatherForecast> forecast;

            if (daysUntil <= NEAR_DAYS_THRESHOLD) {
                // 가까운 날짜: 최신 예보 사용
                forecast = forecastRepository.findLatestForecast(city.name(), date);
            } else {
                // 먼 날짜: 첫 예보 사용 (예보 변경 전 원본)
                forecast = forecastRepository.findFirstForecast(city.name(), date);
            }

            forecast.ifPresent(result::add);
        }

        return result;
    }

    /**
     * 매시간 현재 날씨 업데이트
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void scheduledWeatherUpdate() {
        if (!prefetchEnabled) return;
        log.info("스케줄된 현재 날씨 업데이트 시작 - {}개 도시", City.values().length);
        fetchAllCitiesWeather();
    }

    /**
     * 매일 오전 6시에 7일 예보 업데이트
     */
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void scheduledForecastUpdate() {
        if (!prefetchEnabled) return;
        log.info("스케줄된 예보 업데이트 시작 - {}개 도시", City.values().length);
        fetchAllCitiesForecasts();
        cleanupOldForecasts();
    }

    /**
     * 모든 도시의 현재 날씨 데이터 조회
     */
    @Transactional
    public void fetchAllCitiesWeather() {
        lastCurrentAttemptAt = LocalDateTime.now();
        lastCurrentError = null;
        int successCount = 0;
        int failCount = 0;

        for (City city : City.values()) {
            try {
                WeatherData weather = fetchCurrentWeatherForCity(city);
                if (weather != null) {
                    weatherRepository.save(weather);
                    weatherCache.put(city, weather);
                    successCount++;
                } else {
                    failCount++;
                }
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("현재 날씨 조회 실패 - {}: {}", city.getKoreanName(), e.getMessage());
                lastCurrentError = e.getMessage();
                failCount++;
            }
        }

        this.cacheTime = LocalDateTime.now();
        if (successCount > 0) {
            lastCurrentSuccessAt = cacheTime;
        }
        log.info("현재 날씨 업데이트 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    /**
     * 모든 도시의 7일 예보 데이터 조회
     */
    @Transactional
    public void fetchAllCitiesForecasts() {
        lastForecastAttemptAt = LocalDateTime.now();
        lastForecastError = null;
        int successCount = 0;
        int failCount = 0;

        for (City city : City.values()) {
            try {
                List<WeatherForecast> forecasts = fetchForecastsForCity(city);
                if (!forecasts.isEmpty()) {
                    forecastRepository.saveAll(forecasts);
                    successCount++;
                } else {
                    failCount++;
                }
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("예보 조회 실패 - {}: {}", city.getKoreanName(), e.getMessage());
                lastForecastError = e.getMessage();
                failCount++;
            }
        }

        if (successCount > 0) {
            lastForecastSuccessAt = LocalDateTime.now();
        }
        log.info("예보 업데이트 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    public LocalDateTime getLastCurrentAttemptAt() {
        return lastCurrentAttemptAt;
    }

    public LocalDateTime getLastCurrentSuccessAt() {
        return lastCurrentSuccessAt;
    }

    public String getLastCurrentError() {
        return lastCurrentError;
    }

    public LocalDateTime getLastForecastAttemptAt() {
        return lastForecastAttemptAt;
    }

    public LocalDateTime getLastForecastSuccessAt() {
        return lastForecastSuccessAt;
    }

    public String getLastForecastError() {
        return lastForecastError;
    }

    public Optional<WeatherData> getLatestWeather(City city) {
        if (city == null) return Optional.empty();
        WeatherData cached = weatherCache.get(city);
        if (cached != null) {
            return Optional.of(cached);
        }
        return weatherRepository.findLatestByCity(city.name());
    }

    public String buildCurrentWeatherMessage(City city) {
        City c = city != null ? city : City.SEOUL;
        Optional<WeatherData> dataOpt = getLatestWeather(c);
        if (dataOpt.isEmpty()) {
            return c.getDisplayName() + " 현재 날씨 데이터를 찾지 못했어.";
        }

        WeatherData w = dataOpt.get();
        return String.format(
                "%s 현재 날씨: %.1f°C, %s, 강수량 %.1fmm, 강수확률 %d%%, 풍속 %.1fkm/h\n(관측: %s)",
                w.getCityName() + "(" + w.getCountry() + ")",
                w.getTemperature(),
                w.getWeatherDescription(),
                w.getPrecipitation(),
                w.getPrecipitationProbability() != null ? w.getPrecipitationProbability() : 0,
                w.getWindSpeed(),
                w.getFetchedAt()
        );
    }

    public String buildForecastMessage(City city, LocalDate startDate, LocalDate endDate) {
        City c = city != null ? city : City.SEOUL;
        LocalDate today = LocalDate.now();
        LocalDate start = startDate != null ? startDate : today;
        LocalDate end = endDate != null ? endDate : start.plusDays(6);
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        // Open-Meteo 16-day forecast: clamp to avoid asking for ranges we won't have.
        LocalDate maxEnd = today.plusDays(15);
        if (end.isAfter(maxEnd)) {
            end = maxEnd;
        }

        List<WeatherForecast> forecasts = getSmartForecasts(c, start, end);
        if (forecasts.isEmpty()) {
            return c.getDisplayName() + " 예보 데이터를 찾지 못했어.";
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN);
        LocalDate first = start;
        LocalDate last = end;

        double minMin = forecasts.stream().mapToDouble(WeatherForecast::getTempMin).min().orElse(Double.NaN);
        double maxMax = forecasts.stream().mapToDouble(WeatherForecast::getTempMax).max().orElse(Double.NaN);
        int maxPop = forecasts.stream()
                .map(f -> f.getPrecipitationProbability() != null ? f.getPrecipitationProbability() : 0)
                .max(Integer::compareTo)
                .orElse(0);
        Optional<WeatherForecast> popDay = forecasts.stream()
                .max(Comparator.comparingInt(f -> f.getPrecipitationProbability() != null ? f.getPrecipitationProbability() : 0));

        LocalDateTime latestFetched = forecasts.stream()
                .map(WeatherForecast::getFetchedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s 예보 (%s~%s)\n",
                c.getDisplayName(),
                first.format(fmt),
                last.format(fmt)));
        sb.append(String.format("요약: %.0f~%.0f°C", minMin, maxMax));
        if (popDay.isPresent()) {
            WeatherForecast f = popDay.get();
            sb.append(String.format(", 강수확률 최고 %d%%(%s)", maxPop, f.getForecastDate().format(fmt)));
        }
        if (latestFetched != null) {
            sb.append(String.format("\n(데이터: %s)", latestFetched));
        }
        sb.append("\n\n");

        // Print day-by-day in the requested window. Fill missing days explicitly.
        Map<LocalDate, WeatherForecast> byDate = new HashMap<>();
        for (WeatherForecast f : forecasts) {
            byDate.put(f.getForecastDate(), f);
        }
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            WeatherForecast f = byDate.get(d);
            if (f == null) {
                sb.append(String.format("- %s: 데이터 없음\n", d.format(fmt)));
                continue;
            }
            int pop = f.getPrecipitationProbability() != null ? f.getPrecipitationProbability() : 0;
            sb.append(String.format("- %s: %s %.0f~%.0f°C 강수확률%d%%\n",
                    d.format(fmt),
                    f.getWeatherDescription(),
                    f.getTempMin(),
                    f.getTempMax(),
                    pop));
        }

        return sb.toString().trim();
    }

    /**
     * 30일 이전 예보 데이터 정리
     */
    @Transactional
    public void cleanupOldForecasts() {
        LocalDate cutoffDate = LocalDate.now().minusDays(30);
        forecastRepository.deleteOldForecasts(cutoffDate);
        log.info("오래된 예보 데이터 정리 완료 - 기준일: {}", cutoffDate);
    }

    /**
     * 특정 도시의 현재 날씨 조회
     */
    private WeatherData fetchCurrentWeatherForCity(City city) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    city.buildCurrentWeatherUrl(),
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseCurrentWeatherResponse(city, response.getBody());
            }
        } catch (Exception e) {
            log.error("현재 날씨 API 호출 실패 - {}: {}", city.getKoreanName(), e.getMessage());
        }
        return null;
    }

    /**
     * 특정 도시의 7일 예보 조회
     */
    private List<WeatherForecast> fetchForecastsForCity(City city) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    city.buildForecastUrl(),
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseForecastResponse(city, response.getBody());
            }
        } catch (Exception e) {
            log.error("예보 API 호출 실패 - {}: {}", city.getKoreanName(), e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 현재 날씨 응답 파싱
     */
    @SuppressWarnings("unchecked")
    private WeatherData parseCurrentWeatherResponse(City city, Map<String, Object> response) {
        Map<String, Object> current = (Map<String, Object>) response.get("current");
        Map<String, Object> hourly = (Map<String, Object>) response.get("hourly");

        Double temperature = ((Number) current.get("temperature_2m")).doubleValue();
        Double precipitation = ((Number) current.get("precipitation")).doubleValue();
        Double windSpeed = ((Number) current.get("wind_speed_10m")).doubleValue();
        Integer weatherCode = ((Number) current.get("weather_code")).intValue();

        Integer precipitationProbability = 0;
        if (hourly != null && hourly.get("precipitation_probability") != null) {
            List<Integer> probabilities = (List<Integer>) hourly.get("precipitation_probability");
            if (!probabilities.isEmpty()) {
                precipitationProbability = probabilities.get(0);
            }
        }

        return WeatherData.builder()
                .city(city.name())
                .cityName(city.getKoreanName())
                .country(city.getCountry())
                .temperature(temperature)
                .precipitation(precipitation)
                .windSpeed(windSpeed)
                .precipitationProbability(precipitationProbability)
                .weatherDescription(getWeatherDescription(weatherCode))
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 7일 예보 응답 파싱
     */
    @SuppressWarnings("unchecked")
    List<WeatherForecast> parseForecastResponse(City city, Map<String, Object> response) {
        Map<String, Object> daily = (Map<String, Object>) response.get("daily");
        if (daily == null) {
            return Collections.emptyList();
        }

        List<String> dates = (List<String>) daily.get("time");
        List<Number> tempMaxList = (List<Number>) daily.get("temperature_2m_max");
        List<Number> tempMinList = (List<Number>) daily.get("temperature_2m_min");
        List<Number> weatherCodes = (List<Number>) daily.get("weather_code");
        List<Number> precipProbList = (List<Number>) daily.get("precipitation_probability_max");

        List<WeatherForecast> forecasts = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        if (dates == null) {
            return Collections.emptyList();
        }

        for (int i = 0; i < dates.size(); i++) {
            Number tempMax = valueAt(tempMaxList, i);
            Number tempMin = valueAt(tempMinList, i);
            Number weatherCode = valueAt(weatherCodes, i);
            if (dates.get(i) == null || tempMax == null || tempMin == null || weatherCode == null) {
                log.warn("불완전한 예보 행 건너뜀 - 도시: {}, index: {}", city.getKoreanName(), i);
                continue;
            }
            LocalDate forecastDate = LocalDate.parse(dates.get(i));
            Number precipitationProbability = valueAt(precipProbList, i);

            WeatherForecast forecast = WeatherForecast.builder()
                    .city(city.name())
                    .cityName(city.getKoreanName())
                    .country(city.getCountry())
                    .forecastDate(forecastDate)
                    .tempMax(tempMax.doubleValue())
                    .tempMin(tempMin.doubleValue())
                    .weatherDescription(getWeatherDescription(weatherCode.intValue()))
                    .precipitationProbability(
                            precipitationProbability != null ? precipitationProbability.intValue() : null)
                    .fetchedAt(now)
                    .build();

            forecasts.add(forecast);
        }

        return forecasts;
    }

    private static Number valueAt(List<Number> values, int index) {
        return values != null && index >= 0 && index < values.size() ? values.get(index) : null;
    }

    /**
     * WMO 날씨 코드를 한글 설명으로 변환
     */
    private String getWeatherDescription(int code) {
        return switch (code) {
            case 0 -> "맑음";
            case 1, 2, 3 -> "구름 조금";
            case 45, 48 -> "안개";
            case 51, 53, 55 -> "이슬비";
            case 56, 57 -> "얼어붙는 이슬비";
            case 61, 63, 65 -> "비";
            case 66, 67 -> "얼어붙는 비";
            case 71, 73, 75 -> "눈";
            case 77 -> "싸락눈";
            case 80, 81, 82 -> "소나기";
            case 85, 86 -> "눈 소나기";
            case 95 -> "뇌우";
            case 96, 99 -> "우박 동반 뇌우";
            default -> "흐림";
        };
    }
}
