CREATE TABLE desktop_client_credentials (
    id VARCHAR(36) PRIMARY KEY,
    credential_hash CHAR(64) NOT NULL UNIQUE,
    principal_id BIGINT NOT NULL REFERENCES gahyeon_principals(id),
    installation_id VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL
);

CREATE INDEX idx_desktop_client_credentials_principal
    ON desktop_client_credentials(principal_id);
