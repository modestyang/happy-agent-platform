# Fitness Agent Legacy Tool Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove five superseded Fitness Agent Tools and migrate every current default binding to the granular Tool set.

**Architecture:** Keep the current annotation-scanned Tool registry as the source of executable capabilities, but reduce it from 23 to 18 business Tools. Keep historical published snapshots immutable while ensuring all newly initialized or published Agent/Skill configuration references only executable granular Tools.

**Tech Stack:** Java 17, Spring Boot, Maven, PostgreSQL/Flyway, JUnit 5, Mockito, React/Vitest.

## Global Constraints

- Do not change public OpenAPI contracts or add dependencies.
- Keep `fitness.plan.save` and its approval/resume behavior unchanged.
- Preserve historical published versions and Trace rows; only current/default configuration loses legacy keys.
- Fold Agent schema corrections into the pre-production `V1__agent_baseline.sql`.

---

### Task 1: Lock the executable Tool contract

**Files:**
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`

**Interfaces:**
- Consumes: `SpringToolCatalogScanner.scanRegistrations(List<?>)`
- Produces: a regression contract for the exact 18 retained Tool keys and absence of the five legacy keys.

- [x] Replace legacy profile/search tests with assertions over real scanner registrations.
- [x] Run the focused test and verify it fails because the five old Tools are still registered.

### Task 2: Remove legacy executable paths

**Files:**
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: affected Fitness tests.

**Interfaces:**
- Consumes: retained granular methods backed by `FitnessAgentQueryService`.
- Produces: exactly 18 registered Tools and no `loadForTool()` compatibility path.

- [x] Delete the five annotated methods and their private legacy DTO/mapping helpers.
- [x] Delete `FitnessApplicationService.loadForTool()` after confirming it has no remaining production caller.
- [x] Run focused Fitness tests and verify the scanner contract passes.

### Task 3: Migrate defaults and deterministic Skill preparation

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistry.java`
- Modify: related unit and integration tests.

**Interfaces:**
- Consumes: the retained 18 Tool keys.
- Produces: current Agent and Skill defaults that resolve entirely against the executable registry.

- [x] Add/adjust tests so default Agent and Meal/Plan Skill projections reject all five legacy keys.
- [x] Verify focused tests fail against the old seed/configuration.
- [x] Replace old keys with the granular Tool sets in Java defaults, V1 baseline, deterministic Skill preparation, and test fixtures.
- [x] Run focused agentbuilder/starter tests and verify they pass.

### Task 4: Verify and publish

**Files:**
- Modify: `task_plan.md`, `findings.md`, `progress.md`

**Interfaces:**
- Consumes: the integrated source tree.
- Produces: verified commit on `main` and updated `origin/main`.

- [ ] Apply Spotless and run backend focused tests plus architecture tests.
- [ ] Run frontend tests, typecheck, and formatting checks for the existing UI changes in the same release batch.
- [ ] Start the local stack and perform a page-level training-plan confirmation smoke test.
- [ ] Review `git status` and the complete `git diff`, commit with Conventional Commits, merge to `main`, reverify, and push `origin/main` without force.
