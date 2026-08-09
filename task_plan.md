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
