CREATE TABLE gahyeon_world_actions (
    action_id VARCHAR(100) PRIMARY KEY,
    world_id VARCHAR(100) NOT NULL,
    pending_world_id VARCHAR(100),
    expected_revision BIGINT NOT NULL,
    source_position_x DOUBLE PRECISION NOT NULL,
    source_position_y DOUBLE PRECISION NOT NULL,
    source_position_z DOUBLE PRECISION NOT NULL,
    target_room VARCHAR(100) NOT NULL,
    position_x DOUBLE PRECISION NOT NULL,
    position_y DOUBLE PRECISION NOT NULL,
    position_z DOUBLE PRECISION NOT NULL,
    activity VARCHAR(40) NOT NULL,
    interaction_target VARCHAR(120),
    status VARCHAR(20) NOT NULL,
    result VARCHAR(40),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    execute_after TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT gahyeon_world_actions_revision_nonnegative CHECK (expected_revision >= 0),
    CONSTRAINT gahyeon_world_actions_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'CONFLICT'))
);

CREATE UNIQUE INDEX uq_gahyeon_world_actions_pending_world
    ON gahyeon_world_actions (pending_world_id)
    WHERE pending_world_id IS NOT NULL;

CREATE INDEX idx_gahyeon_world_actions_expiry
    ON gahyeon_world_actions (status, expires_at);

CREATE INDEX idx_gahyeon_world_actions_ready
    ON gahyeon_world_actions (status, execute_after);
