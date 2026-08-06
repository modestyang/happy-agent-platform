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
4. [in_progress] TDD 实现 `/admin` 路由、控制台主视图、组件目录、配置编辑和状态反馈。
5. [pending] 修正计划动作卡片左右等高并进行 390px 视觉复验。
6. [pending] 完成前后端回归、真实数据库/API 验收、桌面浏览器视觉验收与文档归档。

### Decisions

- `/admin` 是同一 React 应用内的桌面路由；移动端 `/` 不受影响。
- 管理数据由 `agent` schema 持久化，页面不内置成功数据。
- 当前运行时尚未接通的能力必须显示“待配置/不可用”，不能伪造成成功。
- Provider API Key 只写入，服务端 AES-256-GCM 加密，任何响应均只返回掩码/配置状态。
- 先打通自用场景的单管理员主链；权限入口复用现有登录会话，复杂 RBAC 留在既有完整生产计划中。
