package com.gahyeonbot.config;

import com.gahyeonbot.core.behavior.DeterministicBehaviorPolicy;
import com.gahyeonbot.core.tool.ToolPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Framework composition root for the platform-neutral Gahyeon domain. */
@Configuration
public class GahyeonCoreConfiguration {
    @Bean
    public DeterministicBehaviorPolicy deterministicBehaviorPolicy() {
        return new DeterministicBehaviorPolicy();
    }

    @Bean
    public ToolPolicy toolPolicy() {
        return new ToolPolicy();
    }
}
