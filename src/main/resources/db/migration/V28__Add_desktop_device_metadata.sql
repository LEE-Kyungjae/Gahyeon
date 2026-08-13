ALTER TABLE desktop_client_credentials
    ADD COLUMN device_label VARCHAR(100),
    ADD COLUMN last_used_at TIMESTAMP;
