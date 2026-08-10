# Happy Agent Platform implementation plan

## Goal

在正式项目中，以 `/Users/modest/IdeaProjects/fitness` 为只读视觉参考，完成 Today、Plans、瘦瘦、Exercises、Profile 五个移动端页面的结构、视觉与交互升级；保持正式后端 API 和数据链路不被 demo 逻辑替换。

## Phases

1. [complete] 审计正式实现、demo 参考与现有接口边界。
2. [complete] 先补充会失败的页面行为测试，覆盖五个 Tab 和关键交互。
3. [complete] 统一字体、暖色视觉、图标引导、卡片与 C 位 AI 导航。
4. [complete] 按需求重构首页、计划、瘦瘦、动作、我的页面。
5. [complete] 运行单测、类型检查、Lint、构建和浏览器逐页验收。
6. [complete] 记录验收证据、遗留依赖与交付方式。

## Decisions

- 正式项目路径：`/Users/modest/IdeaProjects/happy-agent-platform`。
- demo 路径只读：`/Users/modest/IdeaProjects/fitness`。
- 保留正式项目的真实 API、认证、PostgreSQL 持久化和 Agent 配置机制。
- 视觉方向：暖奶油底色、果汁暖色卡片、圆润高对比标题、图标化操作、克制的可爱动效；避免紫色渐变与模板化 AI 风格。
- 当前需求已经给出完整信息架构和 demo 基线，按已确认方案直接实施，不额外暂停询问。

## Errors Encountered

- 2026-08-11：一次合并前端补丁因 `api.ts` 精确上下文不匹配而整体未应用；后续拆为小补丁并逐个验证。

| Error | Attempt | Resolution |
|---|---:|---|
| 首次读取输出被 node_modules 清单截断 | 1 | 改用定向文件读取与排除 node_modules |
| 从 `frontend` 目录读取后端相对路径失败 | 1 | 记录基准测试结果，后续统一从仓库根目录读取后端文件 |
| 首轮完整检查中 TypeScript 不接受 Vitest 单参数 mock 的第二参数索引 | 1 | 在测试内部把调用记录显式收窄为带可选 `RequestInit` 的元组 |
| 第二轮新增回归测试暴露 5 个失败 | 1 | 修正今日饮食口径、抽屉焦点、AI 会话竞态、训练历史计数和本地日期统计，12/12 转绿 |
| 后端完整验证超过一次命令等待窗口 | 1 | 继续轮询同一 Maven 会话，最终退出码 0 |
| 工作台服务 RED 命令使用 artifactId 作为 `-pl` 目标，嵌套 reactor 未识别 | 1 | 改用模块相对路径 `agentbuilder/agentbuilder-service`，不重复原命令 |
| 工作台服务首轮 GREEN 中发布测试仍被校验拦截 | 1 | 追踪到 MemoryPort 只提供 Tool，草稿引用的 Framework/Model/Prompt/Memory/Skill/Hook 均不存在；补齐夹具，不削弱生产校验 |
| Admin API RED 测试容器在初始化脚本前退出 | 1 | 容器日志明确显示复制进去的数据库密码文件不可读；与现有 Fitness 测试对比后补 `setReadable(true, false)` |
| Admin Controller 首轮 GREEN 返回 400 而非进入业务方法 | 1 | MockMvc detail 指出未启用 `-parameters` 且 `@PathVariable` 未声明名称；按现有 Controller 模式显式标注所有变量名 |
| Admin 集成测试最后一项看到 Provider 已配置 | 1 | 三个测试共享同一 Testcontainers 数据库且 JUnit 顺序未定义，凭据测试先运行；固定为读取初始状态→写入发布→冲突的显式顺序 |

## Agent 管理工作台（当前阶段）

### Goal

在正式项目中补齐 `/admin` 的真实 Agent 管理主链，并修正计划动作卡片左右区域等高。管理台以 demo 的克制桌面后台风格为视觉基线，但只读取和写入正式后端/PostgreSQL，不引入 demo Mock。

### Phases

1. [complete] 审计正式 Agent Builder 模块、数据库迁移、OpenAPI 与 demo 管理台视觉基线。
2. [complete] 固化工作台可运行切片的设计与实施计划。
3. [complete] TDD 实现 Agent 草稿、组件目录、Provider 配置、发布校验、运行记录后端 API。
4. [complete] TDD 实现 `/admin` 路由、控制台主视图、组件目录、配置编辑和状态反馈。
5. [complete] 修正计划动作卡片左右等高并进行 390px 视觉复验。
6. [complete] 完成前后端回归、真实数据库/API 验收、桌面浏览器视觉验收与文档归档。

### Decisions

- `/admin` 是同一 React 应用内的桌面路由；移动端 `/` 不受影响。
- 管理数据由 `agent` schema 持久化，页面不内置成功数据。
- 当前运行时尚未接通的能力必须显示“待配置/不可用”，不能伪造成成功。
- Provider API Key 只写入，服务端 AES-256-GCM 加密，任何响应均只返回掩码/配置状态。
- 先打通自用场景的单管理员主链；权限入口复用现有登录会话，复杂 RBAC 留在既有完整生产计划中。

## 2026-08-09 浏览器验收整改（当前阶段）

### Goal

执行 `docs/superpowers/plans/2026-08-09-browser-acceptance-remediation.md`，关闭饮食图片识别、推荐反馈、结构化当前目标报告、跟练语音、Agent Skill/Hook 可用性和管理台状态残留问题。

### Phases

1. [complete] 将 2026-08-07 浏览器验收发现转换为工程执行清单。
2. [in_progress] 使用 Terra 实施 Task 1～6，并对每项执行需求与代码质量审查；Task 1、Task 2 已通过独立审查。
3. [pending] 执行全量测试与真实浏览器复验，输出 2026-08-09 验收报告。

### Decisions

- 正式本地入口统一为 `http://127.0.0.1:5173`。
- 报告使用 Agent 标准结构化输出 + 固定前端渲染，不允许 Agent 直接生成 HTML。
- 饮食识别 Job 与推荐反馈持久化在 fitness schema，且不绑定目标。
- Skill/Hook 状态由数据库登记与真实运行时 handler 双重决定。
- Task 3 的反馈约束、三餐计划围栏、最终复审加固与 Unicode whitespace 对齐已占用 fitness schema 的 V8、V9、V10、V11；Task 4 如需持久化变更必须从新的 V12 迁移开始，绝不回改 V7/V8/V9/V10/V11。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| MiniMax 目录与统一快照测试按预期失败 | 1 | 进入 GREEN：新增目录种子，并让三餐/识别解析同一发布快照及凭据版本 |
| 当前可见子 Agent 模型列表未列出 Luna | 1 | `spawn_agent(model="luna")` 返回 Unknown model；当前仅支持 `gpt-5.6-sol`、`gpt-5.6-terra`，未静默替换 |
| Task 2 首轮实现依赖未提交文件且 OSS 生产链路不闭环 | 1 | 经过 Terra 多轮 TDD 与独立复审，补齐自包含提交、OSS HEAD/GET、异步 fencing、service 幂等、前端重试与契约一致性；最终 Critical/Important 清零 |

## 2026-08-09 开发者工作台重构（当前阶段）

### Goal

将工作台从错误复用健身用户登录态的实现，重构为独立开发者认证和真实 Agent 组件管理/调试控制台。

### Phases

1. [complete] 读取组件工作台参考页，确认目标不是复制 Demo，而是采用其卡片与编辑交互。
2. [in_progress] 固化独立 `AGENT_ADMIN_SESSION`、组件中心、真实调试/追踪的设计与实施计划。
3. [pending] 以 TDD 完成 Agent schema 管理员会话和 Admin API 认证替换。
4. [pending] 重做管理员登录和组件管理 UI。
5. [pending] 回归真实调试/追踪并提供隔离登录浏览器验收环境。

### Decisions

- 工作台是开发者控制面，不绑定健身用户或 `FITNESS_SESSION`。
- 参考 `workbench-components-demo.html` 的信息密度、列表、详情和保存条，不使用其假数据或删除项目中的调试台。
- 保留总览、Agent、提示词、技能、工具、调试台和运行追踪；Provider/Model 仍由 Agent 配置流程引用。

## 2026-08-10 MiniMax Provider 与首次目标复核（当前阶段）

### Goal

重新验证日期输入是否导致首次目标误报；设计并在用户确认后新增 MiniMax Provider/Model，随后仅把用户提供的 API Key 写入本地加密凭据存储。

### Phases

1. [complete] 只读审计首次目标日期处理、Provider/Model 注册方式与现有测试。
2. [complete] 通过真实页面按手工输入方式复核首次目标。
3. [complete] 提出 MiniMax 接入方案与最小设计并取得用户确认。
4. [complete] 按 TDD 实现统一发布快照、MiniMax 兼容处理与本地图片链路，并重启服务。
5. [complete] 通过管理页面保存密钥并验证掩码、发布检查、对话、目标报告与图片识别真实调用。
6. [complete] 执行全量回归并归档本轮页面验收报告。

### Constraints

- API Key 不写入代码、Git、计划、日志或报告，仅在用户确认方案后通过本地管理页面写入加密凭据。
- 不在用户确认前修改生产代码或数据库。
- 如必须新增 migration，按仓库规则先单独取得用户确认。
- 对话、目标报告、三餐建议、饮食图片识别都绑定同一个 `fitness.coach` Agent、同一个 MiniMax Provider 和同一个 MiniMax-M3 模型；实现层可保留不同任务 handler，但不得各自选择模型。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| MiniMax 返回内容包含 `<think>` 推理块 | 1 | TDD 增加统一可见内容清理，对话、Trace 与结构化解析均不再暴露推理块 |
| 当前目标报告首次调用在 45 秒超时 | 1 | 通过失败任务时间戳定位输出无上限，增加 `max_tokens=2000` 后进入结构校验阶段 |
| MiniMax 忽略 `response_format.json_schema` 并返回 fenced JSON | 1 | 保留服务端严格校验，同时在提示词声明精确字段、兼容完整 JSON 围栏；真实报告生成成功 |
| 拍照识别在创建 Job 前提示 Provider 未配置 | 1 | 定位为 local profile 未启用本地媒体存储；新增配置回归测试并启用 `happy.fitness.local-media.enabled` |
| 首次目标自动化提交丢失日期 | 2 | ISO 日期值需通过真实键入并失焦触发 React change；页面提交成功，确认不是产品缺陷 |

## 2026-08-10 AI 流式、确认式训练计划与验收遗留项

### Goal

在 `main` 当前未提交工作上连续实现健身聊天和 Agent 调试台 SSE、折叠执行摘要、完整 Markdown、安全确认后保存当天/未来 7 天训练计划，并一并关闭三餐空状态、Markdown 裸标记和 Trace 手填 UUID 三项遗留问题。

### Phases

1. [complete] 审计当前同步聊天、OpenAPI 流事件骨架、Markdown、训练计划存储与 P1-P3 根因。
2. [complete] 比较 SSE、HTTP 文本流与 WebSocket；用户确认采用持久 Run + SSE + 服务端确认方案 A，并授权新增前端依赖及数据库 migration。
3. [complete] 固化设计规格与 TDD 实施计划。
4. [complete] Contract-first 实现 Run/SSE/确认协议与持久状态。
5. [complete] 实现当天/未来 7 天训练计划提案及确认后 `fitness.plan.save`。
6. [complete] 接入两个聊天页面、Markdown、折叠摘要和确认卡。
7. [complete] 修复 P1 三餐生成入口与 P3 Trace 最近运行入口。
8. [complete] 执行定向测试、类型检查、格式化和真实页面冒烟。

### Decisions

- 继续使用用户明确要求的 `main`，保留当前 MiniMax 统一 Agent 的未提交改动，不创建隔离 worktree。
- 两个聊天入口统一消费持久 Run 的 SSE 事件；不引入 WebSocket。
- UI 只展示可公开的执行摘要、Tool 状态和进度，默认折叠，不展示原始内部推理文本。
- 当天与未来 7 天共用结构化提案；保存只替换同日期未完成计划，绝不覆盖已完成历史。
- 写工具只接受绑定当前用户/Run 的 `proposalId`；确认、保存均幂等。
- 只在整批结束后执行一次相关回归和页面冒烟，不为每个修复输出单独验收报告。

## 2026-08-10 可维护 Provider 与 Model 联动

### Goal

让个人工作台可以手动新增 OpenAI-compatible 模型服务和其支持的模型；模型明确归属 Provider，Agent 选择 Provider 后只显示该服务下可用模型，避免新服务或新模型依赖代码发版。

### Phases

1. [complete] 审计 Tool 绑定/审批执行、聚合 API、通用 Component 数据模型、Provider/Model 与注释缺口。
2. [complete] 比较修补投影、启用旧版本目录、迁移到简单独立资源三种方案；选择独立资源。
3. [complete] 用户授权数据结构调整，并明确开发期 Agent migration 最终压缩为单一正确基线，不累计保留 V1-V12。
4. [in_progress] 写入并自审设计规格与连续实施计划。
5. [pending] 按 TDD 实现独立资源 API、Provider-Model 联动和 Tool 审批绑定。
6. [pending] 执行定向验证、一次全量回归和真实页面验收。
7. [pending] 总结开发经验与遗留风险。

### Constraints

- 保持个人应用范围，不扩展多租户、组织权限或供应商市场。
- Provider 协议范围为 OpenAI-compatible；密钥继续只保存在本地加密凭据存储。
- 当前工作区已有未提交功能改动，后续实现必须保留并避免覆盖。
- Agent schema 仍处于开发期；最终只保留能够从空库建立当前正确结构的基线 migration。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| 计划状态补丁引用了仅存在于内存计划的步骤文本 | 1 | 读取实际 `task_plan.md` 后按现有章节更新，不重复使用错误上下文 |

## 2026-08-10 管理后台交互与 Trace 整理

### Goal

在个人应用范围内修正模型、提示词、技能和 Trace 页面的核心交互：新增操作使用一致弹窗，不挤压已有列表；能力配置只保留用户真正需要理解的内容；停用和保存操作位置统一；提示词与技能支持手动新增；Trace 以最近会话和运行记录为清晰主线。

### Phases

1. [complete] 检查真实页面、现有组件、API 能力和测试覆盖，明确最小交互方案。
2. [complete] 向用户提交 2–3 个方案及推荐设计；用户确认采用统一模态弹窗和模型能力声明方案。
3. [complete] 写入并修订设计规格；用户确认实施，UUID 搜索已从设计移除，Run Trace 改为对话式展示。
4. [complete] 按 TDD 实现模型弹窗、Prompt/Skill 新增和 Trace 重排。
5. [complete] 运行相关测试、类型检查、格式与生产构建。
6. [complete] 冷启动本地服务，并在真实页面复验模型、提示词、技能、会话 Trace 与 Run 详情。

### Constraints

- 不引入企业级模型市场、权限流或复杂能力矩阵。
- Provider、Model、Prompt、Skill 继续是独立资源。
- 删除只采用停用，不提供物理删除。
- 沿用现有后台视觉语言，重点修正层级、间距、按钮和弹层一致性。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| 上轮结束后浏览器标签绑定被释放 | 1 | 从当前用户打开的 Trace 标签页重新声明控制权，不重复创建页面 |
| 已声明的 Trace 标签在用户切换页面后被释放 | 1 | 不重复操作失效标签；后续页面复核时从当前打开标签重新声明 |
| 新增简化工作台 OpenAPI 首次 lint 失败 15 项 | 1 | 按仓库统一契约门禁补 summary/description、错误语义、403/422、Idempotency-Key 与 closed schema，不绕过 lint |
| 合并前端补丁因 `api.ts` 精确上下文不匹配而未应用 | 1 | 拆成 API、弹窗、目录、Trace 与样式小补丁逐一落地 |
| 首次统一格式检查发现 2 个本轮 Java 文件未满足 google-java-format | 1 | 运行 `./mvnw spotless:apply`，仅这 2 个文件被格式化 |

## 2026-08-11 通用 Agent 调试台

### Goal

让调试台展示并流式运行全部已发布 Agent，同时保留 `fitness.coach` 的健身 Tool 与确认流程。

### Phases

1. [complete] 确认数据库发布状态、前端过滤和后端拒绝分支的根因。
2. [complete] 固化通用 Agent 与健身 Agent 分流设计及实施计划。
3. [complete] 以 TDD 实现已发布 Agent 选择、通用持久流式运行和 Controller 分流。
4. [in_progress] 执行定向验证并一次性重启本地服务。

### Decisions

- 未发布 Agent 不进入调试台。
- `fitness.coach` 继续走专属正式运行链路；其他 Agent 读取自身不可变发布快照。
- 共用现有 Run、SSE、会话和 Trace 表，不新增 migration。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| Vitest 定向路径重复包含 `frontend/`，未找到测试文件 | 1 | npm prefix 已把工作目录切到 frontend，后续使用 `src/admin/AdminWorkbench.test.tsx` |

## 2026-08-11 Harness 统一运行时与完整 Trace

### Goal

- AgentScope 与 Spring AI Alibaba 根据已发布 Agent 的 `frameworkKey` 真实参与运行，禁止 Agent 对话入口绕过 Harness 直接调用模型。
- `agentbuilder-core` 提供易读、框架无关的上下文、记忆、能力和事件契约。
- 一次 Run 的 Trace 覆盖上下文装配、Skill 加载、模型推理、Tool 出入参、Hook、Memory 与最终结果。

### Phases

1. [complete] 核实现有 Adapter、运行入口、依赖和数据库框架登记现状。
2. [complete] 确认思考过程与统一 Block 生命周期的记录和展示口径。
3. [complete] 比较平铺事件、原生事件透传与 Core 强类型协议，用户确认采用强类型方案 A。
4. [in_progress] 已写入并自审设计规格，等待用户审阅后编写实施计划。
5. [pending] 按 TDD 实施核心契约、双 Adapter 装配和完整 Trace。
6. [pending] 对 AgentScope/SAA 各执行一次真实问题点验证。

### Constraints

- 不新增企业级编排、分布式追踪或多租户抽象；只实现个人应用需要的正确运行链路。
- 保留两个真实 Harness；管理配置中可选即必须可运行。
- AgentScope Java 以 Maven Central 可用的 2.0.2 为目标；Python 2.0.6 文档仅用于校准 Message/Event 语义。
- Agent schema 仍只保留一个开发期 `V1__agent_baseline.sql`。
- 不覆盖或清理工作区中已有未提交改动。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| 首次追加计划时引用了不存在的旧步骤文本 | 1 | 读取文件尾部后按当前章节锚点追加，保留已有计划内容 |
| 第二次打开外部参考时 JavaScript 对象引号错误 | 1 | 修正工具参数后一次成功读取官方 issue 与百炼文档，不重复错误调用 |
