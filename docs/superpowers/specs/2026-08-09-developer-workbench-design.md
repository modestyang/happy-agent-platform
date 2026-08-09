# 开发者 Agent 工作台重构设计

## 目标

将 `/admin` 改为独立于健身应用用户的开发者控制台，并以 `workbench-components-demo.html` 的组件管理交互为参考，保留本项目真实的 Agent 调试、发布与运行追踪能力。

## 边界与认证

- 健身端继续使用 `FITNESS_SESSION`，只访问 `/api/app/**` 和 `/api/v1/app/**`。
- 工作台使用单独的 `AGENT_ADMIN_SESSION` HttpOnly Cookie，只访问 `/api/admin/**`。
- 管理员账号持久化在 `agent` schema；本地环境迁移后种子账号为 `admin / admin123`，密码只保存 BCrypt hash。生产环境通过配置覆盖初始密码，并在首次登录后修改。
- `/api/admin/**` 不再注入 `FitnessApplicationService` 或读取健身 Cookie。管理员认证仅依赖 Agent Builder 的认证端口。

## 信息架构与交互

桌面工作台沿用参考页的浅灰画布、216px 侧栏、紧凑卡片和底部保存条，但不照搬其虚构内容。

1. 总览：显示真实 Agent 发布状态、已配置 Provider、最近 Run 与快捷入口。
2. Agents：配置 Agent 草稿的模型、提示词、Skill、Tool、Hook；校验后发布。
3. 组件：提示词、技能、工具。
   - 列表使用可搜索、可筛选的组件卡片，显示状态、Key、引用关系和更新时间。
   - 提示词详情支持变量检测、模板编辑和预览。
   - 技能详情支持触发条件、禁止条件、Markdown 编辑/预览与依赖工具选择；保存时拒绝依赖已停用的工具。
   - 工具由代码注册，详情只读展示 Schema、风险、调用限制与被 Skill 引用情况，仅允许启停。
4. 调试台：调用已发布 Agent 的真实运行时；展示最终回复及 Run 链接，未配置模型/未发布时明确阻断。
5. 运行追踪：读取真实的历史 Run/Trace，呈现 Hook、Skill、Tool、模型与最终回复的链路。

## 数据与 API

- 新增 Agent schema 管理员账户与会话表；登录、登出、当前会话检查使用 `/api/admin/auth/**`。
- 既有 `/api/admin/workbench`、组件、发布、Run/Trace API 的业务负载保持不变，仅把认证来源替换为 `AdminSession`。
- 管理员登录错误返回 401 Problem；无效/过期 Cookie 返回 401，不跳转或读取健身应用账户。
- 前端 `/admin` 先请求管理员会话；未登录时显示开发者登录页，成功后加载工作台。移动端登录页不再承担工作台登录或跳转职责。

## 验收标准

- 在未登录健身端的浏览器中，使用 `admin / admin123` 可进入工作台。
- 健身用户 `user / demo123` 登录、退出、Cookie 失效均不影响已登录工作台；反向同样成立。
- 组件编辑、发布、调试、Run/Trace 均基于 PostgreSQL 中的真实数据，不能展示模拟成功结果。
- 组件页面具备参考页的卡片列表、详情、状态、编辑和未保存保存条交互；调试台与运行追踪保留项目特有能力。
