package com.gahyeonbot.core.life;

import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterMemoryRecallPolicyTest {
    @Test
    void keepsImportantDurableMemoryAboveRecentLowValueChatterAndDropsExpiredWorkingMemory() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        var important = memory(CharacterMemoryKind.SEMANTIC, "사용자는 공포영화를 싫어한다", 0.95, 0.95,
                now.minus(Duration.ofDays(30)), null);
        var chatter = memory(CharacterMemoryKind.WORKING, "응", 0.1, 1,
                now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1)));
        var expired = memory(CharacterMemoryKind.WORKING, "만료된 최근 문장", 1, 1,
                now.minus(Duration.ofMinutes(1)), now.minusSeconds(1));

        assertThat(new CharacterMemoryRecallPolicy().rank(List.of(chatter, expired, important), now, 3))
                .extracting(CharacterMemory::content)
                .containsExactly("사용자는 공포영화를 싫어한다", "응");
    }

    private static CharacterMemory memory(CharacterMemoryKind kind, String content, double importance,
            double confidence, Instant createdAt, Instant expiresAt) {
        return new CharacterMemory(0, new CharacterId("gahyeon"), new WorldId("home"), "actor:42",
                kind, content, importance, confidence, 0, expiresAt, createdAt, createdAt);
    }
}
