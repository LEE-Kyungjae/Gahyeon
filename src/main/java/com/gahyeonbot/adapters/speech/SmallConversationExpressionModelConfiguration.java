package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.ConversationExpressionModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SmallConversationExpressionModelProperties.class)
public class SmallConversationExpressionModelConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "gahyeon.speech.expression-planner.small-model.enabled",
            havingValue = "true")
    ConversationExpressionModel smallConversationExpressionModel(
            SmallConversationExpressionModelProperties properties,
            ObjectMapper objectMapper) {
        return new HttpSmallConversationExpressionModel(properties, objectMapper);
    }
}
