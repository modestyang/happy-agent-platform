ALTER TABLE exercises
    ADD COLUMN muscle_groups JSONB,
    ADD COLUMN equipment JSONB,
    ADD COLUMN difficulty VARCHAR(24),
    ADD COLUMN movement_pattern VARCHAR(32),
    ADD COLUMN impact_level VARCHAR(16),
    ADD CONSTRAINT exercises_muscle_groups_selection_check CHECK (
        muscle_groups IS NULL OR
        (jsonb_typeof(muscle_groups) = 'array' AND jsonb_array_length(muscle_groups) > 0)
    ),
    ADD CONSTRAINT exercises_equipment_selection_check CHECK (
        equipment IS NULL OR
        (jsonb_typeof(equipment) = 'array' AND jsonb_array_length(equipment) > 0)
    ),
    ADD CONSTRAINT exercises_difficulty_selection_check CHECK (
        difficulty IS NULL OR difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')
    ),
    ADD CONSTRAINT exercises_movement_pattern_selection_check CHECK (
        movement_pattern IS NULL OR movement_pattern IN (
            'SQUAT', 'HINGE', 'LUNGE', 'HORIZONTAL_PUSH', 'VERTICAL_PUSH',
            'HORIZONTAL_PULL', 'VERTICAL_PULL', 'CORE_STABILITY', 'CORE_FLEXION',
            'ROTATION', 'LOCOMOTION', 'MOBILITY', 'ISOLATION'
        )
    ),
    ADD CONSTRAINT exercises_impact_level_selection_check CHECK (
        impact_level IS NULL OR impact_level IN ('LOW', 'MEDIUM', 'HIGH')
    );
