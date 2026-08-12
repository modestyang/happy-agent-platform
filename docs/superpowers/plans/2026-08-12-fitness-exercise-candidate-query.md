# Fitness Exercise Candidate Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 59 条动作的结构化选择标签，并新增按用户经验、器械和冲击限制执行 SQL 过滤的有界候选 Tool，使训练计划正常只向模型提供最多 32 条候选。

**Architecture:** `dataset.json` 继续作为动作业务数据的唯一事实来源，Fitness V16 只增加存储列和约束，Seed 负责幂等写入标签。`FitnessAgentQueryService` 从可信用户上下文读取档案、规范化器械和限制，再由 `JdbcFitnessAgentReadStore` 用 SQL 硬过滤并按“目标部位 × 动作模式”均衡排序；`FitnessTools` 只发布紧凑候选合同。计划 Skill 使用候选 Tool 代替分批目录搜索，最终动作详情仍一次批量读取。

**Tech Stack:** Java 17、Spring JDBC、PostgreSQL 16 JSONB/窗口函数、Flyway、Node.js ESM/`node:test`、JUnit 5、Mockito、Testcontainers、Agent Tool scanner。

## Global Constraints

- `scripts/seed-exercises/dataset.json` 是动作业务数据唯一事实来源；不得把 59 条业务标签写入 Flyway。
- `/Users/modest/Documents/Learning/AI-Architecture/健身动作库数据初始化方案.md` 必须与最终 Schema、Seed、标签和候选查询行为保持一致。
- 新建 `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V16__exercise_selection_metadata.sql`，不得修改既有 Fitness Migration。
- 不修改 `frontend/**`、公开 OpenAPI 或 Controller；现有公开合同已经声明肌群、器械和难度，本任务不新增公开字段。
- `agentbuilder/**` 不得依赖 `application/**`；Agent 只能通过 Spring Bean `FitnessTools` 访问 Fitness 数据。
- Tool 输入不得包含 `userId`；身份只来自 `ToolExecutionContext.userId`。
- `difficulty`、`movementPattern`、`impactLevel` 向模型直接返回英文枚举码；目标部位、肌群和器械返回规范化中文标签，不逐行附双语字段。
- 候选第一页固定最多 32 条，第二页固定最多 12 条；候选 Tool `defaultMaxCallsPerRun=2`，发布后的 `fitness.coach.maxToolCalls=16`。
- 第二页不得放宽经验、器械或冲击硬限制；无候选和覆盖不足必须作为成功的结构化空结果/缺口返回。
- 保留 `fitness.exercise.catalog.search` 供通用动作问答，但 `fitness.plan.skill` 不再用它遍历动作库。
- 不新增依赖；不 commit、不 push；保留工作区内所有既有未提交改动。

---

### Task 1: Complete and validate the exercise selection dataset

**Files:**

- Modify: `scripts/seed-exercises/dataset.json`
- Modify: `scripts/seed-exercises/seed.mjs`
- Create: `scripts/seed-exercises/seed.test.mjs`
- Modify: `/Users/modest/Documents/Learning/AI-Architecture/健身动作库数据初始化方案.md`

**Interfaces:**

- Consumes: Existing 55 `exercises` entries and 4 stable-ID `demoUpgrades` entries.
- Produces: `validateDataset(dataset) -> { exerciseCount, demoUpgradeCount, totalCount }` and `buildSql(dataset) -> string`; every row has non-empty `muscleGroups`/`equipment` plus valid `difficulty`/`movementPattern`/`impactLevel`.

- [ ] **Step 1: Add a failing Node test for completeness and generated SQL**

Create `seed.test.mjs` with the built-in test runner:

```js
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { buildSql, validateDataset } from './seed.mjs';

const dataset = JSON.parse(
  await readFile(new URL('./dataset.json', import.meta.url), 'utf8'),
);

test('all 59 exercises have complete selection metadata', () => {
  assert.deepEqual(validateDataset(dataset), {
    exerciseCount: 55,
    demoUpgradeCount: 4,
    totalCount: 59,
  });
});

test('seed SQL writes metadata for inserts and demo upgrades', () => {
  const sql = buildSql(dataset);
  assert.match(sql, /muscle_groups, equipment, difficulty, movement_pattern, impact_level/);
  assert.match(sql, /muscle_groups = EXCLUDED\.muscle_groups/);
  assert.match(sql, /movement_pattern = 'SQUAT'/);
  assert.match(sql, /60000000-0000-0000-0000-000000000004/);
});
```

- [ ] **Step 2: Run the Node test and confirm it fails before implementation**

Run: `node --test scripts/seed-exercises/seed.test.mjs`

Expected: FAIL because `seed.mjs` does not export `validateDataset`/`buildSql` and Dataset entries do not yet contain both new tags.

- [ ] **Step 3: Add exact movement and impact labels to the 55 ordinary actions**

Use this reviewed mapping; do not infer classifications at runtime:

| Slug | movementPattern | impactLevel |
| --- | --- | --- |
| `jump-squat` | `SQUAT` | `HIGH` |
| `walking-lunge` | `LUNGE` | `LOW` |
| `single-leg-glute-bridge` | `HINGE` | `LOW` |
| `glute-kickback` | `ISOLATION` | `LOW` |
| `goblet-squat` | `SQUAT` | `LOW` |
| `dumbbell-lunge` | `LUNGE` | `LOW` |
| `db-romanian-deadlift` | `HINGE` | `LOW` |
| `barbell-squat` | `SQUAT` | `LOW` |
| `barbell-deadlift` | `HINGE` | `LOW` |
| `barbell-hip-thrust` | `HINGE` | `LOW` |
| `db-calf-raise` | `ISOLATION` | `LOW` |
| `plank` | `CORE_STABILITY` | `LOW` |
| `side-plank` | `CORE_STABILITY` | `LOW` |
| `crunch` | `CORE_FLEXION` | `LOW` |
| `reverse-crunch` | `CORE_FLEXION` | `LOW` |
| `lying-leg-raise` | `CORE_FLEXION` | `LOW` |
| `russian-twist` | `ROTATION` | `LOW` |
| `dead-bug` | `CORE_STABILITY` | `LOW` |
| `flutter-kicks` | `CORE_FLEXION` | `LOW` |
| `superman` | `CORE_STABILITY` | `LOW` |
| `cat-cow` | `MOBILITY` | `LOW` |
| `push-up` | `HORIZONTAL_PUSH` | `LOW` |
| `incline-push-up` | `HORIZONTAL_PUSH` | `LOW` |
| `decline-push-up` | `HORIZONTAL_PUSH` | `LOW` |
| `wide-push-up` | `HORIZONTAL_PUSH` | `LOW` |
| `db-bench-press` | `HORIZONTAL_PUSH` | `LOW` |
| `db-fly` | `ISOLATION` | `LOW` |
| `barbell-bench-press` | `HORIZONTAL_PUSH` | `LOW` |
| `pull-up` | `VERTICAL_PULL` | `LOW` |
| `band-assisted-pull-up` | `VERTICAL_PULL` | `LOW` |
| `inverted-row` | `HORIZONTAL_PULL` | `LOW` |
| `one-arm-db-row` | `HORIZONTAL_PULL` | `LOW` |
| `db-bent-over-row` | `HORIZONTAL_PULL` | `LOW` |
| `barbell-bent-over-row` | `HORIZONTAL_PULL` | `LOW` |
| `back-extension` | `HINGE` | `LOW` |
| `db-shoulder-press` | `VERTICAL_PUSH` | `LOW` |
| `arnold-press` | `VERTICAL_PUSH` | `LOW` |
| `lateral-raise` | `ISOLATION` | `LOW` |
| `front-raise` | `ISOLATION` | `LOW` |
| `rear-delt-fly` | `ISOLATION` | `LOW` |
| `band-pull-apart` | `HORIZONTAL_PULL` | `LOW` |
| `db-bicep-curl` | `ISOLATION` | `LOW` |
| `hammer-curl` | `ISOLATION` | `LOW` |
| `barbell-curl` | `ISOLATION` | `LOW` |
| `bench-dip` | `VERTICAL_PUSH` | `LOW` |
| `close-grip-push-up` | `HORIZONTAL_PUSH` | `LOW` |
| `overhead-tricep-extension` | `ISOLATION` | `LOW` |
| `jumping-jack` | `LOCOMOTION` | `MEDIUM` |
| `jump-rope` | `LOCOMOTION` | `MEDIUM` |
| `tuck-jump` | `LOCOMOTION` | `HIGH` |
| `split-jump` | `LUNGE` | `HIGH` |
| `inchworm` | `LOCOMOTION` | `LOW` |
| `kettlebell-swing` | `HINGE` | `MEDIUM` |
| `fire-hydrant` | `ISOLATION` | `LOW` |
| `clamshell` | `ISOLATION` | `LOW` |

During the same edit, correct `inverted-row.equipment` from `徒手` to `单杠`; the action needs a stable horizontal support and must not be recommended as equipment-free.

- [ ] **Step 4: Complete the four Demo rows with reviewed labels**

Add these exact fields to `demoUpgrades`:

```json
{"slug":"squat","muscleGroups":["股四头肌","臀大肌","腘绳肌"],"equipment":["徒手"],"difficulty":"BEGINNER","movementPattern":"SQUAT","impactLevel":"LOW"}
{"slug":"knee-push-up","muscleGroups":["胸大肌","肱三头肌","三角肌前束"],"equipment":["徒手"],"difficulty":"BEGINNER","movementPattern":"HORIZONTAL_PUSH","impactLevel":"LOW"}
{"slug":"mountain-climbers","muscleGroups":["核心肌群","髋屈肌群","股四头肌"],"equipment":["徒手"],"difficulty":"INTERMEDIATE","movementPattern":"LOCOMOTION","impactLevel":"MEDIUM"}
{"slug":"glute-bridge","muscleGroups":["臀大肌","腘绳肌","核心肌群"],"equipment":["徒手"],"difficulty":"BEGINNER","movementPattern":"HINGE","impactLevel":"LOW"}
```

Preserve each existing `uuid`、`sourceId` and `targetArea` in the actual JSON objects.

- [ ] **Step 5: Refactor `seed.mjs` into testable validation and SQL generation**

Export `validateDataset(value)`, which returns the three exact counts asserted in Step 1 or throws, and `buildSql(value)`, which returns one `BEGIN`/`COMMIT` transaction. Guard CLI execution with an `isMain` check so the Node test can import both functions without starting Docker.

Validation must enforce:

```js
const DIFFICULTIES = new Set(['BEGINNER', 'INTERMEDIATE', 'ADVANCED']);
const MOVEMENT_PATTERNS = new Set([
  'SQUAT', 'HINGE', 'LUNGE', 'HORIZONTAL_PUSH', 'VERTICAL_PUSH',
  'HORIZONTAL_PULL', 'VERTICAL_PULL', 'CORE_STABILITY', 'CORE_FLEXION',
  'ROTATION', 'LOCOMOTION', 'MOBILITY', 'ISOLATION',
]);
const IMPACT_LEVELS = new Set(['LOW', 'MEDIUM', 'HIGH']);
```

Reject non-arrays, empty arrays, blank strings, invalid enum codes, duplicate Slugs, duplicate names and duplicate Demo UUIDs. Call `validateDataset(dataset)` before either `--sql` output or Docker execution.

Extend INSERT/UPSERT and Demo UPDATE SQL with:

```sql
muscle_groups, equipment, difficulty, movement_pattern, impact_level
```

For ordinary actions update all five columns from `EXCLUDED`; for Demo rows set all five explicitly together with the existing target/image updates.

- [ ] **Step 6: Run Dataset validation and inspect the generated SQL**

Run:

```bash
node --test scripts/seed-exercises/seed.test.mjs
node scripts/seed-exercises/seed.mjs --sql | rg -n "muscle_groups|movement_pattern|impact_level|60000000-0000-0000-0000-000000000004"
```

Expected: 2 Node tests PASS; generated SQL contains all five columns for both insert and Demo paths and does not print credentials.

- [x] **Step 7: Synchronize the action-library initialization document**

The external initialization document now records the five selection fields, closed enums, 59-row review rule, V16/Seed responsibility split, equipment normalization, SQL bucket retrieval, compact model-facing code policy, 32+12 candidate bounds, Tool/global limits, validation SQL and migration → Seed → Agent publication order. Recheck these statements after implementation; if a verified interface changes, update the same section before reporting completion.

---

### Task 2: Add append-only Fitness storage for selection metadata

**Files:**

- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V16__exercise_selection_metadata.sql`
- Modify: `starter/src/test/java/happy/jayden/yang/config/DualSchemaIntegrationTest.java`

**Interfaces:**

- Consumes: Existing `fitness.exercises` table from V2.
- Produces: Five nullable, constrained selection columns that support migration-before-Seed deployment without embedding business rows.

- [ ] **Step 1: Extend the migration integration test first**

Change the expected Fitness migration history count from `13L` to `16L`, accounting for current V14/V15 and new V16. Add assertions that `fitness.exercises` has exactly five new columns and named Check Constraints:

```java
assertThat(fitnessJdbc.queryForObject(
    "select count(*) from information_schema.columns where table_schema='fitness' "
        + "and table_name='exercises' and column_name in "
        + "('muscle_groups','equipment','difficulty','movement_pattern','impact_level')",
    Long.class)).isEqualTo(5L);
```

Also insert one row with all five values `NULL` to prove the migration can precede Seed, and assert invalid `difficulty='EXPERT'` plus empty `muscle_groups='[]'` are rejected.

- [ ] **Step 2: Run the focused migration test and confirm it fails**

Run: `./mvnw -pl starter -am -Dtest=DualSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because V16 and its columns do not exist. Docker is required; if Docker is unavailable, record the environment blocker and continue with compile/static verification only.

- [ ] **Step 3: Create V16 with schema-only constraints**

Use this migration shape:

```sql
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
```

Do not add UPDATE statements or defaults that make old rows look labeled.

- [ ] **Step 4: Run the migration test again**

Run: `./mvnw -pl starter -am -Dtest=DualSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with 16 independent Fitness migrations and no cross-schema foreign keys.

---

### Task 3: Define candidate selection types and deterministic profile rules

**Files:**

- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentQueryService.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentQueryServiceTest.java`

**Interfaces:**

- Consumes: `FitnessAgentReadStore.findUserProfile(userId)` plus requested `focusAreas`/`maxImpactLevel`/`page`.
- Produces: `ExerciseCandidateFilter`, `ExerciseCandidatePage`, and `ExerciseCandidatesView`; normalization is deterministic and hard limits cannot be relaxed by Tool input.

- [ ] **Step 1: Add failing service tests for normalization and hard limits**

Add tests that stub a profile and capture the filter passed to the store:

```java
when(store.findUserProfile(USER_ID)).thenReturn(Optional.of(new UserProfileFact(
    "小花", "NOT_DISCLOSED", 1996, null, "BEGINNER", List.of("HOME"),
    List.of("一对哑铃，瑜伽垫"), List.of(1, 3, 5), 30,
    List.of("膝盖不舒服，避免跳跃"), "WARM_DIRECT", List.of())));

var result = queries.exerciseCandidates(
    USER_ID, List.of("臀腿"), ExerciseImpactLevel.HIGH, 1);

assertEquals(ExerciseDifficulty.BEGINNER, captured.maxDifficulty());
assertEquals(ExerciseImpactLevel.LOW, captured.maxImpactLevel());
assertEquals(Set.of("徒手", "哑铃", "瑜伽垫"), captured.availableEquipment());
assertEquals(0, captured.offset());
assertEquals(32, captured.limit());
```

Add separate assertions for:

- missing experience defaults to `BEGINNER` and adds a limitation;
- `INTERMEDIATE` and `ADVANCED` map to the corresponding maximum difficulty;
- aliases `可调哑铃`/`一副哑铃` → `哑铃`, `阻力带` → `弹力带`, `健身凳`/`卧推凳` → `训练凳`, `引体杆` → `单杠`;
- unknown values are returned unchanged in `unrecognizedEquipment` but never added to the SQL equipment set;
- `maxImpactLevel=HIGH` cannot override a profile-derived `LOW` limit;
- page 1 maps to offset 0/limit 32 and page 2 maps to offset 32/limit 12;
- null page defaults to 1; page 0/3, more than three focus areas, unknown target areas and null list elements are rejected.
- an empty `ExerciseCandidatePage` returns `dataStatus="EMPTY"`, `eligibleCount=0`, a focus-area `NO_ELIGIBLE` gap and no exception.

- [ ] **Step 2: Run the service tests and confirm type/method failures**

Run: `./mvnw -pl starter -am -Dtest=FitnessAgentQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because candidate DTOs, Port method and service method do not exist.

- [ ] **Step 3: Add the exact domain types**

Add to `FitnessAgentDtos`:

```java
public enum ExerciseDifficulty { BEGINNER, INTERMEDIATE, ADVANCED }
public enum ExerciseMovementPattern {
  SQUAT, HINGE, LUNGE, HORIZONTAL_PUSH, VERTICAL_PUSH,
  HORIZONTAL_PULL, VERTICAL_PULL, CORE_STABILITY, CORE_FLEXION,
  ROTATION, LOCOMOTION, MOBILITY, ISOLATION
}
public enum ExerciseImpactLevel { LOW, MEDIUM, HIGH }

public record ExerciseCandidateFact(
    UUID exerciseId, String name, String targetArea, List<String> muscleGroups,
    List<String> equipment, ExerciseDifficulty difficulty,
    ExerciseMovementPattern movementPattern, ExerciseImpactLevel impactLevel,
    int referenceSets, int referenceSeconds) {}

public record ExerciseCandidateFilter(
    java.util.Set<String> availableEquipment, ExerciseDifficulty maxDifficulty,
    ExerciseImpactLevel maxImpactLevel, List<String> focusAreas, int offset, int limit) {}

public record ExerciseCoverageFact(
    String targetArea, ExerciseMovementPattern movementPattern, long eligibleCount) {}

public record ExerciseCandidatePage(
    List<ExerciseCandidateFact> records, long eligibleCount, long unlabeledCount,
    List<ExerciseCoverageFact> eligibleCoverage) {}

public record ExerciseAppliedFilters(
    ExerciseDifficulty maxDifficulty, ExerciseImpactLevel maxImpactLevel,
    List<String> availableEquipment) {}

public record ExerciseCoverage(
    String targetArea, ExerciseMovementPattern movementPattern,
    long eligibleCount, long returnedCount) {}

public record ExerciseCandidatesView(
    QueryMetadata metadata, int page, List<ExerciseCandidateFact> candidates,
    ExerciseAppliedFilters appliedFilters, List<String> unrecognizedEquipment,
    long unlabeledCount, List<ExerciseCoverage> coverage,
    List<String> coverageGaps, boolean hasMore) {}
```

Add this Port method:

```java
FitnessAgentDtos.ExerciseCandidatePage findExerciseCandidates(
    FitnessAgentDtos.ExerciseCandidateFilter filter);
```

- [ ] **Step 4: Implement deterministic normalization in the query service**

Add:

```java
public ExerciseCandidatesView exerciseCandidates(
    UUID userId,
    List<String> focusAreas,
    ExerciseImpactLevel requestedMaxImpactLevel,
    Integer page)
```

Use a fixed canonical target set:

```java
Set.of("臀腿", "核心", "胸部", "背部", "肩部", "手臂", "心肺")
```

Split each equipment entry on `,，、/;；`, trim values, and apply an explicit alias map. Always add `徒手` to the recognized SQL set. Recognize `瑜伽垫` even though no current action requires it, so a common valid profile value is not reported as unknown.

Derive profile impact limit `LOW` only when a restriction contains one of:

```java
List.of("避免跳跃", "不要跳跃", "禁止跳跃", "不做跳跃", "避免高冲击", "低冲击")
```

The effective impact rank is `min(profileLimit, requestedLimit)`; null request means `HIGH`. Do not infer hard limits from `膝盖不舒服` or `腰不好` alone.

Compute `coverage` by combining eligible bucket totals from the store with returned-page bucket counts. A gap is either `targetArea:<area>:NO_ELIGIBLE` for a requested focus with no eligible rows, or `bucket:<area>/<pattern>:NOT_RETURNED` for an eligible bucket absent from the current page. `hasMore` is `eligibleCount > offset + records.size()`.

- [ ] **Step 5: Run the service tests**

Run: `./mvnw -pl starter -am -Dtest=FitnessAgentQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; the service never sends unknown equipment or a looser impact/difficulty limit to SQL.

---

### Task 4: Implement bounded SQL filtering and balanced retrieval

**Files:**

- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessAgentReadStore.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/JdbcFitnessAgentReadStoreTest.java`

**Interfaces:**

- Consumes: `ExerciseCandidateFilter` from Task 3.
- Produces: A stable `ExerciseCandidatePage` with complete eligible coverage and a bounded page; incomplete metadata rows are excluded and counted.

- [ ] **Step 1: Add SQL integration fixtures and failing assertions**

Extend the Testcontainers fixture with labeled actions covering:

- beginner bodyweight low-impact squat;
- beginner dumbbell low-impact hinge;
- beginner barbell low-impact hinge;
- intermediate bodyweight low-impact push;
- beginner bodyweight medium-impact jumping jack;
- a row with all selection columns null.

Call `findExerciseCandidates` for:

```java
new ExerciseCandidateFilter(
    Set.of("徒手", "哑铃"), ExerciseDifficulty.BEGINNER,
    ExerciseImpactLevel.LOW, List.of("臀腿"), 0, 32)
```

Assert bodyweight/dumbbell rows are present, barbell/intermediate/medium-impact/unlabeled rows are absent, `unlabeledCount` includes the null row, and coverage totals describe the full eligible set.

Insert at least 34 eligible rows across repeated and distinct `(target_area, movement_pattern)` buckets. Assert:

- page 1 has 32 rows;
- page 2 with offset 32/limit 12 has only remaining rows and no duplicate IDs;
- each bucket's first row appears before any bucket's second row;
- repeated calls return identical ID order.

- [ ] **Step 2: Run the focused JDBC test and confirm it fails**

Run: `./mvnw -pl starter -am -Dtest=JdbcFitnessAgentReadStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `findExerciseCandidates` is not implemented.

- [ ] **Step 3: Implement the labeled/eligible/bucketed/ranked SQL**

Use the existing Fitness `JdbcTemplate` and unqualified table names. The query must preserve this shape:

```sql
WITH labeled AS (
    SELECT exercise_id, name, target_area, sets, seconds,
           muscle_groups, equipment, difficulty, movement_pattern, impact_level
    FROM exercises
    WHERE muscle_groups IS NOT NULL
      AND equipment IS NOT NULL
      AND difficulty IS NOT NULL
      AND movement_pattern IS NOT NULL
      AND impact_level IS NOT NULL
), eligible_base AS (
    SELECT *,
           CASE WHEN jsonb_build_array(target_area) <@ ?::jsonb THEN 0 ELSE 1 END AS focus_priority
    FROM labeled
    WHERE (CASE difficulty WHEN 'BEGINNER' THEN 1 WHEN 'INTERMEDIATE' THEN 2 ELSE 3 END) <= ?
      AND (CASE impact_level WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END) <= ?
      AND (equipment - '徒手') <@ ?::jsonb
), bucketed AS (
    SELECT *, ROW_NUMBER() OVER (
        PARTITION BY target_area, movement_pattern ORDER BY name, exercise_id
    ) AS bucket_rank
    FROM eligible_base
), ranked AS (
    SELECT *, ROW_NUMBER() OVER (
        ORDER BY bucket_rank, focus_priority, target_area, movement_pattern, name, exercise_id
    ) AS candidate_rank
    FROM bucketed
)
SELECT exercise_id, name, target_area, sets, seconds,
       muscle_groups, equipment, difficulty, movement_pattern, impact_level
FROM ranked
WHERE candidate_rank > ? AND candidate_rank <= ?
ORDER BY candidate_rank
```

Pass `focusAreas` and recognized equipment as JSON arrays. Run an eligible-coverage query using the same `labeled` and `eligible_base` predicates grouped by `target_area,movement_pattern`; sum its counts for `eligibleCount`. Query `unlabeledCount` separately with the exact inverse of the five non-null checks.

Map JSON arrays with the existing `strings(...)` helper and enum columns with `valueOf`; database constraint violations or invalid persisted enum codes must propagate as real data errors.

- [ ] **Step 4: Run JDBC tests**

Run: `./mvnw -pl starter -am -Dtest=JdbcFitnessAgentReadStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with deterministic, non-overlapping pages and correct hard filtering.

---

### Task 5: Publish the compact candidate Tool contract

**Files:**

- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessAgentToolDtos.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`

**Interfaces:**

- Consumes: `FitnessAgentQueryService.exerciseCandidates(...)` and trusted `ToolExecutionContext`.
- Produces: Tool key `fitness.exercise.candidates.query`, runtime name `fitness_exercise_candidates_query`, READ_ONLY/LOW/`fitness.read`, max two calls per run, with no detailed action instructions in output.

- [ ] **Step 1: Add failing scanner and invocation tests**

Extend `expectedReadTools` with `fitness.exercise.candidates.query`. Assert its descriptor has:

```java
assertEquals(2, descriptor.defaultMaxCallsPerRun());
assertFalse(descriptor.inputSchema().document().toString().contains("userId"));
assertFalse(descriptor.outputSchema().document().toString().contains("steps"));
assertFalse(descriptor.outputSchema().document().toString().contains("commonErrors"));
assertFalse(descriptor.outputSchema().document().toString().contains("imageUrls"));
```

Invoke the scanned handler with `focusAreas=["臀腿"]`, `maxImpactLevel="LOW"`, `page=1`; verify the service receives the context user UUID and Tool JSON includes `movementPattern="SQUAT"`, `impactLevel="LOW"`, Chinese muscle/equipment labels, but no duplicate Chinese enum-label properties.

- [ ] **Step 2: Run Tool tests and confirm the missing Tool failure**

Run: `./mvnw -pl starter -am -Dtest=FitnessToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the Tool and candidate output DTOs do not exist.

- [ ] **Step 3: Add compact Tool DTOs and mapper**

Add records equivalent to:

```java
public record ExerciseCandidate(
    UUID exerciseId, String name, String targetArea,
    List<String> muscleGroups, List<String> equipment,
    String difficulty, String movementPattern, String impactLevel,
    int referenceSets, int referenceSeconds) {}

public record ExerciseCandidateRequest(
    List<String> focusAreas, String maxImpactLevel, Integer page) {}

public record ExerciseCandidatesResult(
    Metadata metadata, int page, List<ExerciseCandidate> candidates,
    ExerciseAppliedFilters appliedFilters, List<String> unrecognizedEquipment,
    long unlabeledCount, List<ExerciseCoverage> coverage,
    List<String> coverageGaps, boolean hasMore) {}
```

Use `@AgentToolParam` descriptions to declare page bounds, maximum three focus areas and the English enum values. Map internal enums with `.name()`; do not add `difficultyLabel`/`movementPatternLabel`/`impactLevelLabel`.

- [ ] **Step 4: Add the Tool method with a single dictionary in its description**

Use:

```java
@AgentTool(
    key = "fitness.exercise.candidates.query",
    version = 1,
    runtimeName = "fitness_exercise_candidates_query",
    displayName = "筛选训练计划候选动作",
    description = "按当前用户经验、可用器械和冲击限制筛选并均衡返回候选动作。"
        + " difficulty: BEGINNER初级/INTERMEDIATE中级/ADVANCED高级；"
        + " impactLevel: LOW低/MEDIUM中/HIGH高；movementPattern 使用稳定英文动作模式码。",
    whenToUse = "制定训练计划时先调用第一页；仅当 coverageGaps 非空且 hasMore=true 时调用第二页。",
    whenNotToUse = "不要用它读取动作步骤；不要调用第三次或用第二页放宽用户硬限制。",
    applicationKey = "fitness",
    group = "exercise",
    tags = {"健身", "计划候选"},
    requiredScopes = {"fitness.read"},
    defaultMaxCallsPerRun = 2)
```

The request object is optional; null means no focus, no extra impact tightening and page 1. Parse non-null `maxImpactLevel` with the closed enum and delegate with `user(context)`.

- [ ] **Step 5: Run Tool tests**

Run: `./mvnw -pl starter -am -Dtest=FitnessToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; the model-facing schema is compact, code-based and identity-safe.

---

### Task 6: Preserve safe Tool-limit diagnostics

**Files:**

- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeRuntimeBridge.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeAdapter.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeAdapterContractTest.java`

**Interfaces:**

- Consumes: Existing per-Tool/global `RunBudget` failures.
- Produces: A final `RunFailureCode.TOOL` message that includes only recognized limit reasons while keeping arbitrary Tool exception details out of the final user-facing failure.

- [ ] **Step 1: Add a failing contract test for the per-Tool limit reason**

Configure a resolved read Tool with `maxCallsPerRun=1`, a repeating model transport that requests it twice, and a global budget greater than 1. Assert the handler runs once and the final result is:

```java
assertEquals(RunFailureCode.TOOL, failure.code());
assertEquals(
    "Tool lookup failed: per-tool call limit exceeded",
    failure.message());
assertFalse(failure.retryable());
```

Extend the existing global-budget-zero test to expect:

```java
"Tool lookup failed: global tool call limit exceeded"
```

Keep `mapsToolFailuresToTheNeutralFailureContract` asserting that an arbitrary `database unavailable` cause is visible in the `TOOL_FAILED` trace event but not copied into the final `RunFailure.message`.

- [ ] **Step 2: Run the adapter contract test and confirm the generic-message failure**

Run: `./mvnw -pl agentbuilder/agentbuilder-framework-adapter/agentscope-adapter -am -Dtest=AgentScopeAdapterContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because final limit failures currently contain only `Tool lookup failed`.

- [ ] **Step 3: Add a dedicated internal limit exception and safe message mapping**

Inside `AgentScopeRuntimeBridge`, add a package-visible nested `ToolCallLimitExceeded` used only by `RunBudget.reserveGlobal` and `reserveTool`. Keep its messages exactly `global tool call limit exceeded` and `per-tool call limit exceeded`.

Give `ToolFailure` a method with this behavior:

```java
String safeRunMessage() {
  return getCause() instanceof ToolCallLimitExceeded
      ? getMessage() + ": " + getCause().getMessage()
      : getMessage();
}
```

In `AgentScopeFailureMapper`, find the actual `ToolFailure` and pass `toolFailure.safeRunMessage()` into `RunFailure`. Do not expose arbitrary handler/SQL/HTTP exception messages in the final failure; detailed causes remain in the existing trace event.

- [ ] **Step 4: Run adapter tests**

Run: `./mvnw -pl agentbuilder/agentbuilder-framework-adapter/agentscope-adapter -am -Dtest=AgentScopeAdapterContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS for recognized limit diagnostics and neutral arbitrary Tool failures.

---

### Task 7: Switch the plan Skill source and fresh-environment defaults to candidate retrieval

**Files:**

- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistry.java`
- Modify: `starter/src/test/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistryTest.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java`

**Interfaces:**

- Consumes: Registered candidate and detail Tools from Task 5.
- Produces: A `fitness.plan.skill` source that performs one candidate query, at most one expansion, one details query and save; fresh environments use global `maxToolCalls=16`.

- [ ] **Step 1: Change Skill registry tests first**

Replace the old expected Plan Skill calls with:

```java
List.of(
    "fitness.goal.current.query",
    "fitness.training.constraints.query",
    "fitness.body.latest.query",
    "fitness.workout.summary.query",
    "fitness.workout.schedule.query",
    "fitness.exercise.candidates.query")
```

Assert the candidate invocation receives an empty argument map, so the optional request defaults to page 1. Remove `fitness.exercise.search` from the Plan Skill test context and add the six exact read Tool keys.

- [ ] **Step 2: Run Skill tests and confirm the old-call failure**

Run: `./mvnw -pl starter -am -Dtest=FitnessSkillRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `PlanSkill` still invokes the three legacy coarse Tools.

- [ ] **Step 3: Update the deterministic Plan Skill preparation**

In `FitnessSkillRegistry.PlanSkill`, populate facts under these keys and invoke exactly the six Tools from Step 1:

```java
facts.put("goal", context.invokeTool("fitness.goal.current.query", Map.of()));
facts.put("constraints", context.invokeTool("fitness.training.constraints.query", Map.of()));
facts.put("body", context.invokeTool("fitness.body.latest.query", Map.of()));
facts.put("recentLoad", context.invokeTool("fitness.workout.summary.query", Map.of()));
facts.put("schedule", context.invokeTool("fitness.workout.schedule.query", Map.of()));
facts.put("exerciseCandidates", context.invokeTool("fitness.exercise.candidates.query", Map.of()));
```

Do not invoke detail or save Tools during preparation; the model chooses IDs first and save remains confirmation-gated.

- [ ] **Step 4: Update fresh-environment Agent seed content without adding an Agent migration**

In the existing Agent V1 baseline and `JdbcAdminWorkbenchStore.seedDefaults()`:

- set the fresh `fitness.coach` draft default `max_tool_calls` to `16`;
- bind `fitness.exercise.candidates.query` and `fitness.exercise.details.query`;
- stop requiring `fitness.exercise.catalog.search` for `fitness.plan.skill`;
- preserve Tools required by meal/analysis/knowledge Skills and unrelated draft configuration.

Replace the baseline Plan Skill body with this exact decision path:

```markdown
# 训练计划编排

1. 读取当前目标、训练限制、最新身体数据、近期训练摘要和已排期计划。
2. 调用 `fitness.exercise.candidates.query` 第一页。difficulty、movementPattern、impactLevel 是稳定英文枚举码；目标部位、肌群和器械是规范化中文标签。
3. 只有 `coverageGaps` 非空且 `hasMore=true` 时，才以相同硬限制调用第二页；不得调用第三次或放宽经验、器械、冲击限制。
4. 从候选中选择 4 至 8 个动作，兼顾目标部位、动作模式、恢复和时长；不得编造动作 ID。
5. 一次调用 `fitness.exercise.details.query` 查询全部入选动作的步骤和常见错误，再编排训练日、动作顺序、时长、安全提示和渐进方案。
6. 仅在用户明确要求保存时调用 `fitness.plan.save`；运行时确认卡片负责最终确认。
```

Set its required Tool keys to the six preparation Tools plus `fitness.exercise.details.query` and `fitness.plan.save`.

- [ ] **Step 5: Run Skill and workbench validation tests**

Run:

```bash
./mvnw -pl starter -am -Dtest=FitnessSkillRegistryTest,AdminWorkbenchServiceTest,JdbcAdminWorkbenchStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; a draft with the new required Tool validates against the runtime registry.

---

### Task 8: Apply Seed to the local DB and run regression verification

**Files:**

- Verify only: all files changed in Tasks 1–7.
- Modify current local Agent DB rows: `agent.agent_skills`, `agent.agent_drafts`; publish through existing Admin Workbench service/API only after all verification gates pass.

**Interfaces:**

- Consumes: Migrated local Fitness schema, validated Dataset, updated Agent runtime.
- Produces: Evidence that all 59 rows are labeled, candidate retrieval is bounded, existing Fitness/Agent boundaries pass, and one verified immutable Agent version is published.

- [ ] **Step 1: Apply Migration and Seed through the supported local flow**

Use the repository local stack so Flyway applies V16, then run:

```bash
node scripts/seed-exercises/seed.mjs
```

Do not run Seed before V16 exists in the target schema.

- [ ] **Step 2: Verify all 59 persisted rows are complete**

Run a read-only SQL assertion:

```sql
SELECT
    count(*) AS total,
    count(*) FILTER (
        WHERE muscle_groups IS NOT NULL
          AND equipment IS NOT NULL
          AND difficulty IS NOT NULL
          AND movement_pattern IS NOT NULL
          AND impact_level IS NOT NULL
    ) AS complete,
    count(*) FILTER (
        WHERE muscle_groups IS NULL
           OR equipment IS NULL
           OR difficulty IS NULL
           OR movement_pattern IS NULL
           OR impact_level IS NULL
    ) AS incomplete
FROM fitness.exercises;
```

Expected: `total=59`, `complete=59`, `incomplete=0`. Run Seed a second time and require the same result to prove idempotence.

- [ ] **Step 3: Run focused tests together**

Run:

```bash
node --test scripts/seed-exercises/seed.test.mjs
./mvnw -pl starter -am -Dtest=DualSchemaIntegrationTest,FitnessAgentQueryServiceTest,JdbcFitnessAgentReadStoreTest,FitnessToolsTest,FitnessSkillRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all focused tests PASS; Docker-backed tests may be skipped only when Testcontainers reports Docker unavailable, and that limitation must be reported.

- [ ] **Step 4: Format and run project-level gates**

Format only Java files touched by this plan using the repository Spotless configuration, then run:

```bash
./mvnw spotless:check
./mvnw -DskipTests compile
./mvnw -pl architecture-tests test
```

Expected: formatting, compile and all architecture tests PASS. Do not use a broad formatter if it would rewrite unrelated user changes.

- [ ] **Step 5: Snapshot and update the current local Agent DB transactionally**

Before writes, query and retain only these fields without printing credentials:

```sql
SELECT skill_key, revision, required_tool_keys, content
FROM agent.agent_skills WHERE skill_key='fitness.plan.skill';
SELECT agent_key, revision, tool_keys, skill_keys, max_tool_calls
FROM agent.agent_drafts WHERE agent_key='fitness.coach';
SELECT version FROM agent.agent_versions
WHERE agent_key='fitness.coach' AND status='PUBLISHED' ORDER BY version DESC LIMIT 1;
```

Within one transaction:

- update `fitness.plan.skill` with the exact body and required Tool keys from Task 7 Step 4, keep it `ACTIVE`/`runtime_ready=true`, increment revision;
- update `fitness.coach` draft by taking the deduplicated union of all bound Skill requirements, set `max_tool_calls=16`, status `DRAFT`, increment revision;
- preserve provider, model, Prompt, Hook, memory, temperature and all unrelated Skill rows;
- roll back unless the new candidate/detail Tool keys occur exactly once and no required Tool is missing.

- [ ] **Step 6: Validate and publish one immutable local Agent version**

Call the existing Admin Workbench validation endpoint and require `valid=true` with no errors before publishing. Publish once, then assert the returned version is greater than the snapshot version and its configuration contains:

```text
fitness.exercise.candidates.query
fitness.exercise.details.query
fitness.plan.skill
maxToolCalls = 16
```

Do not publish if Dataset/Seed/Migration completeness, Tool registration or verification gates have failed.

- [ ] **Step 7: Check scope and final diff hygiene**

Run:

```bash
git diff --check
git status --short
git diff -- scripts/seed-exercises application/fitness starter/src/main/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistry.java starter/src/test/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistryTest.java agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java
```

Verify no `frontend/**`、OpenAPI、Controller、既有 Fitness Migration、跨 schema 查询或 unrelated cleanup was introduced. Report the published Agent version, 59/59 DB completeness, candidate limits and every test command with its actual result.
