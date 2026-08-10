CREATE TABLE gahyeon_principals (
    id BIGINT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE external_identities (
    id VARCHAR(36) PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES gahyeon_principals(id),
    provider VARCHAR(30) NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_external_identity_provider_id UNIQUE(provider, external_id)
);

CREATE INDEX idx_external_identities_principal
    ON external_identities(principal_id);
