# 训练计划 Tool 循环与 SAA 运行修复设计

## 背景

当前健身 Agent 有三类相关故障：

1. `fitness.plan.save` 将多天计划限定为连续 7 天，导致周一、周三、周五等真实训练安排无法保存。
2. `fitness.exercise.candidates.query` 将 `focusAreas` 限制为最多 3 项，但该限制没有安全或容量上的必要性，并且只存在于文字与服务端校验中。
3. AgentScope 与 Spring AI Alibaba（SAA）Adapter 将可纠正的 Tool 参数或领域错误升级为整个 Run 失败，模型收不到 Tool 错误，无法在 ReAct 循环中修正参数。

SAA 另有独立的模型 endpoint 故障。Provider endpoint 已包含 `/v1`，而 Spring AI 默认再次追加 `/v1/chat/completions`，实际请求形成双 `/v1` 并返回 404；异常映射又将底层 Provider HTTP 错误隐藏为 `Framework execution failed`。

## 目标

- 一次确认可保存 1 到 31 个任意、互不重复的未来训练日期，不要求连续。
- `focusAreas` 接受任意不重复的现有目标部位；自然上限为现有 7 类，`全身` 继续表示无部位偏好且不可与具体部位混用。
- 模型可纠正的 Tool 输入和领域错误以结构化错误 ToolResult 返回模型，由框架原生循环继续执行。
- 权限、审批、Hook、调用预算、超时、中断和基础设施故障继续终止 Run。
- SAA 对 OpenAI-compatible endpoint 的路径只拼接一次，并向用户返回安全、可诊断的 Provider 错误。
- 不新增依赖、不修改数据库 schema、不改变两个业务 schema 的边界。

## 非目标

- 不增加自定义 while-loop，不复制 AgentScope 或 SAA 的 ReAct 调度逻辑。
- 不让模型自动重试写数据库；审批前只执行无副作用校验，批准后仍只写一次。
- 不把 Provider 响应正文、堆栈、凭据或用户隐私暴露给模型或前端。
- 不修改训练计划持久化表结构。

## 方案

### 训练计划契约

`fitness.plan.save` 发布新 Tool 合约版本。模型输入只表达计划日集合，不再要求模型选择 `DAY` 或 `WEEK`：

- `days` 必填，包含 1 到 31 个日计划。
- 日期可以不连续，但必须互不重复；服务端按日期排序后冻结和展示。
- 日期继续限制在今天至未来一年内。
- 每日标题、预计分钟、动作数量、动作 ID 去重和动作归属继续使用现有业务约束。
- 页面展示范围由服务端派生：1 个训练日输出 `DAY`，多于 1 个输出 `MULTI_DAY`；前端继续读取历史 `WEEK` 事件。

旧审批参数中的 `scope` 在执行兼容层被移除或忽略，再交给新合约；已有冻结审批无需数据库迁移。新模型 Schema 不再暴露 `scope`。

### 候选动作目标部位

删除 `focusAreas` 最多 3 项的人工限制。参数允许现有七类目标部位的任意不重复子集：臀腿、核心、胸部、背部、肩部、手臂、心肺。

- `全身` 必须单独出现，并归一化为无部位偏好。
- 空值、未知值、重复值及 `全身` 与具体部位混用仍是模型可纠正错误。
- 候选查询现有第一页 32 条、第二页 12 条上限保持不变，因此不会产生无界结果。

### Schema 与审批前校验

扩展 `AgentToolParam` 和 Schema 生成器以支持 `minItems`、`maxItems`。`ToolSchemaCodec` 已支持这两个 JSON Schema 关键字，继续作为两个 Adapter 的统一机器校验边界。

`AgentToolHandler` 增加保留函数式接口兼容性的默认无副作用参数校验入口。反射 Handler 在该入口完成参数映射和 DTO 构造但不调用 Tool 方法。两个 Adapter 都在触发用户审批前调用它，以便计划集合数量、重复日期和字段形状错误先返回模型，而不是生成一张不可执行的确认卡。

Fitness service 保留最终领域校验和事务写入，审批前校验不能代替写入边界的校验。

### Tool 错误与框架循环

定义共享的 `ToolInputException` 作为模型可纠正输入/领域错误的唯一中立标记，并由共享 `ToolErrorResponse` 生成结构化且不含敏感信息的模型可见错误：

```json
{
  "ok": false,
  "error": {
    "code": "INVALID_ARGUMENT",
    "message": "训练日期不能重复",
    "retryable": true
  }
}
```

以下错误转成错误 ToolResult，并发出 `TOOL_FAILED` Trace 后继续框架原生循环：

- JSON Schema 输入错误；
- 参数映射或格式错误；
- 明确标记为模型可纠正的领域拒绝，例如未知目标部位、重复日期或计划内容不合法。

输入 Schema、参数映射和 Tool handler 明确抛出的参数 `IllegalArgumentException` 会被包装为 `ToolInputException`。输出 Schema 校验发生在该包装边界之后，输出不符合契约仍属于终止性的内部错误，不能伪装成模型可纠正输入。

以下错误仍终止 Run：

- 缺少可信上下文或 required scope；
- 用户审批、拒绝或审批状态错误；
- Hook fail-closed；
- Tool/Run 调用预算耗尽；
- 超时、中断、Provider/数据库不可用、输出 Schema 或内部协议错误。

AgentScope 使用原生 `ToolResultBlock.error(...)`。SAA ToolCallback 返回同一结构化 JSON，使 `ToolResponseMessage` 进入下一模型回合。错误调用计入现有总 Tool 调用预算和单 Tool 调用预算，防止无限循环；不增加额外隐藏重试。

### SAA endpoint 与错误诊断

SAA 将 Provider endpoint 拆成 origin、已有路径前缀和 completions path：

- endpoint 已以 `/v1` 结尾时，使用 `${existingPath}/chat/completions`；
- endpoint 不含版本路径时，沿用 OpenAI 默认 `/v1/chat/completions`；
- 保留 HTTPS、host 和已有非版本路径，不按 Provider key 写分支。

例如 `https://dashscope.aliyuncs.com/compatible-mode/v1` 生成：

`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`

Failure mapper 遍历 cause chain，识别 Spring HTTP 客户端异常和状态码。前端只收到安全分类，例如 `Provider request failed (HTTP 404)`；服务端 Trace 保存错误类别与 HTTP 状态，不保存响应正文或凭据。

## 数据流

1. 模型根据 Tool Schema 产生 Tool Call。
2. Adapter 校验 JSON Schema，并调用 Handler 的无副作用参数校验。
3. 校验失败时生成结构化错误 ToolResult；模型在原生循环下一回合修正参数。
4. 写 Tool 参数合法时才发出确认事件，运行时冻结排序后的参数并进入 `WAITING_APPROVAL`。
5. 用户批准后，运行时注入 `approvalId`，兼容清理旧 `scope`，调用当前 Tool handler 一次。
6. Fitness service 再次执行完整领域校验，并在现有单事务内保存全部训练日。

## 测试

- Tool Schema 测试：计划 `days` 为 1 到 31；`focusAreas` 不再限制 3 项，并拒绝超过已知集合容量的输入。
- Fitness service 测试：1、3、5 等不连续训练日可保存；重复日期、空集合、32 项和非法日期被拒绝；保存仍为单事务。
- 审批 Runtime 测试：坏计划参数不生成确认卡而是回到模型；合法参数只生成一张确认卡；旧含 `scope` 的审批仍可执行。
- AgentScope Adapter 合同测试：可纠正 Tool 错误产生 ERROR ToolResult，模型第二次调用可成功；权限、预算和基础设施错误仍 `RUN_FAILED`。
- SAA Adapter 合同测试：同等错误循环语义；Bailian/Minimax 风格 `/v1` endpoint 不重复版本路径；HTTP 错误映射保留安全状态信息。
- 运行相关 Maven 模块测试、Architecture Tests、Spotless 和 `git diff --check`。

## 发布影响

Tool 合约变化需要重新发布使用 `fitness.plan.save` 和候选动作 Tool 的 Agent 版本，才能让模型收到新 Schema。部署和发布不属于本次代码实施的默认授权范围，需另行确认。
