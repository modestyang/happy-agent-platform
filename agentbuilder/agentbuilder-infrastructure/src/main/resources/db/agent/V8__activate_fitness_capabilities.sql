-- V8: declare Fitness-owned executable capabilities. Runtime readiness is reconciled at startup;
-- this migration never assumes that a SQL row itself is an executable handler.

INSERT INTO agent_component_projection
    (component_type, component_key, version, display_name, description, status, tags, config, source_checksum)
VALUES
    ('SKILL', 'fitness.meal.skill', 1, '每日饮食建议',
     '组合身体指标、近 6 天训练/饮食、近 30 天偏好反馈，生成结构化三餐计划输入。',
     'DRAFT', ARRAY['fitness', 'meal'],
     '{"requiredTools":["fitness.profile.query","fitness.workout.query","fitness.meal.query","fitness.meal.feedback_context"],"runtimeReady":false}'::jsonb,
     repeat('0', 64)),
    ('SKILL', 'fitness.plan.skill', 1, '训练计划编排',
     '组合目标、时间、安全限制、历史负荷与动作库，生成结构化周训练计划输入。',
     'DRAFT', ARRAY['fitness', 'plan'],
     '{"requiredTools":["fitness.profile.query","fitness.workout.query","fitness.plan.generate"],"runtimeReady":false}'::jsonb,
     repeat('0', 64)),
    ('HOOK', 'fitness.safety', 1, '健身安全护栏',
     '模型调用前确定性拦截急性症状、受伤、极端节食与过度训练风险。',
     'DRAFT', ARRAY['fitness', 'safety'],
     '{"phase":"BEFORE_MODEL","mandatory":true,"runtimeReady":false}'::jsonb,
     repeat('0', 64))
ON CONFLICT (component_type, component_key, version) DO NOTHING;

-- Upgrade only the untouched original Fitness default. Existing non-empty bindings and every
-- other Agent remain exactly as the administrator last saved them.
UPDATE agent_drafts
SET tool_keys = '["fitness.profile.query","fitness.workout.query","fitness.meal.query","fitness.meal.feedback_context","fitness.plan.generate"]'::jsonb,
    skill_keys = '["fitness.meal.skill","fitness.plan.skill"]'::jsonb,
    hook_keys = '["fitness.safety"]'::jsonb,
    status = 'DRAFT',
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE agent_key = 'fitness.coach'
  AND current_published_version = 0
  AND tool_keys = '[]'::jsonb
  AND skill_keys = '[]'::jsonb
  AND hook_keys = '[]'::jsonb
  AND name = '瘦瘦健身教练'
  AND description = '结合用户的训练、饮食与身体记录，提供可执行的日常陪伴。';
