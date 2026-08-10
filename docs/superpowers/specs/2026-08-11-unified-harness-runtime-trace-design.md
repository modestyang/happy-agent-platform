# 统一 Harness 运行时与完整 Trace 设计

## 1. 背景与目标

当前仓库已经实现真实的 AgentScope 与 Spring AI Alibaba（SAA）Adapter，但正式 Agent 对话入口没有调用它们，而是直接通过 `StreamingChatClient` 请求 OpenAI-compatible `/chat/completions`。这导致 `frameworkKey` 只是一项展示配置，已绑定的 Skill、Tool、Hook 与 Memory 也没有在通用 Agent 中生效。

本次改造采用统一 Core 协议：

- 已发布 Agent 按 `frameworkKey` 选择真实 Harness。
- AgentScope Java 从仓库现有的 1.0.12 升级到 2.0.2，并以 `HarnessAgent`/`streamEvents()` 为真实执行入口。
- AgentScope 与 SAA 的消息、Block、Node 和生命周期差异只存在于各自 Adapter 内。
- `agentbuilder-core` 定义框架无关、强类型、易读的运行上下文、记忆、回复块和 Trace 事件。
- 同一套运行服务同时服务管理调试台与健身应用，避免两套 Agent 语义继续漂移。
- Trace 能完整还原一次 Run 的上下文装配、Skill 加载、模型回复、思考块、Tool 出入参、Hook、Memory 和结束状态。

本项目仍是个人应用，不引入分布式编排、跨服务追踪、向量记忆、多租户权限或框架市场。

## 2. 核心原则

### 2.1 Core 拥有语义，Adapter 消化差异

Core 不引用 AgentScope、SAA 或 Spring AI 类型。Adapter 将框架原生输出翻译成统一协议：

```text
Published Agent Snapshot
          │
          ▼
AgentRunContextAssembler
          │ RunRequest
          ▼
AgentFrameworkRegistry ── frameworkKey ──┬─ AgentScopeAdapter
                                         └─ SpringAiAlibabaAdapter
                                                    │
                                                    ▼
                                             Flux<RunEvent>
```

管理页面只展示实际注册且可运行的框架。配置可选即代表生产运行链路可用，禁止再出现“配置显示 AgentScope、实际直接 HTTP 调模型”的情况。

### 2.2 发布快照是一次 Run 的唯一配置来源

运行开始后只读取 Agent 的不可变发布版本。快照包含：

- Framework、Provider、Model 与凭据版本。
- 完整系统提示词。
- Tool 描述、Schema、风险和确认策略。
- Skill 的 Key、版本、说明、正文、资源披露策略和依赖 Tool。
- Hook 定义与顺序。
- Memory 策略、模型参数和运行限制。

运行过程中不再回读可变草稿。Trace 记录每项实际使用的版本。

### 2.3 Trace 记录事实，不推测框架行为

- Provider 或框架显式返回的思考内容记录为 `ThinkingBlock`。
- SAA 中 `reasoningContent` 优先识别为思考块。
- OpenAI-compatible Provider 把思考混在 `<think>` 中时，只允许 Adapter 的流式分类器识别并分块。
- SAA 的普通文本即使随后出现 ToolCall，也只能标记为 `TextBlock(phase=PRE_TOOL)`，不能在缺少显式依据时冒充 `ThinkingBlock`。
- Core 不识别厂商标签，也不把系统生成的执行摘要伪装成模型思考。
- Adapter 无法区分思考与正文时不伪造块，而是发出 `CapabilityDegraded(REASONING_UNAVAILABLE)` Trace 事件。

### 2.4 两级归一化

同一 Harness 下的不同模型服务商仍可能返回不同结构。框架的 Model/ChatModel 实现负责第一层协议归一化，Harness Adapter 负责第二层 Core 归一化：

```text
Provider raw stream
      │
      ▼
Framework model connector
AgentScope Model / Spring AI ChatModel
      │ framework-native message
      ▼
Framework response normalizer
      │ Text / Thinking / ToolCall / ToolResult / Media
      ▼
Core RunEvent + ResponseBlock
```

Provider 标准差异优先由框架官方 Connector 处理，例如 SAA 分别使用 OpenAI `ChatModel` 或 DashScope `ChatModel`。框架没有透出的扩展字段，只能在对应 Adapter 的 response normalizer 中按“实际字段能力”处理，不能在 Core 按 Provider Key 写分支。

OpenAI-compatible 扩展采用协议能力识别：看见明确 `reasoning_content`、`reasoningContent` 或 `<think>` 才解析；没有则透明降级。这样手动新增兼容 Provider 不需要发布代码，也不会因为 Provider 名称未知而走错误逻辑。

## 3. 模块边界

### 3.1 `agentbuilder-core`

只放稳定、框架无关的领域契约：

- `RunRequest`：一次 Harness 执行需要的完整输入。
- `RunContext`：系统指令、当前输入、会话历史、记忆和能力上下文。
- `ConversationMessage` 与 `MemorySnapshot`：有角色、有来源、有 Token 预算的记忆模型。
- `AssistantReply`：一次用户请求对应的完整助手消息，可由事件流确定性重建。
- `ResponseBlock`：统一回复内容块。
- `RunEvent`：统一 Run、Node、Response、Block、Skill、Tool、Hook、Memory 生命周期。
- `AgentFrameworkAdapter` 与 `AgentFrameworkRegistry`。
- `ToolExecutionOutcome`：成功、待确认、拒绝和失败的统一结果。

Core 不负责 JDBC、JSON、SSE、Spring Bean 或具体框架对象。

### 3.2 `agentbuilder-service`

新增统一应用编排服务，职责保持单一：

1. 加载已发布快照。
2. 解析当前会话与用户输入。
3. 按 Memory 策略构建 Token 有界的 `RunContext`。
4. 解析真实 Tool、Skill 与 Hook。
5. 从 Registry 选择 Adapter 并执行。
6. 将统一 `RunEvent` 交给 Trace Port。
7. 在终态保存最终助手消息与会话记忆。

它不直接调用模型，不包含 AgentScope/SAA 分支，也不依赖 fitness schema。

### 3.3 Framework Adapter

- `agentscope-adapter`：基于 AgentScope Java 2.0.2 创建 `HarnessAgent`，消费 `streamEvents()`，翻译原生 `ContentBlock` 与 `AgentEvent`。
- `spring-ai-alibaba-adapter`：创建 SAA `ReactAgent`，消费 `Flux<NodeOutput>` 与 `StreamingOutput.outputType`，翻译 Spring AI Message、ToolCall、ToolResponse 与 metadata。

两个 Adapter 都只依赖 Core SPI，不读数据库、不创建业务 Tool、不写 Trace 表。

AgentScope Java 2.0.2 已有 Harness、Context Compaction、Memory、Permission、Skill 与 Middleware 能力，Adapter 应通过配置和 SPI 使用这些设施，不在 Core 重造一套 AgentScope 内部执行器。Core 只保留跨框架都需要稳定表达的产品语义、发布快照、持久化和统一 Trace。

### 3.4 `agentbuilder-infrastructure`

- 发布快照读取与解密。
- Conversation/Memory 读取和写入。
- 强类型事件的 JSON 编码与 Trace/SSE 持久化。
- Tool Registry 到 Core `ResolvedTool` 的解析。

现有 `agent_run_events.payload` 和 `agent_run_stream_events.payload` 已能保存结构化事件，本次不新增 Trace 表。

### 3.5 `starter`

只负责 Spring 装配与 HTTP 边界：

- 将两个 Adapter 注册为 Bean。
- 创建 `AgentFrameworkRegistry`。
- 将健身 Tool、Hook 等实现贡献给运行时。
- Controller 调用统一运行服务。

`starter` 不再按 Agent Key 编写模型调用分支。

## 4. 上下文工程

### 4.1 强类型 RunContext

`RunRequest` 不再用 `List<String>` 表示记忆，也不允许 Adapter 自己拼系统提示词。核心结构为：

```text
RunContext
├─ IdentityContext
│  ├─ runId
│  ├─ conversationId
│  ├─ userId
│  └─ agentKey / agentVersion
├─ InstructionContext
│  └─ published system prompt
├─ ConversationContext
│  ├─ bounded history messages
│  └─ current user message
├─ MemorySnapshot
│  ├─ retained entries
│  ├─ omitted message count
│  └─ token budget / estimated tokens
└─ CapabilityContext
   ├─ resolved tools
   ├─ published skills
   └─ ordered hooks
```

### 4.2 装配顺序

上下文按固定优先级进入模型：

1. 已发布系统提示词。
2. 强制安全 Hook 产生的上下文。
3. Always-included Skill 内容与资源。
4. 会话记忆，从最新消息向前装入。
5. 当前用户输入。
6. On-demand Skill 元数据与加载工具。
7. Tool Schema。

Token 超限时只裁剪较老的会话消息；系统指令、安全上下文、当前输入和已加载 Skill 不被静默裁剪。每次裁剪都产生 `ContextAssembled` 事件，记录保留/省略数量和估算 Token。

`ContextAssembled` 的 payload 同时保存该 Node 实际提交给模型的消息列表、角色、系统指令、已加载 Skill 内容、Tool Schema 与裁剪结果。凭据和可信执行权限只记录引用及脱敏值，不进入 Trace。

## 5. 记忆管理

本轮只实现个人应用需要的会话短期记忆：

- 每个用户与 Agent 维持一个 24 小时活动 Conversation。
- 按 Agent 的 Memory `maxTokens` 加载有界历史，而不是固定 20 条字符串。
- 历史消息保留 `SYSTEM`、`USER`、`ASSISTANT`、`TOOL` 语义；传给 Harness 前转换成中立 Message。
- 一次 Run 成功后保存最终 Assistant 文本；Tool 中间结果保留在 Trace，不进入用户可见对话，但可作为下一轮模型上下文的一部分。
- 下一轮需要 Tool 历史时，Memory 组件从上一 Run 的已完成 Tool 事件重建中立 `TOOL` Message；不把 Tool JSON 混入用户可见消息文本。
- Conversation 过期或用户新建会话后，不再加载旧上下文。

不增加向量检索或跨 Conversation 长期画像；健身用户档案继续通过授权 Tool 读取，避免复制业务事实到 Agent schema。

## 6. 统一 Message/Event 协议

### 6.1 AssistantReply

借鉴 AgentScope Python 2.0.6 的 Message/Event 分层，Core 将完整消息与流式事件定义为同一事实的两种视图：

- `AssistantReply` 是持久化、会话回放和最终消息气泡的单位。
- `RunEvent` 是实时 UI、Trace 和断线续传的增量单位。
- 同一 `replyId` 下的有序事件必须能够确定性重建完整 `AssistantReply`，禁止另存一份独立拼装且可能不一致的最终回复。
- 一次 Reply 可以包含多轮 Thinking、ModelCall、ToolCall、ToolResult 和最终 Text；它们保持原始顺序。

`AssistantReply` 保存 `replyId`、Agent、Role、按序排列的 Block、创建/结束时间、累计 Usage、结构化结束原因和安全错误信息。等待用户确认时 Reply 处于 `SUSPENDED`，不是伪装成已结束；确认后继续使用相同 `replyId`。

### 6.2 ResponseBlock

Core 定义 sealed `ResponseBlock`：

- `TextBlock`：面向用户的最终文本。
- `ThinkingBlock`：模型或框架显式返回的思考内容，默认折叠展示。
- `ToolCallBlock`：调用 ID、Tool Key、增量或完整参数。
- `ToolResultBlock`：调用 ID、Tool Key、结构化结果、是否错误。
- `MediaBlock`：图片、音频或视频引用及 MIME Type。
- `HintBlock`：框架或应用明确注入的非用户提示，例如 Skill 加载提示；必须携带来源，不能冒充 User Message。

每个 Block 都有稳定 `blockId`、`responseId`、顺序和来源，不以文本内容作为身份。

Block 同时记录 `fidelity`：

- `NATIVE`：框架原生类型，例如 AgentScope `ThinkingBlock`。
- `PROVIDER_METADATA`：Provider/Spring AI metadata 明确给出的类型，例如 `reasoningContent`。
- `PROVIDER_MARKUP`：Provider 明确协议标签解析所得，例如跨 chunk `<think>`。

不定义 `INFERRED_THINKING`。无法可靠识别时宁可缺少 Thinking Block，也不制造错误 Trace。

ToolCall 状态统一为 `PENDING`、`ASKING`、`ALLOWED`、`SUBMITTED`、`FINISHED`；ToolResult 状态统一为 `RUNNING`、`SUCCESS`、`ERROR`、`INTERRUPTED`、`DENIED`。ToolCall 与 ToolResult 使用同一个 `toolCallId` 关联。

### 6.3 生命周期

一次完整回复的标准顺序为：

```text
ReplyStarted
  ModelCallStarted
    BlockStarted
    BlockDelta *
    BlockCompleted
  ModelCallCompleted
  ToolCall / ToolResult / UserConfirmation
  ModelCallStarted ...
ReplyEnded
```

每个流式 Block 都遵守 `Started → Delta* → Completed`。ToolCall 参数允许以 JSON 片段增量到达，但只有 `ToolCallBlockCompleted` 后才允许校验和执行。

`ModelCallCompleted.finishReason` 统一为：

- `STOP`
- `TOOL_CALLS`
- `MAX_TOKENS`
- `CONTENT_FILTER`
- `CANCELLED`
- `ERROR`
- `UNKNOWN`

一次 Reply 可以包含多次 ModelCall：模型提出 ToolCall 后该 ModelCall 以 `TOOL_CALLS` 结束；Tool 执行完成后 Harness 开始下一次 ModelCall。只有最终文本产生并且 Harness 正常结束时才发出 `ReplyEnded(COMPLETED)` 与 `RunCompleted`。

### 6.4 三层结束语义

- `ModelCallCompleted`：单次模型请求结束，不代表助手回复结束。
- `ReplyEnded`：完整助手回复以 `COMPLETED`、`INTERRUPTED`、`EXCEED_MAX_ITERS` 或 `ERROR` 结束；等待确认只挂起，不发结束事件。
- `RunCompleted` / `RunWaitingApproval` / `RunFailed` / `RunCancelled`：本次用户请求结束或挂起。
- Conversation 不随 Run 结束；它按 Memory 策略继续存在。

## 7. Adapter 翻译规则

### 7.1 AgentScope

当前仓库使用的 AgentScope Java 1.0.12 仅作为迁移起点，不再作为目标 API。目标依赖为 Maven Central 已发布的 AgentScope Java 2.0.2 `agentscope-core` 与 `agentscope-harness`；AgentScope Python 2.0.6 文档只作为 Message/Event 语义参考，不能把 Python 类和方法照搬进 Java Adapter。Maven Central 当前没有 AgentScope Java 2.0.6 artifact。

Java Adapter 使用 `HarnessAgent` 而非自行拼装旧版 `ReActAgent` 循环，并消费 `streamEvents()` 的强类型事件。原生 Text、Thinking、Data、ToolUse、ToolResult、Hint、模型调用、确认与 Agent 终态分别映射到 Core 对应事件；不能再调用纯文本辅助方法把 Block 压平。

- 原生 Reply/Message/Block/ToolCall 关联 ID 存在时原样保留，缺失时由 Adapter 创建稳定 ID。
- AgentScope 原生 Block 与事件优先作为 `NATIVE` 映射；只有官方 Connector 未分类但仍有明确协议标记时才进行降级解析。
- `HarnessAgent` 的 Permission/HITL 状态映射为统一 ToolCall 状态和 `RunWaitingApproval`，恢复执行时保持同一 `replyId` 与冻结参数。
- AgentScope Context Compaction、Memory、Skill 和 Middleware 的关键阶段通过 Adapter/Middleware 发出 Core Context、Memory、Skill、Hook Trace，不复制其内部执行逻辑。
- ToolUse/ToolResult 的参数与输出仍须通过 Core 发布快照中的 Tool Schema 和可信上下文边界。

### 7.2 SAA

SAA Adapter 改用 `ReactAgent.stream(...): Flux<NodeOutput>`。根据 `StreamingOutput.outputType` 翻译：

- `AGENT_MODEL_STREAMING`：模型流式增量。
- `AGENT_MODEL_FINISHED`：本轮模型输出与 ToolCall 完成。
- `AGENT_TOOL_FINISHED`：ToolResponse 完成。

思考识别采用以下优先级：

1. `AssistantMessage.metadata["reasoningContent"]` 非空：直接生成 Thinking Block 增量。
2. Provider 返回显式 reasoning metadata：由 SAA Adapter 的 Provider 分类器映射。
3. OpenAI-compatible 文本中的 `<think>`：由支持跨 chunk 的状态机拆成 Thinking/Text。
4. 都不存在时：普通文本始终是 Text Block；若同一轮随后出现 ToolCall，只增加 `phase=PRE_TOOL`，不改变 Block 类型。

SAA 1.1.2.2 当前存在 `AGENT_MODEL_STREAMING` 无法稳定区分思考与最终答案的已知问题。Adapter 必须显式报告 `REASONING_UNAVAILABLE` 降级，前端显示“当前框架/模型未提供可区分的思考块”，不能把整个文本当思考或隐藏最终答案。

ToolCall 从 `AssistantMessage.getToolCalls()` 生成 ToolCall Block；Tool 结果从 `ToolResponseMessage.getResponses()` 生成 ToolResult Block。`Generation.metadata.finishReason` 优先映射统一结束原因，缺失时根据 ToolCall 和 OutputType 推断。

`AssistantMessage.getMedia()` 仅在响应确实携带媒体时映射 Media Block。多数 SAA 模型的 media 主要用于输入侧；没有输出媒体时不创建空 Block。

## 8. Skill、Tool 与 Hook

### 8.1 Skill

- Run 启动时发出 `SkillDiscovered`，记录绑定 Key 与版本。
- Always-included Skill 进入上下文时发出 `SkillLoaded`，记录实际注入内容摘要和资源列表。
- On-demand Skill 被框架加载时发出 `SkillLoaded`，记录加载原因和对应 Node。
- Skill 正文必须来自已发布快照；手动编辑后只有重新发布 Agent 才影响运行。

因此 `food-recomend` 的“只推荐用户水果”会作为真实 Skill 内容进入所选 Harness，而不再只是后台展示字段。

### 8.2 Tool

- ToolCall 参数以流式 Block 累积，完成后做 JSON Schema 校验。
- `ToolStarted` 保存完整模型参数和可信上下文引用，但不把 userId、权限或凭据伪装成模型参数。
- `ToolCompleted` 保存结构化输出与耗时。
- `ToolFailed` 保存错误类型和安全错误信息。
- 需要确认的写 Tool 产生 `RunWaitingApproval`；确认后只能执行已冻结的同一 Tool 参数。

### 8.3 Hook

每个 Hook 发出 `HookStarted` 与 `HookCompleted`；失败时记录 `HookFailed` 和 failure policy。强制 Hook 的 `FAIL_CLOSED` 失败直接终止 Run，非强制 Hook 可继续但 Trace 必须可见。

## 9. Trace 事件模型

`RunEvent` 改为强类型 payload，公共信封统一包含：

- `runId`
- `sequence`
- `occurredAt`
- `frameworkKey`
- 可选 `nodeId`
- 可选 `responseId`
- 可选 `blockId`
- `payload`

事件族：

- Run：Started、WaitingApproval、Completed、Failed、Cancelled。
- Reply：Started、Suspended、Ended。
- ModelCall：Started、Completed。
- Node：Started、Completed、Failed。
- Context：Assembled。
- Memory：Loaded、Saved。
- Skill：Discovered、Loaded、Failed。
- Hook：Started、Completed、Failed。
- Block：Started、Delta、Completed。
- Tool：Started、Completed、Failed。
- HITL：ConfirmationRequired、ConfirmationReceived。

Persistence 层负责把强类型 payload 编码进现有 JSONB。查询 Trace 时返回 payload，不再只返回 title/detail。现有非空 `title` 和 `detail` 列保留为由事件类型生成的简短查询投影，JSONB payload 是完整事实来源，避免两份独立语义发生冲突。

## 10. SSE 与前端展示

SSE 使用同一统一事件，不再维护另一套不一致的 `TEXT_DELTA` 私有协议。前端 reducer 按 `responseId + blockId` 聚合：

- Text Block 进入 Markdown 回复正文。
- Thinking Block 进入默认折叠的“思考过程”。
- ToolCall/ToolResult 形成调用卡片，展示完整出入参和状态。
- Context、Memory、Skill、Hook 与 Node 进入默认折叠的执行过程。
- Run 终态控制输入框、确认卡和错误提示。

Trace 页面与实时聊天复用同一个事件解释器，避免实时显示和历史 Trace 对同一事件产生不同含义。

## 11. 错误处理

- 未注册 `frameworkKey`：发布校验失败；历史已发布版本运行时返回明确依赖不可用。
- Adapter 不支持已绑定能力：发布校验失败，不在运行时静默忽略。
- 无法识别的原生 Block：记录 Adapter 映射错误并使 Run 失败，不把未知内容错误显示成普通文本。
- SSE 断开不取消 Run；事件已持久化，可通过 Last-Event-ID 恢复。
- Provider 错误、Tool 错误、Hook 错误、预算超限与用户取消使用不同失败码。

## 12. 验证范围

### 12.1 Core 契约

- 生命周期顺序与 ID 关联。
- 同一事件流可确定性重建完整 `AssistantReply`。
- 单个 Reply 内的多次 ModelCall 与 Tool 循环。
- 用户确认挂起/恢复保持同一 replyId 与冻结 Tool 参数。
- Token 有界上下文和记忆裁剪。
- Skill 内容与版本进入上下文。

### 12.2 Adapter 契约

- AgentScope Java 2.0.2 `HarnessAgent.streamEvents()` 的原生 Block/Event 到 Core 的映射。
- SAA `reasoningContent`、普通 Answer、ToolCall 前推理、ToolResult 和结束原因映射。
- SAA 无显式 reasoning 时只输出 Text Block，并产生可见降级事件。
- `<think>` 跨 chunk 状态机。
- 两个 Adapter 对同一脚本化 Agent 产生语义等价的统一事件。
- 同一 Adapter 分别接收标准 OpenAI、带 reasoning 扩展、无 reasoning、分片 ToolCall 和多模态 Message，验证模型服务商差异不会泄漏到 Core。

### 12.3 运行时

- 同一个已发布 Agent 分别选择 AgentScope 与 SAA，均真实执行对应 Adapter。
- `food-recomend` 使用“只推荐用户水果”Skill 后只返回水果建议。
- Tool 出入参、Skill 加载、上下文和思考块都可在 Trace 中查看。
- 健身计划写 Tool 仍经过用户确认，确认后执行冻结参数。

### 12.4 页面问题点验证

- 调试台分别运行 AgentScope Agent 和 SAA Agent。
- 实时回复块与历史 Trace 内容一致。
- 思考过程默认折叠，Markdown 正常，工具卡片显示正确。

## 13. 参考

- [AgentScope Python 2.0.6 Message & Event](https://docs.agentscope.io/versions/2.0.6/en/building-blocks/message-and-event)：完整 Message、流式 Event、Block 状态与事件重建语义；本项目只借鉴中立协议，不使用其 Python API。
- [AgentScope Java 2.0 发布说明](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.2/docs/v2/en/docs/others/release-notes.md)：`HarnessAgent`、`ContentBlock`、`streamEvents()`、Context Engineering、Permission、Memory、Skill 与 Middleware 能力。
- 用户提供的 SAA `StreamTest` 示例：`reasoningContent`、Turn Buffer、`NodeOutput`/`OutputType` 分流。
- [Spring AI Alibaba 官方仓库](https://github.com/alibaba/spring-ai-alibaba)：Agent Framework、Graph、Context Engineering 与 streaming 的职责说明。
- [Spring AI Alibaba v1.1.2.2 release](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2)：完整 streaming node `_FINISHED` OutputType 与当前依赖版本能力。
- [SAA Messages 官方文档](https://java2ai.com/docs/frameworks/agent-framework/tutorials/messages/)：`AssistantMessage` 的 text、metadata、toolCalls、media 与独立 `ToolResponseMessage`。
- [SAA issue #4649](https://github.com/alibaba/spring-ai-alibaba/issues/4649)：1.1.2.2 `AGENT_MODEL_STREAMING` 阶段无法稳定区分思考与最终答案。
- [阿里云百炼深度思考文档](https://help.aliyun.com/zh/model-studio/deep-thinking)：OpenAI-compatible 流中的 `reasoning_content` 与 `content` 分离语义。
