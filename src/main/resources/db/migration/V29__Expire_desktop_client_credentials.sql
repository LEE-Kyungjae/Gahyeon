ALTER TABLE desktop_client_credentials
    ADD COLUMN expires_at TIMESTAMP;

UPDATE desktop_client_credentials
SET expires_at = created_at + INTERVAL '90 days'
WHERE expires_at IS NULL;

ALTER TABLE desktop_client_credentials
    ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_desktop_client_credentials_expiry
    ON desktop_client_credentials(expires_at);
