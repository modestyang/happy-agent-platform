# Browser Acceptance Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 2026-08-07 浏览器验收发现的饮食识别、饮食反馈、当前目标累计报告、跟练语音、Agent 组件可用性和工作台状态一致性问题，使健身端与 Agent 管理端达到可正式复验状态。

**Architecture:** 保持单体多模块结构和单 PostgreSQL 双 schema 边界。客观健身数据只写 `fitness` schema；AI Provider、模型、Agent、Skill、Hook 和运行记录只写 `agent` schema。健身应用通过 Java Port/Tool 调用 Agent Runtime，不跨 schema 查询；前端只消费正式 API，不使用 Mock 或前端硬编码成功结果。

**Tech Stack:** Java 17、Spring Boot、JDBC、Flyway、PostgreSQL、React 19、TypeScript、React Router、Vitest、OpenAPI 3.1、阿里云 OSS、百炼兼容 API。

## Global Constraints

- 正式项目路径固定为 `/Users/modest/IdeaProjects/happy-agent-platform`；不得修改只读 demo `/Users/modest/IdeaProjects/fitness`。
- 先改 `docs/architecture/openapi/*.yaml`，再生成 TypeScript 类型，最后实现 Java/React；禁止先写 Controller 再补契约。
- 新增数据库结构必须用新的 Flyway migration，禁止修改已应用的 `V1`～`V7` 文件。
- `agentbuilder/**` 不得依赖 `application/**`；Agent 通过已登记 Tool 调用 Fitness Service。
- 图片上传使用限时 OSS 上传凭证，前端和数据库不得持有长期 AK/SK；API Key 继续由 Agent 工作台加密保存。
- 身体指标、饮食和训练记录不绑定目标；当前目标报告按目标起止时间窗口筛选客观记录。
- 所有异步 AI 任务必须持久化状态，页面关闭后继续执行；前端重新打开记录抽屉时创建新的空白表单。
- 跟练语音必须以训练状态事件驱动，禁止在每次 React render 中重复播报或用 `cancel()` 打断连续倒数。
- 页面保持现有暖色、克制、非模板化 AI 视觉，不新增紫色渐变、玻璃拟态堆叠或大段引导文字。
- 实现必须使用 TDD；每个任务先有失败测试，再实现，再运行覆盖该任务的最小测试集。

---

## Dependency Order

`Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7`

Task 2～6 的代码区域不同，但都依赖 Task 1 固定契约生成和本地运行基线；为了避免 OpenAPI、生成文件和共享测试夹具冲突，按顺序实施，不并行改写同一工作区。

### Task 1: 修复工作台组件状态残留并统一本地端口

**Files:**
- Modify: `frontend/src/admin/pages/ComponentType.tsx`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`
- Modify: `deploy/local-run.sh`
- Modify: `AGENTS.md`
- Modify: `docs/acceptance/browser-acceptance-2026-08-07.md`

**Interfaces:**
- Consumes: `ComponentType({ type, label })`、`admin.snapshot()`。
- Produces: 路由分类变化时不会展示上一分类详情；本地唯一前端地址为 `http://127.0.0.1:5173`。

- [ ] **Step 1: 写失败测试**

在 `AdminWorkbench.test.tsx` 增加测试：先打开模型详情，再点击技能菜单；断言技能列表加载完成前显示“正在拉取组件目录…”，加载后右侧显示“选择左侧组件”，且页面不再包含刚才的模型详情标题。

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx`

Expected: FAIL，技能路由仍显示上一模型详情。

- [ ] **Step 3: 实现路由状态重置**

在 `ComponentType` 的 `[type]` effect 开始处执行：

```ts
setLoading(true);
setComponents([]);
setSelected(undefined);
setForm({ displayName: '', description: '', status: 'DRAFT', tags: [], config: {} });
setQuery('');
setError('');
setSuccess('');
```

请求成功和失败都必须结束 loading；失败时右侧不得保留旧对象。

- [ ] **Step 4: 固定本地启动入口**

保留 `local-run.sh` 的 Vite 默认 `5173`，在脚本启动前检测 `5173` 与 `8080`；若端口已被本项目进程占用则复用，若被其他进程占用则打印 PID 和明确错误后退出。将 `AGENTS.md` 与旧验收报告中的本地地址统一为 `5173`，不再把 `5176` 描述为正式入口。

- [ ] **Step 5: 运行验证并提交**

Run:

```bash
npm --prefix frontend test -- AdminWorkbench.test.tsx
npm --prefix frontend run typecheck
```

Expected: 两个命令退出码均为 0。

Commit: `fix(admin): reset component detail across routes`

### Task 2: 完成饮食照片上传与 AI 识别闭环

**Files:**
- Modify: `docs/architecture/openapi/public-v1.yaml`
- Modify: `scripts/contracts/fixtures/public-coverage.json`
- Regenerate: `frontend/src/api/generated/public.ts`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V4__meal_recognition.sql`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Create: `starter/src/main/java/happy/jayden/yang/fitness/MealRecognitionRuntime.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAppController.java`
- Modify: `starter/src/test/java/happy/jayden/yang/config/DualSchemaIntegrationTest.java`
- Modify: `frontend/src/api.ts`
- Create: `frontend/src/components/MealRecordForm.tsx`
- Create: `frontend/src/components/MealRecordForm.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Consumes: 已配置 Provider、模型目录中的视觉模型、OSS 配置、当前用户会话。
- Produces:

```java
public interface MediaUploadPort {
  MediaUploadTicket createTicket(UUID userId, String contentType, long contentLength, String sha256);
}

public interface MealRecognitionPort {
  MealRecognitionResult recognize(UUID userId, UUID mediaId, MealType mealType, Instant occurredAt);
}
```

```ts
type MealRecognitionState =
  | { status: 'IDLE' }
  | { status: 'UPLOADING'; previewUrl: string }
  | { status: 'RECOGNIZING'; jobId: string; previewUrl: string }
  | { status: 'READY'; jobId: string; previewUrl: string; items: Food[] }
  | { status: 'FAILED'; message: string; previewUrl?: string };
```

- [ ] **Step 1: 收敛并校验 OpenAPI 契约**

复用现有 `/api/v1/app/media-upload-tickets`、`/meal-recognition-jobs`、`/meal-recognition-jobs/{jobId}` 与 `/meal-records`。补充以下约束：

- 只接受 JPEG、PNG、WebP，单文件最大 `10_485_760` bytes。
- Job 状态固定为 `QUEUED | RUNNING | SUCCEEDED | FAILED`。
- `SUCCEEDED` 至少返回一个可编辑候选项；候选包含 `name`、`estimatedKcal`、`confidence`。
- `RECOGNITION_CONFIRMED` 保存时必须带原始 `recognitionJobId`。
- 上传票据有效期最多 10 分钟，不返回任何 OSS 长期凭据。

Run:

```bash
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
```

Expected: 契约 lint 和生成均成功，覆盖夹具无缺失 operationId。

- [ ] **Step 2: 写后端失败集成测试**

覆盖：创建上传票据、创建识别 Job、查询状态、识别完成后修改候选并保存饮食、重新读取记录；同时断言 `meals`/`meal_recognition_jobs` 没有 `goal_id`，Agent schema 无跨 schema 外键。

- [ ] **Step 3: 新增持久化结构**

`V4__meal_recognition.sql` 创建：

- `media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,created_at)`；
- `meal_recognition_jobs(job_id,user_id,media_id,meal_type,occurred_at,status,candidates,failure_code,failure_message,created_at,updated_at)`；
- 为 `meal_recognition_jobs(user_id,created_at desc)` 建索引；
- 给现有 `meals` 增加可空 `source`、`recognition_job_id`、`note` 字段，默认旧记录为 `MANUAL`。

- [ ] **Step 4: 实现识别运行时**

`MealRecognitionRuntime` 从 Agent 工作台数据库解析已启用且支持视觉输入的模型组件与 Provider；调用百炼兼容接口并要求严格 JSON Schema 输出。不得在代码中写死 API Key 或 Endpoint。模型未配置、结果不合法、超时分别落库为明确失败码，禁止返回假食物。

- [ ] **Step 5: 实现前端单层记录交互**

`MealRecordForm` 与身体记录仍位于同一个记录抽屉：

- 同时提供“拍照识别”和手动食物输入，不新增二级页面；
- 选图立即本地预览并自动开始上传/识别；
- `UPLOADING`/`RECOGNIZING` 时锁定当前面板编辑区，但关闭按钮可用；
- Job 成功后解锁识别结果，允许修改名称和热量、增删食物后保存；
- 关闭抽屉后 Job 在服务端继续；再次点击“记录”创建全新空白表单，不恢复上次未完成面板；
- 失败时显示重试和“改为手动填写”，不丢失图片预览。

- [ ] **Step 6: 前后端验证并提交**

Run:

```bash
npm --prefix frontend test -- MealRecordForm.test.tsx
./mvnw -pl starter -am test -DskipITs=false
node scripts/contracts/lint.mjs
```

Expected: 上传状态机、识别结果编辑、失败降级和数据库持久化测试全部通过。

Commit: `feat(fitness): add meal photo recognition flow`

### Task 3: 完成今日饮食推荐点赞、点踩与反馈闭环

**Files:**
- Modify: `docs/architecture/openapi/public-v1.yaml`
- Regenerate: `frontend/src/api/generated/public.ts`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V7__meal_recommendation_feedback.sql`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V11__feedback_note_unicode_whitespace.sql` (final Unicode consistency remediation; Task 4 starts at V12)
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAppController.java`
- Modify: `frontend/src/components/MealRecommendationPage.tsx`
- Modify: `frontend/src/components/MealRecommendationPage.test.ts`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Produces:

```java
public record CreateMealRecommendationFeedbackRequest(
    UUID recommendationId,
    Sentiment sentiment,
    FeedbackReason reason,
    String note) {}

public enum Sentiment { LIKE, DISLIKE }
public enum FeedbackReason { TASTE, PORTION, INGREDIENT, CALORIES, COOKING, OTHER }
```

- [ ] **Step 1: 写契约与失败测试**

为每餐推荐新增反馈请求：LIKE 允许不填原因；DISLIKE 必须提供原因；OTHER 必须提供 1～300 字说明。相同用户、推荐和餐次只有一条有效反馈，再次提交执行幂等更新。

前端测试断言：点赞后按钮变为选中；点踩打开底部原因面板；选择“其他”但无文本时禁止提交；成功后关闭面板并保留点踩状态。

- [ ] **Step 2: 持久化反馈**

创建 `meal_recommendation_feedback`，唯一键为 `(user_id,recommendation_id)`，包含 sentiment、reason、note、created_at、updated_at。所有记录仍属于用户客观偏好数据，不绑定目标。

- [ ] **Step 3: 将反馈加入下一次推荐上下文**

`FitnessTools` 新增读取近 30 天饮食推荐反馈的 Tool 输出；每日 05:30 生成和手动补生成三餐时，把聚合后的喜欢食材、排斥食材、分量/热量/做法原因交给 Agent。原始自由文本要限制长度并作为数据引用，不能拼接成系统指令。

- [ ] **Step 4: 完成卡片交互**

每个 READY 餐卡底部放置图标化点赞/点踩，不使用长引导文案。提交期间只锁定当前餐反馈；失败时恢复原状态并显示可重试提示。

- [ ] **Step 5: 验证并提交**

Run:

```bash
npm --prefix frontend test -- MealRecommendationPage.test.ts
./mvnw -pl starter -am test -DskipITs=false
```

Commit: `feat(fitness): persist meal recommendation feedback`

### Task 4: 将当前目标累计报告升级为结构化 AI 内容块

**Files:**
- Modify: `docs/architecture/openapi/public-v1.yaml`
- Regenerate: `frontend/src/api/generated/public.ts`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V12__current_goal_reports.sql`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V13__objective_record_watermarks.sql`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/AgentRuntimeConversation.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAppController.java`
- Create: `frontend/src/components/CurrentGoalReport.tsx`
- Create: `frontend/src/components/CurrentGoalReport.test.tsx`
- Modify: `frontend/src/components/ChatMarkdown.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Produces `CurrentGoalReportBlock`，固定包含：

```ts
type CurrentGoalReportView = {
  conclusion: { summary: string; score: number; grade: 'A' | 'B' | 'C' | 'D' };
  metrics: Array<{ key: string; label: string; value: number; unit: string; comparison?: number; trend: 'UP' | 'DOWN' | 'STABLE' | 'NOT_AVAILABLE' }>;
  weightTrend: Array<{ weekStart: string; valueJin: number }>;
  trainingVolume: Array<{ weekStart: string; minutes: number; sessions: number }>;
  trainingStructure: Array<{ area: string; percent: number }>;
  cardioPercent: number;
  strengthPercent: number;
  highlights: string[];
  weaknesses: string[];
  nextActions: Array<{ title: string; rationale: string; action: 'GENERATE_PLAN' | 'OPEN_RECORD' | 'NONE' }>;
};
```

- [ ] **Step 1: 写结构化报告失败测试**

覆盖：报告窗口从当前目标 `startedAt` 到当前日期；客观记录按时间过滤而非 `goal_id`；不足 4 周时趋势数组仍按周补空档但页面显示“数据积累中”；新记录晚于 `computedThrough` 时状态为 STALE；Agent 只生成 conclusion、highlights、weaknesses、nextActions，指标和图表由服务端确定性计算。

- [ ] **Step 2: 建立持久化报告状态**

`current_goal_reports` 使用 `(user_id,goal_id,goal_version)` 唯一键，状态为 `QUEUED | GENERATING | READY | STALE | FAILED`，保存确定性数据快照、AI 结构化字段、computed_through、failure、updated_at。

- [ ] **Step 3: 完成 Agent 结构化输出**

Agent 输入只包含经过服务层裁剪的指标和事实；要求 JSON Schema 输出，不允许生成 HTML。解析失败将报告置 FAILED，页面提供重试，不回退到伪造报告。

- [ ] **Step 4: 在瘦瘦对话中渲染报告卡**

首页“报告”仍进入 `/ai`；消息结果若为 `CURRENT_GOAL_REPORT`，固定渲染：

1. 顶部一句结论 + 评分；
2. 带环比的关键指标；
3. 至少 4 周宽度的体重折线和训练量柱状图；
4. 部位分布、力量/有氧比例；
5. 2 条正向 + 1～2 条短板；
6. 下周可执行建议和“一键生成计划”。

QUEUED/GENERATING 显示 holding 动效；FAILED 显示失败原因与重试。输入框继续固定底部。

- [ ] **Step 5: 验证并提交**

Run:

```bash
npm --prefix frontend test -- CurrentGoalReport.test.tsx
./mvnw -pl starter -am test -DskipITs=false
```

Commit: `feat(fitness): render structured current goal report`

### Task 5: 重构跟练语音为状态事件队列

**Files:**
- Create: `frontend/src/workout/voiceGuidance.ts`
- Create: `frontend/src/workout/voiceGuidance.test.ts`
- Modify: `frontend/src/components/WorkoutPlayer.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Produces:

```ts
export type VoiceCue = { id: string; text: string; interrupt: boolean };
export function voiceCueForTransition(previous: WorkoutSessionState, current: WorkoutSessionState, exercise: PlayerExercise): VoiceCue | undefined;
export interface VoiceEngine {
  unlock(): void;
  speak(cue: VoiceCue): void;
  mute(value: boolean): void;
  stop(): void;
}
```

- [ ] **Step 1: 写语音顺序失败测试**

测试完整短流程：用户点击开始后依次出现“训练准备，3”“2”“1”“深蹲，第 1 组”“3”“2”“1”“休息 20 秒”；每个 cue id 只消费一次。断言普通连续 cue 不调用 cancel；仅跳过动作、退出训练和用户静音调用 stop/cancel。

- [ ] **Step 2: 实现 VoiceEngine**

首次“开始训练”用户手势中执行 `speechSynthesis.resume()` 并解锁；使用内部队列等待 `onend` 后播放下一条。暂停时播报一次“训练暂停”，继续时播报一次“继续训练”。不支持 Web Speech API 时，在预览页和训练页都显示简短文字提示，计时照常运行。

- [ ] **Step 3: 将播报绑定状态 transition**

`WorkoutPlayer` 保存上一状态，只在 phase、remaining、setIndex 或 exerciseIndex 发生有效状态变化时生成 cue；组件 render 不直接调用 `speechSynthesis.cancel()`。

- [ ] **Step 4: 验证并提交**

Run:

```bash
npm --prefix frontend test -- voiceGuidance.test.ts App.test.tsx
npm --prefix frontend run typecheck
```

Commit: `fix(workout): synchronize queued voice guidance`

### Task 6: 补齐 Agent Skill、Hook 的真实可用状态和发布语义

**Files:**
- Modify: `agentbuilder/agentbuilder-core/src/main/java/**`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/**`
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/**`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/FitnessSafetyHook.java`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/FitnessSkillRegistry.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V8__activate_fitness_capabilities.sql`
- Modify: `frontend/src/admin/pages/Overview.tsx`
- Modify: `frontend/src/admin/pages/AgentEditor.tsx`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`
- Add tests under matching `agentbuilder/*/src/test/java/**` and `starter/src/test/java/**`.

**Interfaces:**
- Produces:

```java
public interface ExecutableSkill {
  String key();
  SkillResult execute(AgentExecutionContext context, Map<String, Object> input);
}

public interface AgentHook {
  String key();
  HookDecision beforeRun(AgentExecutionContext context);
  void afterRun(AgentExecutionContext context, AgentRunResult result);
}
```

- [ ] **Step 1: 写发布门禁和 Hook 失败测试**

覆盖：组件仅在“DB 状态 AVAILABLE 且运行时存在相同 key 的 handler”时可选/可发布；只把数据库状态改为 AVAILABLE 不能绕过门禁。安全 Hook 对用户明确的胸痛、眩晕、受伤、极端节食和过度训练信号返回 BLOCK；普通训练问题返回 ALLOW。

- [ ] **Step 2: 实现三个运行时能力**

- `fitness.safety`：运行前读取本轮消息与必要用户配置，执行确定性高风险关键词/状态检查；BLOCK 时返回安全建议，不调用模型。
- `fitness.meal.skill`：组合身体指标、近 6 天训练、历史饮食、近 30 天反馈和偏好，要求 Agent 输出三餐结构化计划。
- `fitness.plan.skill`：组合当前目标、可用时间、安全限制、历史负荷和动作库，输出按日训练计划；只能通过 Tool 读取/写入 fitness 数据。

- [ ] **Step 3: 激活组件和修正文案**

V8 migration 只在 handler 已随当前构建注册时，将三个组件置 AVAILABLE 并更新当前草稿装配；不覆盖用户手工修改的其他 Agent。工作台总览明确区分：

- “已发布 vN：运行就绪”；
- “草稿 revision N：可发布 / 尚有 N 项待完成”。

禁止出现“运行准备就绪”同时又说“还有 3 个组件待完成”的矛盾组合。

- [ ] **Step 4: 验证并提交**

Run:

```bash
./mvnw -pl agentbuilder/agentbuilder-service,starter -am test
npm --prefix frontend test -- AdminWorkbench.test.tsx
```

Commit: `feat(agentbuilder): activate fitness skills and safety hook`

### Task 7: 全量回归、真实浏览器复验与验收报告更新

**Files:**
- Modify: `docs/acceptance/browser-acceptance-2026-08-07.md`
- Create: `docs/acceptance/browser-acceptance-2026-08-09.md`
- Create/replace screenshots under `docs/acceptance/screenshots/2026-08-09/`
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

**Interfaces:**
- Consumes: Tasks 1～6 的所有提交。
- Produces: 可复现的测试记录、真实浏览器截图和最终通过/未通过判定。

- [ ] **Step 1: 运行静态与单元测试**

Run:

```bash
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
git diff --exit-code frontend/src/api/generated
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
./mvnw verify -q
```

Expected: 所有命令退出码 0；生成文件无未提交差异。

- [ ] **Step 2: 检查架构边界**

Run: `./mvnw -pl architecture-tests -am test`

Expected: Agent Builder 不依赖 Fitness application；没有跨 schema SQL、外键或事务。

- [ ] **Step 3: 用正式本地脚本启动并浏览器复验**

使用 `deploy/local-run.sh`，只访问 `http://127.0.0.1:5173`。必须真实操作并截图：

1. 上传饮食图片 → 识别中 → 识别完成 → 修改热量 → 保存；
2. 推荐餐点踩 → 选择原因 → 保存 → 刷新后状态保持；
3. 当前目标报告 holding → 结构化报告 → 一键生成计划；
4. 跟练完整短动作，确认语音 cue 顺序和计时同步；
5. Agent 模型/技能/Hook 路由切换无详情残留；
6. 草稿发布准备度与已发布运行态文案一致；
7. 调试台真实返回并生成 Run/Trace。

- [ ] **Step 4: 更新验收报告**

报告必须列出：环境、测试账号、数据来源、通过项、未通过项、截图索引、控制台 error/warn、后端 problem+json 错误、最终判定。不得以“端口已监听”替代页面与交互验收。

- [ ] **Step 5: 最终代码审查并提交**

至少执行两阶段审查：需求符合性审查、代码质量审查；关闭所有 Critical/Important 问题后提交。

Commit: `test(acceptance): verify remediation flows`

## Definition of Done

- 饮食图片识别、手工修改和保存均使用真实数据库与真实配置模型。
- 推荐反馈写入数据库并进入下一次推荐上下文。
- 当前目标累计报告为结构化内容块，不由 Agent 直接生成 HTML。
- 跟练倒计时和语音 cue 在真实手机/Chrome 中同步，无重叠或只播开头。
- Agent 工作台组件详情不串页，三个 Fitness 能力具有真实 handler 后才显示 AVAILABLE。
- 全量前后端、契约、架构测试通过，浏览器截图和验收报告齐全。
