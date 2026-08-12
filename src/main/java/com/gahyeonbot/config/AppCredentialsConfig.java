package com.gahyeonbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// AppCredentialsConfig.java
@Component
@ConfigurationProperties(prefix = "app.credentials")
@Getter
@Setter
public class AppCredentialsConfig {

    private String applicationId;

    private String token;

    private String spotifyClientId;

    private String spotifyClientSecret;

    /**
     * OpenAI API Key (선택적)
     * 설정되지 않으면 선택적인 OpenAI Moderation 검사만 비활성화됩니다.
     */
    private String openaiApiKey;

    /**
     * Zhipu AI GLM API Key (선택적)
     * 설정되지 않으면 대화 요약 기능이 비활성화됩니다.
     */
    private String glmApiKey;

    /**
     * Zhipu AI GLM Model (선택적)
     * 기본값은 application.yml에서 설정합니다.
     */
    private String glmModel;
}
