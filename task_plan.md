# Happy Agent Platform implementation plan

## 2026-08-12 训练播放器媒体、计时与语音修复（当前阶段）

### Goal

修复计划页与训练过程中的动作图片轮播、训练准备倒计时节奏、训练中逐秒提示音，并为语音指导增加可选择的声音风格；保持真实动作资源、训练状态和现有页面流程不被替换。

### Phases

1. [complete] 只读复现计划页、训练播放器、计时与音频现状，定位五项问题的根因和现有数据边界。
2. [complete] 逐项确认语音风格能力范围与移动端验收标准，比较实现方案并取得设计确认。
3. [complete] 固化设计规格与 TDD 实施计划。
4. [complete] 按失败测试实现共享媒体轮播、单调时钟倒计时、节拍音和语音风格选择。
5. [complete] 执行定向回归、类型检查、Lint、生产构建与本地移动端环境核验；真实设备音色验收待账号具备可进入的训练计划。

### Constraints

- 当前工作区包含其他任务的大量未提交修改，必须保留并避免覆盖。
- 不新增或升级依赖，除非用户另行确认。
- 不改数据库 migration，除非现有模型无法保存必要偏好且用户另行确认。
- 计划页与训练页复用同一动作媒体规则，计时显示与音频必须由同一时钟事实驱动。

### Verification

- 训练相关 5 个测试文件共 56/56 通过；TypeScript、ESLint、生产构建和 `git diff --check` 通过。
- 全量前端测试 127/128 通过；唯一失败为并行改动中的 `MealRecommendationPage.test.tsx` 重试状态断言，单独运行可复现，本轮未修改该模块。
- 本地账号没有可进入的训练计划，因此未创建测试业务数据；真实设备声音观感保留为有计划数据后的手工验收项。

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
| TypeScript DOM 类型没有声明 `CSSStyleDeclaration.textSizeAdjust` | 1 | 改用标准 `getPropertyValue('text-size-adjust')` 读取计算样式 |
| 定向 Vitest 路径包含 `frontend/`，prefix 切换目录后未找到文件 | 1 | 改用 `src/App.test.tsx`，不重复错误命令 |
| 首个 computed-style 断言在未注入 CSS 的 jsdom 中直接通过 | 1 | 删除无效断言，改为显式加载真实 `app.css` 并断言其计算后行为 |
| CSS 行为测试使用 `import.meta.url` 时被 Vitest 转换为非 file URL | 1 | 与现有测试一致，从 frontend 工作目录读取 `src/app.css` |
| 从仓库根目录直接调用 ESLint 未发现 frontend 配置 | 1 | 将工作目录切到 `frontend` 后只 lint `src/App.test.tsx` |
| 本地浏览器后端不支持 `networkidle` 等待状态 | 1 | 页面已打开，改用受支持的 `load` 后获取 DOM 快照 |
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

## 2026-08-11 手机输入框自动缩放验收修复

### Goal

消除 iPhone 等手机浏览器聚焦体重目标及其他表单输入框时触发的页面自动放大，同时保留用户主动缩放能力。

### Phases

1. [complete] 定位 viewport、移动端输入字号与目标表单的实际样式根因。
2. [complete] 先添加可复现的最小回归测试，再实施单点样式修复。
3. [complete] 只运行相关前端测试、类型/格式检查并做一轮改动代码审查。
4. [complete] 按用户要求重新部署本地栈，并在手机视口验证聚焦行为。

### Constraints

- 不禁用 pinch-to-zoom，不使用 `user-scalable=no` 或过小 `maximum-scale`。
- 不修改本轮无关的现有未提交代码。
- 不执行全量 Maven/前端测试或完整验收，仅做定向验证与部署冒烟。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|

## 2026-08-11 AI 聊天缩放与自动跟随修复

### Goal

消除手机端 AI 聊天输入聚焦时的页面自动放大，并让新消息、流式回复和恢复中的回复自动跟随到对话底部。

### Phases

1. [complete] 核对聊天输入的最终 CSS、消息状态更新路径与真实滚动容器。
2. [complete] 先添加两个最小失败用例，分别覆盖聊天输入字号和新回复滚动。
3. [complete] 实施最小修复并运行相关前端测试、类型检查和定向 lint。
4. [complete] 做一轮只针对本次改动的代码审查；不部署、不做完整验收。

### Constraints

- 保留用户主动缩放，不修改 viewport 为禁止缩放。
- 自动跟随只作用于 AI 对话页，不改其他页面滚动逻辑。
- 不触碰工作区内其他既有未提交改动。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|

## 2026-08-11 首页适配、独立目标报告与饮食中文化

### Goal

让首页核心组件在常见手机视口内完整展示；将目标报告从 AI 对话中拆为独立页面；查清饮食推荐的 Agent/模型调用链并确保面向用户的推荐只输出中文。

### Phases

1. [complete] 复现并定位首页溢出、报告路由耦合及饮食推荐语言来源。
2. [complete] 先添加最小失败用例，覆盖独立报告入口、首页布局约束和中文生成指令。
3. [complete] 实施最小范围的前端布局/路由与后端提示词修复。
4. [complete] 让已持久化的英文 READY 推荐在进入饮食页后自动重新生成。
5. [complete] 运行定向测试、类型/格式检查并做一轮改动代码审查；不部署、不做完整验收。

### Constraints

- 不修改 API 契约、依赖、数据库 migration 或部署配置。
- 不触碰工作区内其他既有未提交改动。
- 报告沿用现有数据和卡片内容，只改变信息架构与页面承载方式。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| starter 单模块测试缺少 reactor 依赖类 | 1 | 改用 `-pl starter -am` 并关闭其他模块无匹配测试失败。 |
| 全仓 Spotless 被无关 `ToolSchemaCodec.java` import 顺序阻塞 | 1 | 保留用户改动，改跑本轮 Java 所在 starter 模块 Spotless。 |
| 首页 701px 高度断点卡片内容裁切 | 1 | 审查时将首页紧凑断点独立提高到 820px，播放器仍保留 700px。 |
| 已持久化英文 READY 推荐不会被新提示词自动替换 | 1 | 非中文 READY 结果进入饮食页时自动重入既有持久任务与轮询，中文 READY 仍直接复用。 |

## 2026-08-12 指定 Skill 的三餐 Agent 后台任务

### Goal

把每日三餐从固定提示词直调模型改为 `fitness.coach` 的独立后台 Agent 任务，并严格指定 `fitness.meal.skill`；结合档案、训练、饮食、反馈和近期推荐生成结构化中文三餐，同时暂停连续 14 天未使用应用的用户，支持回访按需恢复、启动补偿和有界并发处理。

### Phases

1. [complete] 恢复现有未提交 Harness/Trace、三餐任务和调度上下文，固定不新增 migration 的实现边界。
2. [complete] TDD 增加发布 Agent 后台任务入口：只装载指定 Skill 及其声明的 Tool，缺失即失败且不使用聊天记忆。
3. [complete] TDD 将三餐运行时改为消费后台 Agent JSON，并把推荐逻辑收拢到 `fitness.meal.skill`。
4. [deferred] 按用户最新要求，本轮不修改或新增 Tool 清单/契约；Tool 上下文优化另开一轮。
5. [complete] TDD 实现 14 天活跃筛选、首页回访按需恢复、启动补偿与 3 路有界 Worker。
6. [complete] 运行相关测试、Spotless、编译检查并执行一轮改动代码审查。
7. [complete] 更新并发布真实 `fitness.meal.skill`，重新部署，完成定时/回访/真实 Agent 三餐验收。

### Constraints

- 后台任务固定 `agentKey=fitness.coach`、`requiredSkillKey=fitness.meal.skill`；Skill、必要 Tool 或发布快照缺失时失败关闭。
- 推荐策略与 JSON 输出协议由 Skill 承载；Java 只负责可信任务信封、权限、调度、严格结果校验和持久化。
- 后台任务使用独立内部会话标识且不加载聊天记忆，不出现在健身用户 AI 对话中。
- 连续 14 天没有打开应用的用户不再进入每日生成名单；重新打开后更新活动时间并仅在当天计划缺失时入队。
- 使用现有 `users.updated_at`，不新增或修改数据库 migration。
- 保留工作区全部既有未提交改动；不创建 commit。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| TDD Skill 引用的 `writing-good-tests.md` 不在 Skill 根目录 | 1 | 在 `skills/test-driven-development/` 子目录定位并完整读取，不重复错误路径。 |
| 后台任务首轮 GREEN 把 Skill 快照修订号读取为 `revision` | 1 | 核对 `PublishedComponent.asSnapshot()` 后改读真实字段 `version`，不修改发布快照契约。 |
| 新增动态上下文 Tool 会提前改变 Tool 清单/契约 | 1 | 用户明确要求 Tool 后续单独优化，已完整撤回新 Tool、查询 DTO 与绑定，当前 Tool 逻辑保持不变。 |
| 专用 Executor 抑制 Spring Boot 默认 `applicationTaskExecutor` | 1 | 将专用 Bean 标记为 `defaultCandidate=false`，保留 Qualifier 注入且不影响 Boot 默认执行器。 |
| AgentScope 自动注入 `wait_async_results` 导致后台任务请求人工确认 | 1 | 后台模式已禁用异步 Tool 和子 Agent，因此在构建后移除该 Harness 内部辅助 Tool；业务 Tool 清单与契约不变。 |

## 2026-08-12 首页密度、聊天宽度与花爷命名修复

### Goal

消除高屏手机上首页四张快捷卡被剩余空间强行拉长的问题，并在首页加入紧凑的体重变化趋势；让 AI Markdown、表格及长文本始终限制在手机聊天容器内；把所有面向用户的 AI 名称从“瘦瘦”统一为“花爷”；为体重趋势、动作示意和报告图表等需要查看细节的场景提供点击放大。

### Phases

1. [complete] 根据用户截图复现并定位首页 Grid 拉伸、聊天横向溢出、残留名称及现有趋势/动作图实现。
2. [complete] 提交并确认首页趋势、通用 UI 基础层、可放大场景和 A2UI-inspired renderer registry 设计。
3. [complete] 按 TDD 增加失败用例，再实施最小 CSS、趋势图、放大查看与文案修复。
4. [complete] 运行相关前端测试、类型检查、格式检查和一轮改动代码审查；不部署。

### Constraints

- 保持现有暖色、圆角、卡片化视觉语言，不进行范围外重设计。
- 首页允许内容高度自然收紧；优先消除空白，不再以“必须填满全部视口”为目标。
- 聊天正文、代码与长链接不得撑宽页面；Markdown 表格保持二维结构，只允许表格组件自身横向滑动，并支持弹出浮层完整查看。
- 只修改面向用户的 AI 品牌文案，不修改技术标识 `fitness.coach`、数据库键或 API 契约。
- 放大能力只覆盖承载数据或动作细节的视觉内容；头像、图标、装饰性插画和普通卡片不放大。
- 首页趋势图复用 bootstrap 中已有体重记录，数据不足时提供克制的空状态，不新增 API。
- Markdown 表格浮层与趋势/动作视觉查看器共享遮罩、关闭和键盘交互，但表格使用独立的滚动布局、粘性表头和首列。
- 本轮不部署、不跑全量验收、不调整 Tool 逻辑。

### Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| 独立审查发现结构化确认卡缺少回调、焦点约束、状态关联与运行时降级边界 | 1 | 按 confirmationId 透传可信动作和提交/终态状态，补焦点循环与背景 inert，未知或畸形 block 安全降级且不遮蔽 legacy 审批。 |
| 放大表格通过 portal 脱离 `.md` 后丢失二维布局规则 | 1 | 将表格宽度、边框与 nowrap 规则归属到 `.data-table-viewport`，并用 portal 后计算样式回归测试覆盖。 |
| 全 reactor Spotless 被工作区内无关 Fitness/Starter 旧格式阻断 | 1 | 不改动无关文件；本轮涉及的 agentbuilder-service 与 agentbuilder-infrastructure 模块 Spotless 单独通过。 |
| 9 文件并发 Vitest 首轮因机器负载使后台总览异步断言超过默认等待时间 | 1 | 单测文件单独通过；最终使用 `--maxWorkers=1` 重跑同一相关套件，88/88 通过，未修改产品代码规避超时。 |
