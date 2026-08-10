# 通用 Agent 调试台设计

## 目标

调试台展示并运行全部已发布 Agent，不再把 `fitness.coach` 当成唯一可选项。未发布草稿仍不进入调试台。

## 方案

- 前端仅按 `publishedVersion > 0` 过滤，默认优先选中 `fitness.coach`，否则选择第一个已发布 Agent。
- `/api/v1/admin/playground/runs` 接受任意已发布 Agent Key。
- `fitness.coach` 继续使用现有健身运行链路，保留用户数据、Tool、确认保存训练计划和 SSE。
- 其他 Agent 使用其不可变发布快照中的 Provider、Model、Prompt 和凭据，通过通用运行器执行；模型输出按增量写入现有持久 Run 事件并由同一 SSE 接口输出。
- 通用 Agent 的 Run、会话消息和 Trace 继续写入现有 `agent_runs`、`agent_run_events`、`agent_run_stream_events` 与会话表，不新增 migration。

## 边界

- 不新增企业级调度、租户或 Agent 类型系统。
- 不允许调试未发布草稿。
- 通用 Agent 不冒用健身上下文；健身专属 Tool/Skill/Hook 仍由 `fitness.coach` 的正式运行链路执行。
- 通用运行失败必须写入 Trace 并通过 SSE 返回可读错误，不能返回假结果。

## 验收

1. 已发布的 `food-recomend` 与 `fitness.coach` 都出现在调试 Agent 下拉框。
2. 选择通用 Agent 后，请求携带其真实 `agentKey`。
3. 后端不再拒绝非 `fitness.coach`，并使用该 Agent 的最新已发布快照创建 Run。
4. 通用 Agent 输出产生 `TEXT_DELTA` 和终态事件，并可在 Trace 中查到。
5. 健身 Agent 原有流式响应、Tool 和确认流程不回归。
