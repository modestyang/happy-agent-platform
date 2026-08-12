# Fitness Agent DB 内容初始化设计

## 目标

将 Obsidian 中已确认的系统提示词与四个 Fitness Skill 一次性写入当前本地 Agent 数据库，并发布新的 `fitness.coach` 不可变版本，使手机端立即使用完整配置。

## 数据来源

- 系统提示词：`06-Fitness-Agent-系统提示词.md` 的 `Runtime System Prompt`
- 四个 Skill：`02` 至 `05` 文档各自的 `Runtime Skill Content`
- Skill 元数据：各文档 YAML front matter 中的 `skill_key`、`display_name`、`description`、`when_to_use`、`when_not_to_use` 和 `required_tools`

## 写入边界

- 仅更新 `agent.agent_prompts` 中的 `fitness.coach.prompt`。
- 仅更新或新增 `agent.agent_skills` 中的四个固定 Key：
  - `fitness.plan.skill`
  - `fitness.meal.skill`
  - `fitness.analysis.skill`
  - `fitness.knowledge.skill`
- 更新 `agent.agent_drafts` 中 `fitness.coach` 的 Prompt、Skill 与 Tool 绑定。
- 不修改表结构、Flyway 历史、Provider 凭证、测试 Agent、测试 Prompt 或测试 Skill。

## 兼容性

`fitness.meal.skill` 继续保留 `DAILY_MEAL_PLAN` 后台任务的严格 JSON 输出分支；普通对话部分使用新版专业饮食 Skill。后台分支额外依赖只读的 `fitness.meal.recommendations.query`，用于读取当天已有推荐、避免重复生成；不重新暴露旧聚合 Tool，也不引入确认流程。

## 发布与回滚

写入前保存目标行的数据库快照。配置写入在单个事务内完成，随后校验字段、绑定和 Tool 可用性，再调用现有工作台发布接口生成新版本。旧的已发布版本保持不可变，可通过既有版本机制回滚。

## 验收

- Prompt 正文与 Obsidian Runtime 区块一致。
- 四个 Skill 均为 `ACTIVE`、`runtime_ready=true`，元数据完整。
- `fitness.coach` 绑定四个 Skill 及其所需细粒度 Tool。
- 每日三餐后台任务契约仍存在。
- 新发布版本的快照包含新 Prompt 与四个 Skill。
- `test-agent-1`、`test-food`、`fruit-only-acceptance` 未改变。
