CREATE TABLE character_life_states (
    character_id VARCHAR(64) NOT NULL,
    world_id VARCHAR(100) NOT NULL,
    storage_version BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL,
    activity VARCHAR(80) NOT NULL,
    valence DOUBLE PRECISION NOT NULL,
    arousal DOUBLE PRECISION NOT NULL,
    social_need DOUBLE PRECISION NOT NULL,
    curiosity_need DOUBLE PRECISION NOT NULL,
    rest_need DOUBLE PRECISION NOT NULL,
    attention_target VARCHAR(200),
    current_goal VARCHAR(200),
    prospective_intention VARCHAR(500),
    last_interaction_at TIMESTAMP WITH TIME ZONE,
    last_initiative_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (character_id, world_id),
    CONSTRAINT character_life_valence_range CHECK (valence >= -1 AND valence <= 1),
    CONSTRAINT character_life_arousal_range CHECK (arousal >= 0 AND arousal <= 1),
    CONSTRAINT character_life_social_range CHECK (social_need >= 0 AND social_need <= 1),
    CONSTRAINT character_life_curiosity_range CHECK (curiosity_need >= 0 AND curiosity_need <= 1),
    CONSTRAINT character_life_rest_range CHECK (rest_need >= 0 AND rest_need <= 1)
);

CREATE INDEX idx_character_life_states_world_updated
    ON character_life_states (world_id, updated_at DESC);
