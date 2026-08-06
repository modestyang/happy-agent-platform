# Agent 管理工作台可运行切片设计

日期：2026-08-06

## 目标

在现有模块化单体中补齐一个可真实操作的 Agent 管理工作台。它服务于自用部署：同一 Spring Boot 进程、同一 PostgreSQL 数据库的 `agent` schema、同一 React 前端，通过 `/admin` 管理 Agent 草稿、组件绑定与 Provider 凭据。页面沿用已确认 demo 的桌面视觉语言，但不复制 demo 数据层。

同时修正健身计划卡片：动作图片区与右侧信息区在每张卡片内上下边缘对齐，图片内容居中且不拉伸。

## 方案比较与选择

### A. 只移植 demo 页面

速度快，但所有数据仍是假数据，无法配置百炼、保存 Agent 或验证重启持久化。与正式版本要求冲突，不采用。

### B. 一次实现完整 OpenAPI 控制面

覆盖评测队列、回滚、SSE Trace、审批与复杂 RBAC，最终能力最全，但会把当前任务扩大成剩余整个平台，无法尽快给出可操作版本。

### C. 可运行管理主链（采用）

实现真实数据库草稿、组件目录、Provider 密钥、配置校验、发布快照、运行/Trace 只读区和 Playground 状态。未接通的运行能力明确不可用。该方案可立即操作，又保持数据模型和接口可继续扩展到完整 OpenAPI。

## 信息架构

- 总览：Agent 数、可用组件、Provider 配置状态、最近运行；突出当前阻塞项。
- Agent：选择 Agent、编辑基本信息与 Framework/Provider/Model/Prompt，绑定 Tools/Skills/Hooks，配置温度和最大 Tool 调用数，保存草稿、校验并发布。
- 组件库：按 Tool、Skill、Hook、Framework、Provider、Model、Memory、Prompt 分类检索；查看名称、用途、版本、状态、风险/能力和依赖信息。
- Provider：显示百炼 Endpoint、掩码凭据、配置状态；API Key 只写入，保存后不回显。
- 运行记录：读取持久化 Run 与 Trace 事件；没有运行时显示真实空状态。
- Playground：复用当前 Agent 草稿；Provider 或 Runtime 未配置时给出明确阻塞原因，不生成伪回复。

## 后端边界

- `agentbuilder-service` 定义 `AdminWorkbenchService`、命令/DTO 和 `AdminWorkbenchPort`。
- `agentbuilder-infrastructure` 用 `JdbcAdminWorkbenchStore` 实现持久化和凭据加密。
- `starter` 只放薄 Controller、Bean 配置和登录会话门禁。
- 新迁移创建 Agent 草稿、Provider 凭据、组件投影、Run 与 Trace 表；强类型组件表仍是正式组件域，投影只承担管理台检索读模型。
- 发布时验证 Framework、Provider、Model 与 Prompt 已选择，Provider 已配置，所有绑定组件处于可用状态；成功后向 `agent_versions` 写入完整不可变快照。

## API

- `GET /api/admin/workbench`：返回总览、Agent、组件、Provider 状态和最近运行。
- `PATCH /api/admin/agents/{agentKey}/draft`：按 revision 乐观更新草稿，响应返回新 revision。
- `POST /api/admin/agents/{agentKey}/validate`：返回错误和警告，不修改数据。
- `POST /api/admin/agents/{agentKey}/publish`：校验后写入新版本快照。
- `PUT /api/admin/providers/{providerKey}/credential`：保存加密 API Key，只返回 `configured=true` 与掩码。
- `GET /api/admin/runs/{runId}`：读取 Run 和 Trace。

所有端点要求现有登录会话。错误使用稳定 code；并发草稿更新返回 409。Provider 响应永不包含明文、密文、IV 或主密钥路径。

## 本地种子与安全

`local-seed` 只写入可辨识的本地测试组件、一个“瘦瘦健身教练”草稿和空运行列表。Provider 初始为未配置，不写入假 API Key。主密钥由 `deploy/scripts/generate-secrets.sh` 生成到 git-ignore 的 `deploy/secrets/agent-master-key`，数据库仅保存 AES-256-GCM 密文、IV 和组件绑定 AAD。

## 前端视觉与交互

管理台使用浅蓝灰背景、白色大圆角容器、76px 窄侧栏、海军蓝正文和蓝/绿/琥珀语义色。避免紫色 AI 渐变、玻璃叠层和密集徽章。1440px 下主内容为 12 栏；低于 1024px 收缩为单列卡片。

表单默认只展示高频配置，Tools/Skills/Hooks 和模型参数放在“高级配置”。保存状态、校验阻塞和发布结果在页面内反馈；密钥字段关闭自动填充且保存后清空。

## 测试与验收

- 后端 Testcontainers：种子读取、草稿乐观更新、凭据不泄漏、发布阻塞与成功、重启后持久化。
- 前端 Vitest：`/admin` 路由、导航、真实 fetch、草稿保存、组件筛选、未配置 Provider 状态。
- 浏览器：390px 计划卡片等高；1280/1440px 管理台总览、Agent、组件、Provider、运行记录逐页检查，无横向溢出和控制台错误。
- 完整执行前端 test/lint/build 和 Maven verify。
