CREATE TABLE gahyeon_world_states (
    world_id VARCHAR(100) PRIMARY KEY,
    storage_version BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL,
    current_room VARCHAR(100) NOT NULL,
    position_x DOUBLE PRECISION NOT NULL,
    position_y DOUBLE PRECISION NOT NULL,
    position_z DOUBLE PRECISION NOT NULL,
    activity VARCHAR(40) NOT NULL,
    activity_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outfit VARCHAR(100) NOT NULL,
    world_time TIMESTAMP WITH TIME ZONE NOT NULL,
    emotion VARCHAR(80) NOT NULL,
    interaction_target VARCHAR(120),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
