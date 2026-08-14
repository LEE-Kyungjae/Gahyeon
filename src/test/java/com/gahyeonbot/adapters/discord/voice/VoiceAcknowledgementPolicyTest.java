package com.gahyeonbot.adapters.discord.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceAcknowledgementPolicyTest {
    @Test
    void rotatesMessagesAndAppliesSessionCooldown() {
        var policy = new VoiceAcknowledgementPolicy();
        var messages = List.of("첫 번째", "두 번째", "세 번째");

        var first = policy.tryAcquire(1_000, 30_000, "", messages).orElseThrow();
        assertThat(first.message()).isEqualTo("첫 번째");
        assertThat(policy.tryAcquire(1_001, 30_000, "", messages)).isEmpty();
        first.close();
        assertThat(policy.tryAcquire(30_999, 30_000, "", messages)).isEmpty();

        var second = policy.tryAcquire(31_000, 30_000, "", messages).orElseThrow();
        assertThat(second.message()).isEqualTo("두 번째");
        second.close();
        var third = policy.tryAcquire(61_000, 30_000, "", messages).orElseThrow();
        assertThat(third.message()).isEqualTo("세 번째");
        third.close();
    }

    @Test
    void legacyOverrideStaysSupportedWithoutMixingMessages() {
        var policy = new VoiceAcknowledgementPolicy();

        var lease = policy.tryAcquire(0, 0, "  기존 문구  ", List.of("새 문구"))
                .orElseThrow();

        assertThat(lease.message()).isEqualTo("기존 문구");
        lease.close();
    }

    @Test
    void emptyAndDuplicateMessagesAreHandledFailClosed() {
        var policy = new VoiceAcknowledgementPolicy();

        assertThat(policy.tryAcquire(0, 0, " ", List.of(" "))).isEmpty();
        var first = policy.tryAcquire(0, 0, "", List.of("하나", " 하나 "))
                .orElseThrow();
        first.close();
        var second = policy.tryAcquire(1, 0, "", List.of("하나", " 하나 "))
                .orElseThrow();
        assertThat(second.message()).isEqualTo("하나");
        second.close();
    }

    @Test
    void leaseCloseIsIdempotent() {
        var policy = new VoiceAcknowledgementPolicy();
        var first = policy.tryAcquire(0, 0, "", List.of("하나", "둘")).orElseThrow();

        first.close();
        first.close();

        var second = policy.tryAcquire(1, 0, "", List.of("하나", "둘")).orElseThrow();
        assertThat(second.message()).isEqualTo("둘");
        second.close();
    }
}
