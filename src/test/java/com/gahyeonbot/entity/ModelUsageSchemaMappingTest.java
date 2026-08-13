package com.gahyeonbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageSchemaMappingTest {
    @Test
    void neutralUsageLedgerMapsToTheExistingV24OpenAiUsageTable() throws Exception {
        assertThat(column("requestId")).isEqualTo("interaction_id");
        assertThat(column("actorId")).isEqualTo("user_id");
        assertThat(column("actorDisplayName")).isEqualTo("username");
        assertThat(column("toolScopeId")).isEqualTo("guild_id");
        assertThat(ModelUsage.class.getAnnotation(Table.class).name()).isEqualTo("openai_usage");
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/resources/db/migration/V31__Rename_openai_usage_core_columns.sql")))
                .isFalse();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/resources/db/migration/V35__Rename_openai_usage_to_model_usage.sql")))
                .isFalse();
        String identityMerge = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/gahyeonbot/adapters/identity/JpaIdentityLinkAdapter.java"));
        assertThat(identityMerge)
                .contains("new String[]{\"conversation_history\"}")
                .contains("new String[]{\"agent_sessions\", \"agent_runs\"}")
                .contains("UPDATE openai_usage SET user_id = :target WHERE user_id = :source")
                .doesNotContain("UPDATE model_usage", "SET actor_id = :target");
    }

    private static String column(String field) throws Exception {
        return ModelUsage.class.getDeclaredField(field).getAnnotation(Column.class).name();
    }
}
