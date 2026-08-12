# Fitness Agent DB Content Initialization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize the approved Fitness Agent system prompt and four professional Skill definitions in the current Agent database, then publish an immediately usable immutable Agent version.

**Architecture:** Treat the approved Obsidian Runtime sections as canonical content and write only the four owned Skill keys plus `fitness.coach.prompt`. Perform database changes transactionally, preserve unrelated rows, retain the daily-meal background JSON branch, validate against the runtime Tool registry, and publish through the existing workbench service.

**Tech Stack:** PostgreSQL 16, Spring Boot 3, JDBC, existing Admin Workbench HTTP API, Markdown prompt/Skill sources.

## Global Constraints

- Do not change database schemas or Flyway migration history.
- Do not modify frontend code or Fitness query implementations.
- Do not overwrite test Agent, Prompt, or Skill rows.
- Do not expose credentials or internal configuration values in command output.
- Do not commit changes unless the user explicitly requests a commit.

---

### Task 1: Capture the current configuration

**Files:**
- Read: `/Users/modest/Documents/Learning/AI-Architecture/Happy Agent Platform/健身 Agent Tool 与 Skills/02-Skill-训练计划制定.md`
- Read: `/Users/modest/Documents/Learning/AI-Architecture/Happy Agent Platform/健身 Agent Tool 与 Skills/03-Skill-健身饮食推荐.md`
- Read: `/Users/modest/Documents/Learning/AI-Architecture/Happy Agent Platform/健身 Agent Tool 与 Skills/04-Skill-训练与饮食分析诊断.md`
- Read: `/Users/modest/Documents/Learning/AI-Architecture/Happy Agent Platform/健身 Agent Tool 与 Skills/05-Skill-健身与饮食知识答疑.md`
- Read: `/Users/modest/Documents/Learning/AI-Architecture/Happy Agent Platform/健身 Agent Tool 与 Skills/06-Fitness-Agent-系统提示词.md`

**Interfaces:**
- Consumes: Existing local PostgreSQL `agent` schema.
- Produces: A before-state snapshot of the owned Prompt, four Skill keys, `fitness.coach` draft, current publication pointer, and unrelated acceptance rows.

- [ ] **Step 1: Query and retain the before-state snapshot in the active task output**

Use `SELECT` statements scoped to `fitness.coach.prompt`, the four fixed Skill keys, `fitness.coach`, `test-agent-1`, `test-food`, and `fruit-only-acceptance`.

- [ ] **Step 2: Verify all canonical Runtime sections are non-empty**

Extract the system prompt between its fenced Runtime block and each Skill body between `## Runtime Skill Content` and `## 验收用例`. Abort before writes if any extracted value is empty.

### Task 2: Transactionally upsert Prompt and Skill definitions

**Files:**
- No production file changes.

**Interfaces:**
- Consumes: Canonical Markdown content and front matter from Task 1.
- Produces: One active Prompt and four active Skill rows with complete metadata.

- [ ] **Step 1: Update `fitness.coach.prompt`**

Set display name to `瘦瘦系统提示词`, description to `Fitness Agent 的稳定角色、事实边界、能力使用、用户表达和安全约束。`, template to the canonical Runtime System Prompt, status to `ACTIVE`, and increment revision.

- [ ] **Step 2: Upsert the four Skill rows**

Use the exact keys and metadata from each document front matter. Store only `Runtime Skill Content` in `content`, set `status='ACTIVE'`, `runtime_ready=true`, and store `required_tools` as `required_tool_keys` JSON arrays. Add `fitness.meal.recommendations.query` only to `fitness.meal.skill` for the background compatibility branch.

- [ ] **Step 3: Preserve the daily-meal background contract**

For `fitness.meal.skill`, prepend a `## 每日三餐后台任务` branch that uses the new read-only Tool set, including `fitness.meal.recommendations.query`, and preserves the strict `recommendations` JSON contract; then append the canonical conversational Runtime Skill Content.

- [ ] **Step 4: Update the `fitness.coach` draft binding**

Bind `fitness.coach.prompt`, all four Skill keys, and the deduplicated union of their required Tool keys. Set the draft back to `DRAFT` and increment its revision without changing provider, model, hook, memory, temperature, or max Tool calls.

- [ ] **Step 5: Commit the database transaction**

Commit only after all four Skill rows and the draft binding pass SQL assertions. Roll back the whole transaction on any assertion failure.

### Task 3: Validate and publish

**Files:**
- No production file changes.

**Interfaces:**
- Consumes: Updated draft and the currently running Tool registry.
- Produces: A new immutable published `fitness.coach` version.

- [ ] **Step 1: Authenticate through the local Admin Workbench API**

Use the configured local admin session cookie without printing credentials or the cookie value.

- [ ] **Step 2: Call `POST /api/admin/agents/fitness.coach/validate`**

Require `valid=true` and an empty errors list. Stop before publishing if any Tool, Skill, Prompt, Provider, Model, Hook, or Memory dependency is unavailable.

- [ ] **Step 3: Call `POST /api/admin/agents/fitness.coach/publish`**

Require the returned version to be greater than the before-state published version and the publication status to be `PUBLISHED`.

### Task 4: Verify persisted and published state

**Files:**
- No production file changes.

**Interfaces:**
- Consumes: New published Agent snapshot.
- Produces: Evidence that initialization is active and unrelated data is unchanged.

- [ ] **Step 1: Verify database metadata**

Assert all four Skill keys have non-blank description, `when_to_use`, `when_not_to_use`, content, active status, runtime readiness, and the expected Tool count.

- [ ] **Step 2: Verify the immutable publication snapshot**

Assert the newest `agent_versions.configuration` contains `fitness.coach.prompt`, all four Skill keys, the expected Tool bindings, and the current Skill revisions.

- [ ] **Step 3: Verify compatibility and isolation**

Assert `fitness.meal.skill.content` still contains `DAILY_MEAL_PLAN` and the strict `recommendations` contract. Compare unrelated acceptance rows with the before-state snapshot and require no changes.

- [ ] **Step 4: Report exact published version and remaining limitations**

Report the new version number, configured resources, verification evidence, and note that no schema, frontend, or query-flow change was made.
