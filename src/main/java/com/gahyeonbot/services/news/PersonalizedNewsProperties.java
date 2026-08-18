package com.gahyeonbot.services.news;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "news.personalized")
public class PersonalizedNewsProperties {
    private boolean enabled = false;
    private int lookbackHours = 30;
    private int maximumItems = 5;
    private List<String> topics = new ArrayList<>();
    private List<Source> sources = new ArrayList<>();

    @Data
    public static class Source {
        private String id;
        private String name;
        private String url;
        private boolean official;
        private int trustTier = 3;
    }
}
