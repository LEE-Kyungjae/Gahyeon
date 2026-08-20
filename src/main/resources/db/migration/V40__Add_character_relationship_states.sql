CREATE TABLE character_relationship_states (
    character_id VARCHAR(64) NOT NULL,
    world_id VARCHAR(100) NOT NULL,
    subject_id VARCHAR(160) NOT NULL,
    storage_version BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL,
    familiarity DOUBLE PRECISION NOT NULL CHECK (familiarity >= 0 AND familiarity <= 1),
    trust DOUBLE PRECISION NOT NULL CHECK (trust >= 0 AND trust <= 1),
    affinity DOUBLE PRECISION NOT NULL CHECK (affinity >= 0 AND affinity <= 1),
    tension DOUBLE PRECISION NOT NULL CHECK (tension >= 0 AND tension <= 1),
    last_interaction_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (character_id, world_id, subject_id)
);
