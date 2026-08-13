package com.gahyeonbot.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLedgerSchemaMappingTest {
    @Test
    void neutralDomainFieldsMapToTheExistingV24AgentLedger() throws Exception {
        assertThat(column(AgentSession.class, "toolScopeId")).isEqualTo("guild_id");
        assertThat(column(AgentRun.class, "toolScopeId")).isEqualTo("guild_id");
        assertThat(column(AgentSession.class, "actorId")).isEqualTo("user_id");
        assertThat(column(AgentRun.class, "actorId")).isEqualTo("user_id");
        assertThat(column(AgentRun.class, "actorDisplayName")).isEqualTo("username");
        assertThat(column(AgentSession.class, "modality")).isEqualTo("gateway");
        assertThat(column(AgentRun.class, "modality")).isEqualTo("gateway");
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/resources/db/migration/V30__Rename_agent_tool_scope_columns.sql")))
                .isFalse();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/resources/db/migration/V32__Rename_agent_actor_columns.sql")))
                .isFalse();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/resources/db/migration/V33__Rename_agent_gateway_to_modality.sql")))
                .isFalse();
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V36__Index_agent_run_supersession.sql")))
                .contains("ON agent_runs(user_id, status, created_at)")
                .doesNotContain("ON agent_runs(actor_id");
    }

    private static String column(Class<?> type, String field) throws Exception {
        return type.getDeclaredField(field).getAnnotation(Column.class).name();
    }
}
