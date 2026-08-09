-- The platform is pre-release. Existing non-fitness drafts are reset to the same clean defaults
-- used by newly created Agents, rather than inheriting fitness-only prompt, memory, and handlers.
UPDATE agent_drafts
SET prompt_key = 'agent.default.prompt',
    tool_keys = '[]'::jsonb,
    skill_keys = '[]'::jsonb,
    hook_keys = '[]'::jsonb,
    memory_key = 'agent.default.memory',
    status = 'DRAFT',
    current_published_version = 0,
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE agent_key <> 'fitness.coach';
