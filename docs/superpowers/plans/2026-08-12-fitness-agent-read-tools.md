# Fitness Agent Read Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 16 个面向 AI 的细粒度健身只读 Tool，并让它们通过独立、有界的查询链路读取现有 Fitness 数据，不影响页面和其他业务流程的查询语义。

**Architecture:** 新增 `FitnessAgentReadStore → FitnessAgentQueryService → FitnessTools` 专用链路。JDBC 实现使用现有 Fitness DataSource 和表结构，但不复用或修改 `loadBootstrap/loadForTool/loadForAi`；旧 Tool 与 `fitness.plan.save` 保持兼容。服务层负责窗口、空值、汇总和营养估算，Tool 层只负责模型合同与可信身份注入。

**Tech Stack:** Java 17、Spring JDBC、Spring Bean wiring、JUnit 5、Mockito、Testcontainers、Agent Tool scanner。

## Global Constraints

- 不修改 `frontend/**`，避免与应用端 AI 对话组件通用化任务冲突。
- 不修改数据库、Migration、OpenAPI 或公开 Controller。
- 不改变 `FitnessApplicationService`、`FitnessStore.loadBootstrap/loadForTool/loadForAi` 的现有页面查询逻辑。
- 新 Tool 不接收 `userId`；身份只来自 `ToolExecutionContext.userId`。
- 所有历史查询都有明确窗口和硬上限；未知值保持 `null`，未记录日不按零摄入处理。
- 保留旧 `fitness.profile.query`、`fitness.workout.query`、`fitness.meal.query`、`fitness.exercise.search` 和现有 `fitness.plan.save`。
- 不 commit、不 push。

---

### Task 1: Tool catalog contract

**Files:**

- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`

**Interfaces:**

- Produces: 16 个只读 Tool key 的扫描合同；输入 schema 不含 `userId`；禁止聚合/伪能力 Tool。

- [x] 写失败测试，断言目标 Tool keys、READ_ONLY/LOW/`fitness.read` 元数据和 schema 边界。
- [x] 运行 `./mvnw -pl starter -am -Dtest=FitnessToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因新 Tool 缺失失败。

### Task 2: Agent-only query model and deterministic calculations

**Files:**

- Create: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Create: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentQueryService.java`
- Create: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/NutritionTargetEstimator.java`
- Create: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentQueryServiceTest.java`
- Create: `starter/src/test/java/happy/jayden/yang/fitness/NutritionTargetEstimatorTest.java`

**Interfaces:**

- Consumes: `FitnessPorts.FitnessAgentReadStore` 的用户隔离原始事实。
- Produces: 带 `asOf/timezone/window/dataStatus/recordCount/limit/truncated/limitations` 的有界查询结果，以及版本化营养估算结果。

- [x] 写失败测试覆盖年龄范围、身体最新非空值、训练空分母、饮食漏记日、limit 截断和营养估算缺字段。
- [x] 确认测试因类型/实现缺失而失败。
- [x] 实现不可变事实 DTO、专用 Port、查询服务和 `nutrition-targets-v1` 估算器。
- [x] 运行聚焦测试并确认通过。

### Task 3: Isolated JDBC read store

**Files:**

- Create: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessAgentReadStore.java`
- Create: `starter/src/test/java/happy/jayden/yang/fitness/JdbcFitnessAgentReadStoreTest.java`

**Interfaces:**

- Implements: `FitnessPorts.FitnessAgentReadStore`。
- Guarantees: 每个用户查询直接带 `user_id=?`；动作库查询除外；不调用 `JdbcFitnessStore`。

- [x] 用 Testcontainers 写失败测试，覆盖用户隔离、最近非空身体指标、窗口、排序、limit+1 与 meal note 不暴露。
- [x] 确认测试因 JDBC 实现缺失失败。
- [x] 实现专用 SQL 与 JSON 解析，查询范围最大 90/365 天且列表有硬上限。
- [x] 运行聚焦 JDBC 测试并确认通过。

### Task 4: Publish 16 read Tools without touching page flows

**Files:**

- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`

**Interfaces:**

- Consumes: `FitnessAgentQueryService`。
- Produces: `fitness.user.profile.query`、`fitness.goal.current.query`、`fitness.training.constraints.query`、`fitness.nutrition.preferences.query`、`fitness.body.latest.query`、`fitness.body.trend.query`、`fitness.workout.schedule.query`、`fitness.workout.history.query`、`fitness.workout.summary.query`、`fitness.exercise.catalog.search`、`fitness.exercise.details.query`、`fitness.meal.history.query`、`fitness.meal.summary.query`、`fitness.meal.recommendations.query`、`fitness.meal.feedback.query`、`fitness.nutrition.targets.estimate`。原 `fitness.meal.feedback_context@1` 保持原合同供现有流程使用。

- [x] 为每类输入边界补失败测试：日期、windowDays、days、limit、exerciseIds、activityLevel。
- [x] 在 `FitnessTools` 中添加模型友好的注解合同与闭合输出 schema，只委托专用查询服务。
- [x] 在 `FitnessExperienceConfig` 独立装配 read store、query service、estimator；原 `FitnessApplicationService` Bean 不变。
- [x] 验证 16 个新读 Tool 和旧 Tool/写 Tool 同时可扫描、编码和调用。

### Task 5: Regression verification

**Files:**

- Verify only: existing Fitness service/controller/page tests and architecture tests。

- [x] 运行聚焦 Tool、query service、JDBC 测试。
- [x] 仅对本次 Java 文件运行 Spotless，并运行 `./mvnw -DskipTests compile`、`./mvnw -pl architecture-tests test`。
- [x] 运行相关现有 `FitnessExperienceIntegrationTest`，确认页面 Bootstrap、写入链路和旧反馈合同未受影响。
- [x] 运行 `git diff --check`，并核对本任务没有修改 `frontend/**`、Migration、OpenAPI 和公开 Controller。

## Verification Notes

- 聚焦新增能力：18 tests，0 failures。
- 现有 Bootstrap、确认后保存训练计划、旧反馈上下文：3 tests，0 failures。
- 全量 Java compile：通过。
- Architecture tests：7 tests，0 failures。
- 全量上游串行测试另有既存失败：仓库已包含 Fitness V14/V15，而 `DualSchemaIntegrationTest` 仍期望 13 条 Migration；另有并行开发中的每日餐单测试失败。本任务未修改这些 Migration 或测试。
