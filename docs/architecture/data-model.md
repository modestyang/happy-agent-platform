# 逻辑数据模型

## 1. 总体规则

物理数据库为 `happy_agent`，包含 `fitness` 与 `agent` 两个 schema。表名以下用逻辑名称表示；正式迁移可采用 snake_case。两个 schema 不建外键、不跨 schema 查询、不做跨 schema 事务。所有可变聚合包含 `version`、`created_at`、`updated_at`；标识使用不可猜测 UUID/ULID；时间点为 UTC。

幂等表分别位于所属 schema，唯一键为 `(principal_id, idempotency_key)`，并保存请求摘要、响应状态/资源标识和过期策略。相同键不同摘要必须拒绝。

## 2. `fitness` schema

| 聚合/表 | 关键字段与约束 |
|---|---|
| `users` | `user_id`、外部主体标识（唯一）、状态、创建时间 |
| `user_preferences` | `user_id`（唯一）、时区、单位制、饮食限制、忌口、器械、训练日、单次时长、语音/提醒、`version` |
| `goals` | `goal_id`、`user_id`、类型、开始/结束日期、目标值/单位、状态、`version`；用户仅一个 `ACTIVE` |
| `exercises` | `exercise_id`、名称、目标肌群、器械、难度、步骤、注意事项、媒体引用、状态、目录版本 |
| `workout_plans` | `workout_plan_id`、`user_id`、计划日期/周期、来源、状态、`version` |
| `workout_plan_exercises` | 计划、顺序、动作、组数、次数/时长、休息、语音提示、替换来源；计划内顺序唯一 |
| `workout_records` | `workout_record_id`、`user_id`、发生时间、来源、总时长、感受、备注；**无 `goal_id`** |
| `workout_record_exercises` | 记录、顺序、动作快照、实际组次/次数/负重/时长 |
| `daily_meal_plans` | `meal_plan_id`、`user_id`、用户本地日期、生成输入 checksum、状态、`version`；用户/日期唯一 |
| `meal_plan_items` | 计划、餐别（早餐/午餐/晚餐）、顺序、食物、份量、单位、热量、蛋白质/碳水/脂肪 |
| `meal_feedback` | `feedback_id`、计划、餐别、采纳状态、评分、实际份量、说明、发生时间 |
| `meal_recognition_jobs` | `job_id`、`user_id`、媒体引用、状态、租约、重试、输入 checksum、候选结果、错误、时间戳 |
| `meal_records` | `meal_record_id`、`user_id`、发生时间、餐别、来源、识别任务可选引用、备注；**无 `goal_id`** |
| `meal_record_items` | 记录、顺序、食物、份量、单位、热量和宏量营养素、识别置信度可选 |
| `body_metric_records` | `record_id`、`user_id`、测量时间、体重/体脂/腰围/静息心率、备注；至少一个指标，**无 `goal_id`** |
| `current_goal_reports` | `report_id`、`user_id`、`goal_id`、目标版本快照、状态、统计窗、`computed_through`、聚合结果、错误、`version`；只保留当前目标报告语义 |
| `media_objects` | `media_id`、`user_id`、OSS key、media type、字节数、checksum、状态；不存二进制 |
| `fitness_jobs` | 类型、业务键、状态、租约所有者/截止、重试、输入 checksum、错误；三餐计划/报告等可恢复任务 |
| `fitness_idempotency` | 主体、键、请求摘要、状态、资源/响应摘要；组合唯一 |
| `fitness_operations` | `operation_id`（唯一）、调用主体、用例、请求摘要、结果摘要；供 Agent Tool 跨 schema 恢复 |

05:30 三餐调度以 `(user_id, local_date, job_type)` 唯一，租约过期可重领。客观记录没有目标外键；报告只按目标快照的日期窗读取聚合。

## 3. `agent` schema：类型化目录

每种组件使用独立主表/版本表，公共元数据可重复列或由只读视图统一投影，不使用 EAV 总表。

| 表族 | 类型专属字段 |
|---|---|
| `framework_versions` | framework key/version、adapter/build version、支持 Skill/Hook 阶段/恢复/结构化输出能力 |
| `provider_versions` | provider type、endpoint、credential version、workspace、支持框架、能力、健康、timeout |
| `models` / `model_versions` | provider version、model name、模态、context/max output、Tools/stream/structured/vision/audio、默认参数、参数 Schema |
| `tool_versions` | runtime name、使用/禁用说明、应用、input/output Schema、strict input、副作用/幂等/风险/scopes、timeout/调用上限、stream/return direct、checksum/build |
| `skill_versions` | 使用/禁用说明、应用范围、Markdown content、渐进披露、必需/可选 Tools、只读资源、checksum |
| `hook_versions` | hook type、阶段、优先级/前后顺序、config Schema/default、timeout、失败策略、required、副作用 |
| `memory_policy_versions` | 策略类型、保留/压缩窗口、token 限制、config Schema/default |
| `prompt_versions` | 模板、变量 Schema、模板格式、checksum |
| `output_schema_versions` | 闭合 JSON Schema、示例、checksum |
| `evaluation_suite_versions` | 通过阈值、用例集合、评分规则、required safety checks |

所有版本表包含公共字段：key、整数 version、名称、说明、分类、tags、source type、生命周期、config Schema/checksum、兼容框架、created by/at、deprecated at、replacement key。Tool/Framework/Hook 的代码核心字段只由扫描器登记。

`component_usages` 保存被 Agent version/草稿或 Skill 引用的类型、key、version 与用途，用于详情页影响分析；不替代类型表外键。

## 4. `agent` schema：凭据与默认配置

| 表 | 关键字段与约束 |
|---|---|
| `credentials` | credential id、provider key、密文、密钥版本、掩码提示、状态、轮换时间；响应永不暴露密文 |
| `default_profile_versions` | profile key/version、应用范围、框架范围、稀疏默认项、状态、checksum、`version` |
| `platform_limits` | 代码/部署登记的安全硬限制版本与 checksum，只读投影 |

默认解析不持久化含义不清的 `null`。草稿覆盖只存显式字段；解析结果的每个叶子保存 `PLATFORM_LIMIT`、`CODE_DEFAULT`、`APPLICATION_PROFILE` 或 `AGENT_OVERRIDE` 来源。

## 5. `agent` schema：Agent 生命周期

| 表 | 关键字段与约束 |
|---|---|
| `agents` | agent key、名称、应用范围、状态、当前发布版本、唯一草稿引用 |
| `agent_drafts` | agent、framework/provider/model/prompt/default profile 引用、稀疏运行限制、`version` |
| `draft_tool_bindings` | Tool key/version、enabled、usage guidance、timeout、calls/run、审批、重试、结果模式；不得突破代码上限 |
| `draft_skill_bindings` | Skill key/version、enabled、稀疏配置；必需 Tool 校验 |
| `draft_hook_bindings` | Hook key/version、enabled、稀疏 config；required Hook 不可关闭 |
| `agent_versions` | agent/version（唯一）、发布时间/主体、来源草稿/回滚版本、框架/Provider/Model/Prompt/Memory/Output/Evaluation 引用、默认档案版本、完整 runtime limits、解析后的完整有效配置、组件 checksums；不可变 |
| `evaluation_jobs` | job、草稿版本/输入 checksum、suite version、状态、租约/重试、总结果、错误 |
| `evaluation_case_results` | job、case key、状态、score、expected/actual 结构化摘要、安全结果和 Trace 引用 |
| `probe_jobs` | Provider/Model 目标、探测类型、状态、租约/重试、能力/健康结果、错误 |

发布事务只写 `agent` schema：校验草稿版本与评测结果后插入不可变 `agent_versions` 并更新 `agents.current_published_version`。回滚从目标快照复制成下一整数版本。

## 6. `agent` schema：会话、Run 与 Trace

| 表 | 关键字段与约束 |
|---|---|
| `sessions` | session id、应用、业务用户外部标识、Agent key/version、状态、created/expires/closed；手机会话 `expires_at = created_at + 24h` |
| `session_messages` | session、严格序号、角色、结构化 content、created；序号唯一 |
| `runs` | run id、session/playground 来源、Agent version 或草稿 checksum、状态、预算、token/成本、游标、输出、错误、时间戳 |
| `run_events` | run、严格 event id/序号、闭合事件类型、结构化 payload、可见性、created；供 SSE 续传 |
| `trace_events` | run、严格序号、span/parent、类型、阶段、组件引用、脱敏输入/输出、耗时、状态、错误 |
| `tool_call_intents` | run、call、operation id（唯一）、Tool version、服务端上下文、参数摘要、状态、结果摘要 |
| `approvals` | run/tool call、策略、状态、请求/决定主体和时间、到期时间 |
| `checkpoints` | run、序号、adapter version、恢复状态、checksum |
| `agent_idempotency` | 管理主体、键、请求摘要、状态、资源/响应摘要；组合唯一 |

Agent schema 只保存关联业务用户的外部标识，不复制 Fitness 用户资料或记录。Tool 意图与 Fitness operation 通过不可猜测 `operation_id` 协调，但不建跨 schema 外键。

## 7. 索引、保留和并发

- 所有用户历史索引以 `(user_id, occurred_at desc, id desc)` 支持游标分页。
- 活跃目标使用条件唯一索引；每日计划使用 `(user_id, local_date)` 唯一索引。
- 任务索引覆盖 `(status, lease_until, next_attempt_at)`；领取采用带租约的条件更新或 advisory lock。
- Run/event/trace 分别以 `(run_id, sequence)` 唯一并有时间索引。
- 组件和发布版本以 `(component_key, version)` 唯一；不可变版本禁止 UPDATE/DELETE，只允许生命周期旁路记录。
- 乐观资源 `version` 每次修改递增并映射强 ETag。
- V1 不依赖 Redis；可丢失缓存使用 Caffeine，重启后由数据库恢复。
