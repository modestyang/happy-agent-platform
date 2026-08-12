# Daily Meal Agent Task Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate daily meals through a strict, skill-selected background Agent task while suspending inactive users and preserving durable scheduling.

**Architecture:** Extend the published Agent runtime with a synchronous background-task entry that selects one published Skill and its required read Tools, with isolated trace context and no chat memory. Keep the fitness job lease and JSON validator as the persistence boundary; use existing user timestamps for activity eligibility and a bounded worker executor for model concurrency.

**Tech Stack:** Java 17, Spring Boot Scheduling, JDBC/PostgreSQL, Agent Builder RunRequest/Framework adapters, JUnit 5, AssertJ, Testcontainers.

## Global Constraints

- Do not add schema changes or a new migration; the pre-production Agent V1 seed may carry the updated Skill content.
- Do not add or upgrade dependencies.
- Preserve all unrelated uncommitted workspace changes.
- Do not commit; the repository requires an explicit commit request.
- `fitness.coach` + `fitness.meal.skill` is strict and has no generic fallback.

---

### Task 1: Strict published Skill task execution

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntimeTest.java`

**Interfaces:**
- Produces: `TaskRunResult runTask(String agentKey, UUID userId, String requiredSkillKey, String input)`.
- Guarantees: exactly one Skill, only its `requiredToolKeys`, empty Memory, isolated background conversation, published version trace.

- [ ] Add a failing integration test whose adapter captures RunRequest and asserts the only Skill is `fitness.meal.skill`, the Tool keys equal the Skill snapshot dependencies, and Memory is empty.
- [ ] Run the single test and confirm failure because `runTask` does not exist.
- [ ] Parse Skill revision and `requiredToolKeys` from the immutable snapshot, add strict selection, and share the existing adapter execution path.
- [ ] Add failing cases for missing Skill and a required Tool not bound to the Agent; map both to a task configuration exception.
- [ ] Run the runtime test class and confirm all task and chat cases pass.

### Task 2: Meal generation consumes Agent task output

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/MealPlanGenerationRuntime.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/MealPlanGenerationRuntimeTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `runTask`.
- Produces: `DailyMealPlanGenerationPort.generate(UUID userId, LocalDate date)` and existing validated `DailyMealPlanGenerationResult`.

- [ ] Replace the direct-provider request test with a failing test that captures `fitness.coach`, `fitness.meal.skill`, date task input, and parses fenced JSON output.
- [ ] Run the test and confirm failure because the runtime still builds a direct `/chat/completions` request.
- [ ] Replace direct HTTP/config credential logic with an injected task runner and keep the existing strict Chinese/schema parser.
- [ ] Change the application worker call to omit preloaded feedback so the Agent must use the bound feedback Tool.
- [ ] Run meal runtime and durable plan integration tests.

### Task 3: Skill-owned generation rules (Tool optimization deferred)

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`

**Interfaces:**
- Produces: strict Skill instructions over the existing Tool bindings; no Tool contract or Tool list change.

- [x] Expand `fitness.meal.skill` content with the query order, prompt-injection boundary, Chinese requirement, and closed JSON contract.
- [x] Preserve the existing Tool list and Tool contracts after the user explicitly deferred Tool optimization.

### Task 4: Activity eligibility, catch-up, and bounded workers

**Files:**
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/DailyMealPlanScheduler.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/DailyMealPlanGenerationWorker.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/DailyMealPlanSchedulerTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Produces: `scheduledMealPlanUserIds(Instant activeSince, LocalDate date)`, `recordUserActivity(UUID, Instant)`, startup reconciliation, and a 3-thread zero-queue executor.

- [ ] Add failing integration tests: a 15-day inactive user is skipped; a recent user with active goal is queued; bootstrap updates activity and queues only an absent current-day plan.
- [ ] Run the tests and confirm current status-only selection fails them.
- [ ] Implement eligibility using `users.updated_at`, active goal, and missing daily run; touch activity during bootstrap and enqueue missing current-day work.
- [ ] Add a failing scheduler unit test for startup reconciliation and a worker unit test proving submission uses the injected bounded executor.
- [ ] Implement startup catch-up and dedicated executor wiring without per-user timers.
- [ ] Run scheduler, worker, and fitness integration tests.

### Task 5: Review, deployment, and acceptance

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: verified build, published production Skill revision, deployed application, and acceptance evidence.

- [ ] Run targeted Maven tests for agentbuilder infrastructure, fitness service/infrastructure, and starter integration.
- [ ] Run `./mvnw spotless:apply` and inspect that formatting did not touch unrelated files; run `git diff --check`.
- [ ] Perform one scoped code review covering strict Skill selection, schema boundaries, SQL eligibility, concurrency, retries, and token-cost regressions.
- [ ] Update the persisted `fitness.meal.skill` through the workbench and republish `fitness.coach` so the immutable production snapshot contains the new Skill revision.
- [ ] Redeploy using the configured project deployment path and verify health endpoints.
- [ ] Validate with a real user: trace shows only `fitness.meal.skill` and required read Tools, output is Chinese/three meals, inactive user is skipped, return bootstrap reactivates and queues once.
