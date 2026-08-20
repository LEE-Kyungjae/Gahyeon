package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.application.speech.ExpressiveSpeechSynthesisPort;
import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QwenExpressiveTtsProperties.class)
public class QwenExpressiveTtsConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "gahyeon.speech.expressive.qwen.enabled",
            havingValue = "true")
    ExpressiveSpeechSynthesisPort qwenExpressiveSpeechSynthesisPort(
            QwenExpressiveTtsProperties properties) {
        return new QwenExpressiveTtsAdapter(properties);
    }

    @Bean
    @ConditionalOnProperty(
            name = "gahyeon.speech.expressive.qwen.enabled",
            havingValue = "true")
    StreamingExpressiveSpeechSynthesisPort qwenStreamingExpressiveSpeechSynthesisPort(
            QwenExpressiveTtsProperties properties,
            ObjectMapper mapper) {
        return new QwenStreamingExpressiveTtsAdapter(properties, mapper);
    }
}
