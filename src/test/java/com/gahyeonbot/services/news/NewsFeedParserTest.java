package com.gahyeonbot.services.news;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsFeedParserTest {
    private final NewsFeedParser parser = new NewsFeedParser();

    @Test
    void parsesRssAndRemovesTrackingParametersWhileKeepingPublicationTime() {
        PersonalizedNewsProperties.Source source = source(true);
        String feed = """
                <?xml version="1.0"?><rss version="2.0"><channel><item>
                <title>OpenAI releases a new agent runtime</title>
                <link>https://Example.com/news/agent?utm_source=rss&amp;edition=kr#top</link>
                <description>Official release details</description>
                <pubDate>Tue, 18 Aug 2026 01:00:00 GMT</pubDate>
                </item></channel></rss>
                """;

        var articles = parser.parse(feed.getBytes(StandardCharsets.UTF_8), source);

        assertThat(articles).singleElement().satisfies(article -> {
            assertThat(article.canonicalUrl()).isEqualTo("https://example.com/news/agent?edition=kr");
            assertThat(article.official()).isTrue();
            assertThat(article.publishedAt()).isNotNull();
            assertThat(article.eventFingerprint()).hasSize(64);
        });
    }

    @Test
    void rejectsDoctypeAndNonHttpNewsUrls() {
        PersonalizedNewsProperties.Source source = source(false);
        String xxe = "<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><rss><channel><item>"
                + "<title>&xxe;</title><link>https://example.com/a</link>"
                + "<pubDate>Tue, 18 Aug 2026 01:00:00 GMT</pubDate></item></channel></rss>";
        assertThatThrownBy(() -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8), source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.canonicalizeUrl("file:///tmp/article"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PersonalizedNewsProperties.Source source(boolean official) {
        PersonalizedNewsProperties.Source source = new PersonalizedNewsProperties.Source();
        source.setId("official-ai");
        source.setName("Official AI");
        source.setOfficial(official);
        source.setTrustTier(1);
        return source;
    }
}
