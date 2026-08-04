# 模块边界与依赖规则

## 1. 运行单元

平台是模块化单体：唯一可执行模块为 `starter`，生产只有一个 JVM。`/app/**`、`/admin/**` 和 `/api/**` 同源；前端构建产物打入最终 JAR。一个 PostgreSQL 数据库通过 `fitness` 与 `agent` schema 隔离所有权，两个最多 3 连接且 `minimumIdle=0` 的 DataSource 分别授权。

## 2. 模块职责

| 模块 | 拥有 | 不得拥有 |
|---|---|---|
| `application/fitness/fitness-common` | Fitness 领域对象、值对象、命令、查询结果、错误和稳定公共契约 | Controller、持久化类型、Agent 类型 |
| `application/fitness/fitness-service` | 健身用例、事务入口、Repository/OSS/时钟/任务 Port | Spring MVC、SQL、Agent 编排 |
| `application/fitness/fitness-infrastructure` | `fitness` schema Repository、OSS、调度、`FitnessTools` Bean | 业务规则、跨 schema 查询 |
| `agentbuilder/agentbuilder-core` | 框架无关组件契约、Agent 定义/快照、会话、Run、Trace、审批、预算 | 业务应用依赖、具体框架或数据库类型 |
| `agentbuilder/agentbuilder-service` | 目录、默认解析、草稿、评测、发布、回滚、会话/Run 用例 | Controller、框架 SDK、SQL |
| `agentbuilder/agentbuilder-infrastructure` | `agent` schema、凭据加密、组件扫描、外部 HTTP/MCP Adapter | Fitness Repository、业务 schema 查询 |
| `agentbuilder-framework-adapter/*` | 把统一 spec/tool/skill/hook/event 转为单一框架 API | 控制面规则、业务应用依赖 |
| `starter` | 启动、Controller、全局异常、安全、配置、调度装配、静态资源、健康检查 | 核心业务规则 |
| `frontend` | 手机端和管理端 UI、由 OpenAPI 生成的 DTO | 服务端业务决策、任意模型 HTML 渲染 |

## 3. 允许依赖

```text
starter
  ├─ application/fitness/{common,service,infrastructure}
  └─ agentbuilder/{core,service,infrastructure,framework-adapter/*}

fitness-service ──> fitness-common
fitness-infrastructure ──> fitness-common + fitness-service ports
fitness-infrastructure.agent ──> agentbuilder-core component contracts

agentbuilder-service ──> agentbuilder-core
agentbuilder-infrastructure ──> agentbuilder-core + agentbuilder-service ports
agentscope-adapter ──> agentbuilder-core
spring-ai-alibaba-adapter ──> agentbuilder-core
```

## 4. 禁止依赖

- `agentbuilder-core`、service、framework adapter 不依赖任何 `application/*`。
- Agent 不直接调用 Fitness Repository，不查询 `fitness` schema。
- Fitness common/service 不依赖 starter、Controller 或 Agent Builder。
- 除 `fitness-infrastructure.agent` 对核心组件契约的窄依赖外，Fitness 不感知 Agent Builder。
- Framework adapter 不依赖具体业务应用，不创建控制面或业务规则。
- Controller 只位于 `starter.controller.fitness` 或 `starter.controller.agent`。
- 基础设施实现上层 Port，不向上层暴露 JPA、JDBC、OSS SDK 或框架 SDK 类型。
- 不创建跨 schema 外键、事务或业务 SQL。

Maven 模块图阻止编译期反向依赖，ArchUnit 对 Java 包依赖作第二道门禁。

## 5. 关键调用链

### 5.1 手机端请求

`starter.controller.fitness → fitness-service use case → fitness-service Port → fitness-infrastructure → fitness schema/OSS`

Controller 只完成认证主体映射、DTO 转换、header 校验和 HTTP 状态映射；事务始于 use case。

### 5.2 本地 Agent Tool

`Framework Adapter → ToolRegistry → FitnessTools Spring Bean → Fitness Application Service → Fitness Repository → fitness schema`

`FitnessTools` 使用服务端 `ToolExecutionContext` 中的用户、Run、权限和 operationId。模型参数不能覆盖这些字段。Agent 先在 `agent` schema 保存调用意图，Fitness 以 operationId 幂等写入，Agent 再保存结果；中断后可查询或重放而不重复业务写入。

### 5.3 外部 Tool

`Framework Adapter → ToolRegistry → HTTP/MCP Adapter → external system`

外部工具遵守同一审批、预算、Trace 和错误事件契约，但不获得数据库连接或内部 Repository。

### 5.4 框架适配

`AgentDraft/AgentVersion → FrameworkAgentSpec → Adapter Registry → selected adapter`

Registry 按 `frameworkKey` 选择 AgentScope 或 Spring AI Alibaba；未选择的 adapter 不创建模型客户端或线程。新增框架只增加新的 adapter 子模块。切换框架必须重新解析、评测和发布。

## 6. API 所有权

- `public-v1.yaml` 由 Fitness 外部边界拥有，包含手机端 DTO，以及手机端看到的 AI 会话/Run 视图。
- `admin-v1.yaml` 由 Agent 管理边界拥有，包含组件、默认档案、Agent、评测、发布、会话、Run、Trace 和 Playground DTO。
- 两份合同均可复用同名概念，但不能用 Java 内部类型作为 HTTP DTO。
- 新 Controller operation 必须先进入对应 OpenAPI、fixture 和生成类型；实现任务不能自行发明端点。

## 7. 事务、异步与一致性边界

- 每个用例事务只落在单一 schema。
- 写请求以 `(principal, Idempotency-Key)` 和请求摘要保存结果；相同键不同摘要为冲突。
- 版本化资源使用强 ETag；`If-Match` 缺失为 428，不匹配为 412。
- 餐食计划、图片识别、评测、探测和 Agent Run 都是持久化任务，使用 PostgreSQL 租约/条件更新恢复。
- SSE 只负责实时传输，事实源是持久化事件；客户端用事件 ID 续传。

## 8. 安全与配置边界

- Fitness 与 Admin 使用不同 scope；Controller 在调用用例前完成外部授权。
- Provider 密文只由 Agent infrastructure 加解密；主密钥来自只读 Secret 文件。
- OSS 优先 ECS RAM Role；仓库和 DTO 都不保存长期 AccessKey。
- 正式代码不包含 Fake Runtime、Fake Media 或“缺配置返回伪结果”的实现。
- Tool/Hook 代码契约在后台只读；管理端只能编辑绑定、默认档案和允许管理的类型化组件。
