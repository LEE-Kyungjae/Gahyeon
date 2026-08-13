package com.gahyeonbot.adapters.discord.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TtsSpeechTextTest {

    @Test
    void removesEmojiMarkdownAndUrlsWhileKeepingReadableText() {
        assertThat(TtsSpeechText.sanitize(
                "**가현**: 결과는 [원문](https://example.com)에서 확인해 😀 `RAG`!!!"))
                .isEqualTo("가현 : 결과는 원문에서 확인해 RAG !!!");
    }

    @Test
    void removesDiscordMentionsAndCustomEmoji() {
        assertThat(TtsSpeechText.sanitize(
                "<@12345> 이건 <:party:98765> 최신 논문이야"))
                .isEqualTo("이건 최신 논문이야");
    }
}
