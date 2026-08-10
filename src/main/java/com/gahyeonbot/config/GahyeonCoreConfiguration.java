package com.gahyeonbot.config;

import com.gahyeonbot.core.behavior.DeterministicBehaviorPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Framework composition root for the platform-neutral Gahyeon domain. */
@Configuration
public class GahyeonCoreConfiguration {
    @Bean
    public DeterministicBehaviorPolicy deterministicBehaviorPolicy() {
        return new DeterministicBehaviorPolicy();
    }
}
