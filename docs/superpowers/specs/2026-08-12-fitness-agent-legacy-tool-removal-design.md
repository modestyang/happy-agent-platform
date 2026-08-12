# Fitness Agent 历史 Tool 清理设计

## 目标

从当前业务 Tool 注册表和默认 Agent 配置中移除 5 个已被细粒度能力取代的历史 Tool，避免模型继续误选宽聚合查询，同时保持当前训练计划、饮食建议、分析、知识问答和计划确认能力可用。

## 删除范围

- `fitness.profile.query`
- `fitness.workout.query`
- `fitness.meal.query`
- `fitness.meal.feedback_context`
- `fitness.exercise.search`

它们分别由用户资料/目标/约束/身体指标、训练日程/汇总/历史、饮食汇总/历史/推荐/反馈，以及动作目录/候选/详情等细粒度 Tool 替代。

## 配置迁移

当前 Agent 默认绑定和 Plan Skill 的 required keys 不再包含历史键。Meal Skill 改为显式依赖当前粒度的用户资料、目标、身体指标、营养偏好、训练汇总、饮食汇总/历史/推荐/反馈与营养目标 Tool。旧发布快照与 Trace 只保留历史记录，不保证旧版本重新运行。

## 代码清理

删除 `FitnessTools` 中 5 个旧注册方法、仅被这些方法使用的 DTO/映射和 `FitnessApplicationService.loadForTool()` 宽聚合入口。旧的 `FitnessSkillRegistry` 如继续保留，则改用与当前 Meal Skill 一致的细粒度 Tool；若确认无生产消费者，则只收敛其依赖，不扩大本轮重构范围。

## 安全边界

`fitness.plan.save` 及其审批恢复路径不变。当前待确认卡片只恢复冻结的 `fitness.plan.save` 调用，因此历史只读 Tool 的删除不影响已有确认操作。当前最新 Agent 必须先通过默认配置/数据库初始化迁移到 18 个 Tool，避免新 Run 解析到不存在的绑定。

## 验收

- 自动化测试确认注册表只包含 18 个当前 Tool，历史键不可发现。
- 默认草稿和 Skill required keys 不含历史键。
- 后端相关测试、架构检查、前端测试和类型检查通过。
- 从页面发起训练计划对话，能进入 `fitness.plan.save` 确认卡；确认卡展示动作名称，候选查询不再因“全身”报错，回复前处理进度行为和字号保持正常。
