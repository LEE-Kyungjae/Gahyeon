package com.gahyeonbot.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopClientCredentialSchemaMappingTest {
    @Test
    void credentialHashMatchesTheV27FixedWidthPhysicalColumn() throws Exception {
        Column mapping = DesktopClientCredential.class.getDeclaredField("credentialHash")
                .getAnnotation(Column.class);
        JdbcTypeCode jdbcType = DesktopClientCredential.class.getDeclaredField("credentialHash")
                .getAnnotation(JdbcTypeCode.class);

        assertThat(mapping.name()).isEqualTo("credential_hash");
        assertThat(mapping.length()).isEqualTo(64);
        assertThat(mapping.columnDefinition()).isEqualTo("CHAR(64)");
        assertThat(jdbcType.value()).isEqualTo(SqlTypes.CHAR);
        assertThat(Files.readString(Path.of(
                "src/main/resources/db/migration/V27__Add_desktop_client_credentials.sql")))
                .contains("credential_hash CHAR(64) NOT NULL UNIQUE");
    }

    @Test
    void linkTokenHashMatchesTheV26FixedWidthPhysicalColumn() throws Exception {
        Column mapping = IdentityLinkToken.class.getDeclaredField("tokenHash")
                .getAnnotation(Column.class);
        JdbcTypeCode jdbcType = IdentityLinkToken.class.getDeclaredField("tokenHash")
                .getAnnotation(JdbcTypeCode.class);

        assertThat(mapping.name()).isEqualTo("token_hash");
        assertThat(mapping.length()).isEqualTo(64);
        assertThat(mapping.columnDefinition()).isEqualTo("CHAR(64)");
        assertThat(jdbcType.value()).isEqualTo(SqlTypes.CHAR);
        assertThat(Files.readString(Path.of(
                "src/main/resources/db/migration/V26__Add_identity_link_tokens.sql")))
                .contains("token_hash CHAR(64) PRIMARY KEY");
    }
}
