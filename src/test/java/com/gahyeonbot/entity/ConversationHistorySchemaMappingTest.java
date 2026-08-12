package com.gahyeonbot.entity;

import com.gahyeonbot.repository.ConversationHistoryRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationHistorySchemaMappingTest {
    @Test
    void neutralConversationMemoryMapsToTheExistingV24UserColumn() throws Exception {
        assertThat(ConversationHistory.class.getDeclaredField("actorId")
                .getAnnotation(Column.class).name()).isEqualTo("user_id");
        assertThat(Arrays.stream(ConversationHistory.class.getAnnotation(Table.class).indexes())
                .map(Index::columnList)).contains("user_id", "user_id, created_at DESC");
        String compactionQuery = ConversationHistoryRepository.class
                .getDeclaredMethod("findUnsummarizedOldConversations", Long.class,
                        int.class, int.class)
                .getAnnotation(Query.class).value();
        assertThat(compactionQuery)
                .contains("WHERE c.user_id = :actorId", "WHERE user_id = :actorId")
                .doesNotContain("actor_id");
        assertThat(Files.exists(Path.of(
                "src/main/resources/db/migration/V34__Neutralize_conversation_and_agent_display_columns.sql")))
                .isFalse();
    }
}
