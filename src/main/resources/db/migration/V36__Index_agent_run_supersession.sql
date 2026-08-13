-- Supports the same-actor supersession scan used when a replacement cognition run starts.
-- created_at is intentionally part of the key: strict earlier-than ordering prevents an
-- idempotent retry of an old request from cancelling a newer run.
CREATE INDEX idx_agent_runs_actor_status_created
    ON agent_runs(user_id, status, created_at);
