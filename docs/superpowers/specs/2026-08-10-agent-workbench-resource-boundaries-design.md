# Agent 工作台独立资源与可确认 Tool 设计

日期：2026-08-10  
状态：用户已确认实施  
范围：个人应用本地 Agent 工作台

## 目标

修正工作台当前四个结构性问题：

1. 模型看不到已绑定 Tool，训练计划保存依赖服务端文本启发式而不是真实 Tool Call。
2. 所有页面依赖 `/api/admin/workbench` 全量快照，任一坏数据会扩大为整个工作台故障。
3. Agent、Provider、Model、Prompt、Tool、Skill、Hook 等被压平为 `ComponentView(type, config)`，丢失独立业务边界。
4. 关键安全与数据边界缺少维护说明。

同时让 OpenAI-compatible Provider 和其模型可由工作台新增、编辑、启用和停用，不再依赖代码发版。

## 明确不做

- 不增加多租户、组织权限、供应商市场、远程模型发现或企业审批流。
- Provider、Model 不提供物理删除，只允许停用。
- 不让模型获得可信身份字段或 `fitness.write` scope。
- 不保留双写、长期兼容层或另一套全量聚合接口。

## 资源边界

工作台业务层和前端不再出现通用 `WorkbenchComponent`、`ComponentView`、`ComponentUpdate`、`ComponentType` 或 `/components/{type}`。改为独立资源：

- `AgentDefinition`
- `ProviderDefinition`
- `ModelDefinition`
- `PromptDefinition`
- `ToolDefinition`
- `SkillDefinition`
- `HookDefinition`
- `FrameworkDefinition`
- `MemoryDefinition`

可以共享分页、状态、校验等基础实现，但 DTO、服务方法、Repository 查询、Controller 路由和页面状态必须按资源命名。旧版本目录内部的 `ComponentKey` 等底层值对象不再被工作台引用；本轮不扩大到删除未使用的历史 Catalog 子系统。

## 数据模型

Agent schema 使用简单、面向个人应用的独立表：

### Provider

`agent_providers`

- `provider_key` 主键
- `display_name`
- `endpoint`
- `protocol`，首版固定 `OPENAI_COMPATIBLE`
- `status`：`ACTIVE` / `DISABLED`
- `revision`
- 创建与更新时间

凭据继续使用现有 `agent_provider_credentials`，以 Provider Key 作为 AAD 身份并使用 AES-256-GCM 加密。读取 API 永不返回明文。

### Model

`agent_models`

- `model_key` 主键
- `provider_key` 外键到 `agent_providers`
- `model_id`：实际发送给 OpenAI-compatible API 的模型名
- `display_name`、`description`
- `supports_streaming`、`supports_tool_calling`、`supports_vision`
- `status`：`ACTIVE` / `DISABLED`
- `revision`
- 创建与更新时间

同一 Provider 下 `model_id` 唯一。Agent 选择 Provider 后只能选择该 Provider 下状态为 ACTIVE 的模型。发布校验和运行时继续双重验证 Provider–Model 归属。

### Prompt、Skill、Hook、Framework、Memory

分别使用独立表保存当前工作台所需字段。Skill 保存逻辑文本和 required tool keys；Hook 保存 phase、mandatory、runtime readiness；Prompt 保存模板；Framework 和 Memory 保存各自明确配置。JSON 只用于天然的列表或扩展参数，不用作隐藏业务字段的通用垃圾桶。

### Tool

Tool 定义不由管理员手写，也不使用数据库种子。`SpringToolCatalogScanner` 每次启动扫描 `@AgentTool`，工作台直接读取不可变的运行时 Tool 清单：key、版本、名称、说明、input/output schema、风险、副作用、幂等、scope 和运行时状态。这样新增代码 Tool 后不会再漏出控制台。

旧 `agent_component_projection` 停止被生产代码读写。现有本地数据库暂不主动 DROP，避免不必要的数据破坏；最终空库 baseline 不再创建该表，也不再创建未被产品使用的旧通用 Catalog 表。

## API

移除前端对 `/api/admin/workbench` 的依赖。新增或实现以下小型资源 API：

- `GET /api/v1/admin/overview`
- `GET|POST /api/v1/admin/agents`
- `GET /api/v1/admin/agents/{agentKey}`
- `PATCH /api/v1/admin/agents/{agentKey}/draft`
- `GET|POST /api/v1/admin/providers`
- `GET|PATCH /api/v1/admin/providers/{providerKey}`
- `PUT /api/v1/admin/providers/{providerKey}/credential`
- `GET /api/v1/admin/providers/{providerKey}/models`
- `GET|POST /api/v1/admin/models`
- `GET|PATCH /api/v1/admin/models/{modelKey}`
- 分别提供 Prompt、Tool、Skill、Hook、Framework、Memory 的 list/get/update 路由；Tool 只读。
- `GET /api/v1/admin/playground/agents` 只返回可调试 Agent 及就绪原因。

列表中某一条坏数据只影响该资源请求；Repository 对单行 JSON/字段损坏返回明确 Problem，不再阻断其他资源页面。

## 模型可见 Tool 与确认执行

发布快照保存 Agent 绑定的 Tool key 与合约版本。运行时从 `ToolRegistry` 解析为 OpenAI Tool Schema，并随流式 chat completion 请求发送给模型。

### READ Tool

模型发起 Tool Call 后：

1. 累积并解析流式 `tool_calls` 参数。
2. 按 Tool input schema 校验。
3. 使用只读 scope 执行。
4. 将 Tool result 追加到消息，再继续模型流式响应。

### WRITE Tool

`fitness.plan.save` 绑定给 `fitness.coach`，模型能够看到名称、用途和参数，但运行时绝不直接写库：

1. 模型发出 `fitness.plan.save` Tool Call。
2. 服务端校验参数、用户归属、日期、动作 ID 和 Tool contract version。
3. 以 `runId + toolCallId` 冻结参数并写入 approval，Run 进入 `WAITING_APPROVAL`。
4. SSE 输出确认卡，UI 默认不执行。
5. 用户确认后，服务端读取冻结参数，用 `operationId=approval.execute` 和 `fitness.write` 调用同一个 Tool handler。
6. 拒绝则取消写入；重复确认依靠 approval 和 Tool 幂等键返回同一结果。

模型参数中禁止出现 userId、runId、permissions、operationId 等可信字段。现有按文本猜测训练计划意图的启发式逻辑从主路径移除。

## 页面

- Overview 只请求 overview 和近期 Run 摘要。
- Agent 列表只请求 Agent。
- Agent 编辑器并行请求单个 Agent 以及 Provider、当前 Provider 的 Model、Prompt、Tool、Skill、Hook、Framework、Memory；某一资源失败时只禁用对应区块并显示重试。
- Provider 页面提供新增表单、endpoint、密钥和停用操作。
- Model 页面提供新增表单，第一项必须选择 Provider；支持模型能力编辑与停用。
- Tool、Skill、Hook 使用独立页面与类型，不复用通用 Component JSON 编辑器。
- Playground 只请求可调试 Agent 列表，不加载所有目录。

沿用当前工作台视觉语言，不进行无关重设计。

## 注释与维护性

- 新增公开 Service、Port、Controller 和安全相关 DTO 使用 JavaDoc 说明职责与边界。
- Provider 凭据、Provider–Model 归属、Tool approval、发布快照不可变、trusted context 等非直观逻辑写 WHY 注释。
- 不写复述代码的逐行注释。
- 架构文档记录每个页面的数据源和 Tool 审批时序。

## Migration 策略

项目仍处开发期，Agent schema 最终只保留一份可从空库建立当前正确结构的 baseline migration：

1. 开发期间先以临时增量 SQL 验证数据迁移。
2. 将 Agent V1-V12 中仍被当前产品使用的结构和本轮独立资源结构合并为最终 `V1__agent_baseline.sql`；不把已废弃的通用投影和空置 Catalog 表带入新基线。
3. 删除 Agent V2-V12 文件；不触碰 Fitness schema migration。
4. Testcontainers 必须从空 PostgreSQL 仅依靠最终 V1 建库成功。
5. 本地数据库先备份；应用本轮增量后对齐 Flyway history，不删除业务数据和加密凭据。
6. 将“开发期 migration 只保留正确基线”写入仓库 `AGENTS.md`，避免再次累计。

## 测试与验收

- Contract fixtures 覆盖所有独立资源操作。
- Repository Testcontainers 覆盖迁移、Provider–Model 外键、停用与凭据掩码。
- Service 测试覆盖引用校验、Provider 切换模型过滤、停用发布门禁。
- Tool runtime 测试证明模型能看到 `fitness.plan.save`，未确认不能执行，确认后只执行一次。
- 前端测试证明各页面不再请求 `/api/admin/workbench`，单个资源失败不拖垮其他页面。
- 真实页面验收覆盖新增 Provider、新增 Model、Agent 联动选择、Tool 可见、训练计划确认保存和 Trace。
- 最后执行一次全量 Maven、前端测试、类型检查、Lint、构建、Spotless、契约 lint 与 `git diff --check`。
