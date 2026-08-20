CREATE TABLE character_memories (
    id BIGSERIAL PRIMARY KEY,
    character_id VARCHAR(64) NOT NULL,
    world_id VARCHAR(100) NOT NULL,
    subject_id VARCHAR(160),
    kind VARCHAR(32) NOT NULL,
    memory_key VARCHAR(160),
    content VARCHAR(2000) NOT NULL,
    importance DOUBLE PRECISION NOT NULL CHECK (importance >= 0 AND importance <= 1),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    emotional_weight DOUBLE PRECISION NOT NULL CHECK (emotional_weight >= -1 AND emotional_weight <= 1),
    expires_at TIMESTAMP WITH TIME ZONE,
    last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    superseded_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_character_memories_namespace_created
    ON character_memories (character_id, world_id, subject_id, created_at DESC);
