CREATE TABLE identity_link_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES gahyeon_principals(id),
    target_provider VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_identity_link_tokens_expiry
    ON identity_link_tokens(expires_at);
