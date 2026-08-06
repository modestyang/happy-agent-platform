# Agent 管理工作台首轮验收记录

## 验收范围

- 手机端计划页动作卡片左右区域等高。
- `/admin` 管理工作台：总览、Agent 草稿编辑、发布校验、组件中心、模型服务、运行记录和调试台。
- 管理数据来自 `agent` schema；没有运行记录时展示真实空状态，不生成 Mock 成功数据。
- Provider 凭据只写入，使用 AES-256-GCM 加密保存，接口仅返回配置状态和固定掩码。

## 本地访问

- 手机端：`http://127.0.0.1:5176/plan`
- 管理工作台：`http://127.0.0.1:5176/admin`
- 本地体验账号：`user`
- 本地体验密码：`demo123`
- PostgreSQL：通过 `./deploy/local-up.sh` 启动，数据目录为 `deploy/.local/data/postgres`。
- Agent 主密钥：由 `./deploy/scripts/generate-secrets.sh` 生成在忽略提交的 `deploy/secrets/agent-master-key`；验收记录不包含密钥内容。

## 自动化证据

- 前端：Vitest 28/28、TypeScript、ESLint、Vite production build 通过。
- 后端：`./mvnw verify -q` 退出码 0，覆盖服务、JDBC/Testcontainers、两个框架适配层、Starter 集成与架构约束。
- 管理 API：覆盖未登录 401、数据库快照、草稿乐观锁、发布校验、版本发布、Provider 加密写入和运行详情。
- 安全：Testcontainers 直接检查数据库中不出现 Provider 明文；响应只返回 `••••••••`。

## 浏览器验收

- 计划页首张动作卡片：图片区高度 `233.39px`，右侧文案区高度 `233.39px`，上下边沿一致。
- 工作台从真实数据库读到 1 个 Agent、14 个组件、1 个 Provider、0 条运行记录。
- 发布检查正确返回阻塞项：`Provider 尚未配置 API Key`；没有 Tool/Skill 时返回提醒。
- 草稿实际保存后 revision 从 1 变为 2；页面刷新后仍显示 revision 2，证明写入 PostgreSQL 而非仅改前端状态。
- 自定义 `If-Match` 与 JSON Content-Type 的组合问题已通过真实浏览器发现，并增加 RED/GREEN 回归测试。

## 当前真实限制

- 阿里云百炼 API Key 尚未配置，因此 Agent 不允许发布或真实对话。
- Fitness Tool Bean 尚未接入，4 个健身 Tool 如实显示“不可用”。
- 2 个 Skill 和安全 Hook 当前处于“待完成”，管理台可以登记和选择，但发布门禁会检查其状态。
- 调试台在 Provider、Tool 和已发布版本未就绪时保持禁用，不伪造模型回答。
