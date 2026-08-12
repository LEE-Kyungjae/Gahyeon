package com.gahyeonbot.adapters.unreal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled"},
        havingValue = "true")
public class UnrealWebSocketConfiguration implements WebSocketConfigurer {
    static final int MAX_TEXT_MESSAGE_BYTES = 65_536;
    static final int MAX_BINARY_MESSAGE_BYTES =
            UnrealStreamingTranscriptionStateMachine.SEQUENCE_HEADER_BYTES
                    + UnrealStreamingTranscriptionStateMachine.MAX_PCM_BYTES;
    private final UnrealWebSocketHandler handler;

    public UnrealWebSocketConfiguration(UnrealWebSocketHandler handler) {
        this.handler = handler;
    }

    @Bean
    @ConditionalOnMissingBean(ServletServerContainerFactoryBean.class)
    ServletServerContainerFactoryBean gahyeonUnrealWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BYTES);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/gahyeon/unreal/v1");
    }
}
