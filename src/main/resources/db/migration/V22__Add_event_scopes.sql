ALTER TABLE gahyeon_events ADD COLUMN scope_type VARCHAR(30);
ALTER TABLE gahyeon_events ADD COLUMN scope_id VARCHAR(200);

UPDATE gahyeon_events
SET scope_type = 'SESSION', scope_id = session_id;

ALTER TABLE gahyeon_events ALTER COLUMN scope_type SET NOT NULL;
ALTER TABLE gahyeon_events ALTER COLUMN scope_id SET NOT NULL;
ALTER TABLE gahyeon_events ALTER COLUMN session_id DROP NOT NULL;

CREATE INDEX idx_gahyeon_events_scope_sequence
    ON gahyeon_events(scope_type, scope_id, id);
