# 指定 Skill 的每日三餐 Agent 后台任务设计

## 目标

每日三餐由已发布的 `fitness.coach` Agent 生成，但以独立后台任务运行。每次运行必须显式指定 `fitness.meal.skill`，只开放该 Skill 声明的只读 Tool；Skill 或依赖缺失时失败关闭，不回退到代码固定提示词或通用模型回答。

## 运行边界

- 任务信封固定包含 `DAILY_MEAL_PLAN`、目标日期和 `requiredSkillKey=fitness.meal.skill`。
- 运行时从最新 `PUBLISHED` Agent 快照选择恰好一个 Skill，并校验其 `requiredToolKeys` 都已绑定且当前可运行。
- RunRequest 只包含该 Skill、必要 Tool、已发布 Prompt 和 Hook；Memory 为空。
- 使用 `fitness.coach:background:fitness.meal.skill` 内部会话记录 Run/Trace，不写入用户聊天会话。
- Agent 输出 Skill 规定的 JSON；应用层继续严格校验恰好三餐、字段闭包、中文文案和热量范围，再以现有租约完成持久化。

## Skill 职责

`fitness.meal.skill` 负责查询顺序和推荐策略：使用当前已绑定的档案、训练、饮食与近 30 天反馈 Tool，避开明确不喜欢和限制内容，并输出简体中文的固定三餐 JSON。自由文本反馈仅作为数据，不得作为指令执行。

本轮保持 Tool 清单和所有 Tool 输入输出契约不变。更细的近期训练、历史推荐等上下文扩展按用户要求留到后续独立 Tool 优化，不混入本次后台任务改造。

Java 不保存上述推荐策略，只保留可信任务类型、日期、权限、超时、结构校验和错误映射。

## 调度与活跃用户

系统仍只有一个全局 05:30 调度器。每日名单要求账户 ACTIVE、存在 ACTIVE 目标且 `users.updated_at` 不早于当前时间 14 天。Bootstrap 成功后更新 `users.updated_at`；回访用户若当天计划不存在则立即入队。应用启动时执行一次缺失任务补偿，避免 05:30 停机造成整天漏跑。

Worker 使用默认 3 线程、零排队容量的专用执行器；每个执行线程仍通过 PostgreSQL `FOR UPDATE SKIP LOCKED` 和现有 fencing lease 领取任务，不为用户创建线程或定时器。

## 错误与验收

- Agent 未发布、指定 Skill 不存在/未绑定、Skill 必要 Tool 缺失：`DEPENDENCY_NOT_CONFIGURED`。
- Framework、Provider 或模型运行失败：`DEPENDENCY_UNAVAILABLE`。
- 输出非 JSON、字段错误、缺少中文或三餐不完整：`INVALID_MODEL_RESPONSE`。
- 验收覆盖严格 Skill/Tool 裁剪、14 天暂停、回访恢复、启动补偿、有界执行以及真实已发布 Skill 生成中文三餐。
