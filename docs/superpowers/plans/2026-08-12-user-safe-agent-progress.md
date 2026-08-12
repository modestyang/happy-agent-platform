# User-Safe Agent Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机端 Agent 对话只展示少量、固定、业务化的处理阶段，不接收或显示 Provider 原生 Thinking、Tool/Skill/Hook 名称和技术异常；管理端 Trace 继续保留原始执行信息。

**Architecture:** 在 `FitnessAgentRunService` 的用户 SSE 边界投影持久化事件，只放行正文、确认卡、结束信号和固定进度文案；开发者 SSE 保持原始事件。`AgentRunMessage` 再使用固定文案白名单并彻底忽略 Thinking delta，形成纵深防护。

**Tech Stack:** Java 17、Spring MVC SSE、React、TypeScript、Vitest、JUnit 5。

## Global Constraints

- 不修改数据库、migration、OpenAPI 或持久化 Trace 结构。
- 不修改管理端 Trace 的原始事件展示能力。
- 用户端最多显示四个固定阶段，不出现动态 key、参数、字段、原始异常或 Thinking 文本。
- 保留当前工作树中已有未提交改动，只做目标文件内的局部修改。
- 不 commit、不 push。

---

### Task 1：在用户 SSE 边界投影安全事件

**Files:**

- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAgentRunService.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentRunServiceTest.java`

**Interfaces:**

- Consumes: `JdbcRunTraceRepository.StreamEvent`。
- Produces: `static Optional<StreamEvent> projectUserEvent(StreamEvent event)`，以及用户/开发者两种 SSE 投影视图。

- [x] **Step 1: Write the failing projection tests**

覆盖以下字面期望：

```java
assertEquals("正在整理建议", projected.data().get("summary"));
assertFalse(projected.data().containsKey("blockId"));
assertTrue(projectUserEvent(rawThinkingDelta).isEmpty());
assertEquals("这次处理没有成功，请稍后重试。", projectedError.data().get("message"));
```

- [x] **Step 2: Run the focused test and verify RED**

Run: `./mvnw -pl starter -Dtest=FitnessAgentRunServiceTest test`

Expected: FAIL because `projectUserEvent` does not exist.

- [x] **Step 3: Implement the minimal public event projection**

规则：

```text
RUN_STATE/RUNNING                      -> 正在理解你的需求
RUN_EVENT/BLOCK_STARTED/THINKING       -> 正在整理建议
RUN_EVENT/TOOL_*                       -> 正在查看相关记录
RUN_EVENT/BLOCK_DELTA                  -> drop
TEXT_DELTA                             -> only { delta }
APPROVAL                               -> approvalId/status/title/proposal only
ERROR                                  -> fixed user-safe message
COMPLETED                              -> empty data, keep terminal event
other internal RUN_EVENT               -> fixed stage or drop
```

`streamUser` 使用投影；`streamDeveloper` 原样发送持久化事件。游标仍按原始 sequence 前进，避免重连重复读取被过滤事件。

- [x] **Step 4: Run the focused backend test and verify GREEN**

Run: `./mvnw -pl starter -Dtest=FitnessAgentRunServiceTest test`

Expected: PASS.

### Task 2：手机端只渲染固定业务阶段

**Files:**

- Modify: `frontend/src/components/AgentRunMessage.tsx`
- Test: `frontend/src/components/AgentRunMessage.test.tsx`

**Interfaces:**

- Consumes: 用户 SSE 的 `RUN_STATE`、`RUN_EVENT`、`TEXT_DELTA`、`APPROVAL`。
- Produces: 最多四项的 `AgentRunUiMessage.progress`，不再生产或渲染 `thinking`。

- [x] **Step 1: Replace the raw-thinking expectation with a failing privacy regression test**

测试输入包含：`fitness.plan.skill`、`fitness.exercise.search`、原始 Thinking 文本和技术化 `RUN_STATE.summary`。

字面期望：

```typescript
expect(message.progress).toEqual([
  '正在理解你的需求',
  '正在查看相关记录',
  '正在整理建议',
]);
expect(screen.queryByText('思考过程')).not.toBeInTheDocument();
expect(screen.queryByText(/fitness\./)).not.toBeInTheDocument();
expect(screen.queryByText('I should inspect tool arguments')).not.toBeInTheDocument();
```

- [x] **Step 2: Run the focused frontend test and verify RED**

Run: `npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx`

Expected: FAIL because the current reducer stores Thinking delta and renders dynamic Tool/Skill/Hook names.

- [x] **Step 3: Implement fixed progress mapping and remove Thinking rendering**

只允许以下文案进入 `progress`：

```text
正在理解你的需求
正在查看相关记录
正在整理建议
计划已准备好，请核对是否保存
```

忽略原始 `RUN_STATE.summary`、Thinking delta 和未知动态进度；对重复阶段去重。

- [x] **Step 4: Run the focused frontend test and verify GREEN**

Run: `npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx`

Expected: PASS.

### Task 3：验证用户视图与开发者 Trace 分离

**Files:**

- Verify: `frontend/src/admin/components/traceEvents.test.ts`
- Verify: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**

- Consumes: 未投影的管理端 Trace 事件。
- Produces: 管理端仍可查看 Tool 与 Thinking 明细；手机端无原始明细。

- [x] **Step 1: Run targeted frontend regressions**

Run: `npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx src/admin/components/traceEvents.test.ts src/admin/AdminWorkbench.test.tsx`

Expected: PASS.

- [x] **Step 2: Run typecheck and backend formatting/compile checks**

Run:

```bash
npm --prefix frontend run typecheck
./mvnw spotless:apply
./mvnw -DskipTests compile
```

Expected: all commands exit 0.

- [x] **Step 3: Review the scoped diff**

确认用户端没有 `thinking` 渲染、动态 key 文案或原始异常；开发者 SSE 与 `traceEvents.ts` 未被降级；`git diff --check` 通过。

## Verification Result

- Backend focused tests: 4 passed.
- Frontend focused regressions: 61 passed; developer Trace additionally verifies raw Thinking retention.
- Architecture tests: 7 passed.
- Frontend typecheck, Maven compile, Spotless, and `git diff --check`: passed.
