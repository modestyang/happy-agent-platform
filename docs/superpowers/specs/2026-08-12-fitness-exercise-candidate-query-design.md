# Fitness 训练计划候选动作查询设计

## 背景

当前 `fitness.plan.skill` 通过 `fitness.exercise.catalog.search` 按目标部位分批搜索动作。动作库只有 59 条时，这种方式仍会造成重复 Tool 调用；一旦模型连续搜索多个部位，还会触发单 Tool 调用次数上限并使整次运行失败。将调用上限从 5 提高到 50 只能延后失败，同时增加无效查询和上下文开销。

动作库未来预计增长到数百条。训练计划生成不应把完整动作库注入模型上下文，也不应依赖模型用关键词逐页探索动作库。本设计将动作选择拆为两层：数据库负责结构化硬约束和有界、多样化候选召回，模型负责结合目标、限制和训练结构从候选中编排计划。

## 目标

- 为全部现有动作补齐可用于计划选择的肌群、器械、难度、动作模式和冲击等级。
- 使用 SQL 根据用户档案和明确限制筛选候选动作。
- 无论动作库有 59 条、500 条还是更多，正常计划生成只向模型返回最多 32 条紧凑候选。
- 正常路径只调用一次候选查询和一次批量详情查询；仅在明确覆盖不足时允许一次有界扩展。
- 保留通用动作搜索 Tool，供动作问答和人工探索使用，但计划 Skill 不再依赖它遍历动作库。

## 非目标

- 不引入向量数据库、Embedding 或语义检索基础设施。
- 不修改移动端动作库公开 API、OpenAPI 合同或页面查询流程。
- 不把步骤、常见错误和图片放入候选结果。
- 不根据“膝盖不舒服”等模糊自然语言擅自推断医学禁忌。
- 不通过提高 Tool 调用上限解决动作召回问题。

## 数据来源与初始化边界

`scripts/seed-exercises/dataset.json` 继续作为动作业务数据的唯一事实来源。现有 55 条普通动作已经包含 `muscleGroups`、`equipment` 和 `difficulty`；本次补充以下信息：

- 逐条复核现有 55 条动作的肌群、器械和难度，并为 4 条 `demoUpgrades` 补齐这些字段。
- 为全部 59 条动作人工判断并补齐 `movementPattern` 和 `impactLevel`。
- 由 `scripts/seed-exercises/seed.mjs` 做完整性校验并幂等写入数据库。

遵循既有《健身动作库数据初始化方案》的约束，Fitness V16 Migration 只增加字段和数据库约束，不硬编码 59 条业务数据。发布顺序为先执行 Migration，再运行 Seed，最后校验标注完整率。Seed 可重复运行，更新同一动作时同步更新选择元数据。

## 数据模型

在 `fitness.exercises` 增加以下列：

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `muscle_groups` | `JSONB` | 主要参与肌群，使用规范化中文标签数组 |
| `equipment` | `JSONB` | 完成动作所需器械，`["徒手"]` 表示无外部器械要求 |
| `difficulty` | `VARCHAR` | `BEGINNER`、`INTERMEDIATE`、`ADVANCED` |
| `movement_pattern` | `VARCHAR` | 单个主要动作模式，用于候选均衡召回 |
| `impact_level` | `VARCHAR` | `LOW`、`MEDIUM`、`HIGH` |

`movement_pattern` 使用下列闭合值：

- `SQUAT`
- `HINGE`
- `LUNGE`
- `HORIZONTAL_PUSH`
- `VERTICAL_PUSH`
- `HORIZONTAL_PULL`
- `VERTICAL_PULL`
- `CORE_STABILITY`
- `CORE_FLEXION`
- `ROTATION`
- `LOCOMOTION`
- `MOBILITY`
- `ISOLATION`

Migration 为非空值增加 JSON 类型、非空数组和枚举检查，但字段初始允许 `NULL`，以支持“先迁移、后 Seed”的部署顺序。候选查询必须排除任一选择元数据缺失的动作，并在结果中返回 `unlabeledCount`，不能静默使用不完整动作。

本次不增加场地、左右侧或医学禁忌字段。场地可以由器械可用性推导；左右侧暂不影响当前计划召回；医学禁忌需要更严格的知识来源和产品规则，不能由动作标签替代。

## 标签判断原则

- `muscleGroups`：描述主要受力肌群，不把所有次要稳定肌群都加入数组。
- `equipment`：列出完成动作必需的全部器械；徒手动作统一使用 `徒手`。
- `difficulty`：综合技术门槛、力量要求、稳定性要求和常见代偿风险判断，不只按负重判断。
- `movementPattern`：每个动作只选择一个对训练编排最有价值的主要模式；复合动作按主导关节和主要训练目的归类。
- `impactLevel`：按落地冲击、快速变向、爆发跳跃及关节瞬时负荷分为低、中、高三级，不代表医学安全等级。

所有判断直接写入 Dataset，接受代码审查和后续人工修订，不在运行时调用模型重新分类。

## 器械规范化

用户档案中的 `available_equipment` 当前是自由文本数组，而 Dataset 使用规范化中文标签。应用层在 SQL 查询前进行确定性规范化：

- 去除首尾空格、统一大小写和常见分隔符。
- 使用显式别名表将“一对哑铃”“可调哑铃”等映射为 `哑铃`。
- `徒手` 不要求用户档案显式声明。
- 一个动作除 `徒手` 外的全部器械都必须包含在用户可用器械集合中。
- 无法识别的用户器械原文保留在查询结果的 `unrecognizedEquipment` 中，不用模糊包含关系猜测。

别名表属于应用代码中的确定性规则，避免 SQL `LIKE` 把相似但不可替代的器械错误匹配。

## 难度规则

训练经验映射为允许的最高难度：

| 用户经验 | 可选动作难度 |
| --- | --- |
| `BEGINNER` | `BEGINNER` |
| `INTERMEDIATE` | `BEGINNER`、`INTERMEDIATE` |
| `ADVANCED` | 全部 |

用户经验缺失时使用保守的 `BEGINNER` 上限，并在 `limitations` 中明确说明；不伪造或回写用户档案。

## 限制条件规则

SQL 只执行能够确定解释的硬限制：

- 器械可用性。
- 经验等级对应的动作难度上限。
- 用户明确表达“避免跳跃”或同义规则时，将允许的最高冲击等级收紧为 `LOW`。

“膝盖不舒服”“腰不好”等描述不直接映射成医学排除规则。候选结果保留 `impactLevel`、动作模式和肌群，模型在计划说明中采取保守选择；如果无法安全判断，应向用户澄清或建议寻求专业人员意见。

## 专用 Tool 合同

新增计划专用只读 Tool：`fitness.exercise.candidates.query`。

Tool 不接收 `userId`，身份仅来自 `ToolExecutionContext.userId`。服务内部一次性读取训练档案、可用器械和训练限制，避免模型先后调用多个档案 Tool 才能完成基础筛选。

输入字段：

- `focusAreas`：可选，最多 3 个目标部位，用于排序和配额，不绕过硬限制。
- `maxImpactLevel`：可选，使用 `LOW`、`MEDIUM`、`HIGH`，只能收紧结果；用户档案中的明确限制始终优先。
- `page`：只能为 `1` 或 `2`。第一页最多 32 条，第二页最多 12 条。

第一页正常返回以下紧凑字段：

- `exerciseId`
- `name`
- `targetArea`
- `muscleGroups`
- `equipment`
- `difficulty`
- `movementPattern`
- `impactLevel`
- `referenceSets`
- `referenceSeconds`

结果元数据包括：

- `eligibleCount`
- `returnedCount`
- `unlabeledCount`
- `appliedFilters`
- `unrecognizedEquipment`
- 按目标部位和动作模式统计的 `coverage`
- `coverageGaps`
- `hasMore`
- `limitations`

候选结果不返回 `steps`、`commonErrors` 或 `imageUrls`。模型确定最终 4 至 8 个动作后，继续使用现有批量 `fitness.exercise.details.query` 一次获取详情。

`difficulty`、`movementPattern` 和 `impactLevel` 直接返回稳定英文枚举码；`targetArea`、`muscleGroups` 和 `equipment` 直接返回 Dataset 中的规范化中文标签。Tool 描述集中解释一次英文枚举含义，不在每条候选中重复返回中英文双份字段。最终训练计划仍由模型使用中文表达。

## SQL 候选召回

查询分为三步：

1. 通过 `WHERE` 排除元数据缺失、难度超限、器械不满足和明确禁止的高冲击动作。
2. 为符合条件的动作计算目标部位优先级，并使用 `ROW_NUMBER() OVER (PARTITION BY target_area, movement_pattern ...)` 生成桶内顺序。
3. 按桶内序号、目标优先级、目标部位、动作模式、名称和 `exercise_id` 稳定排序，第一页取 32 条，第二页取后续 12 条。

这种轮询桶排序会优先返回每个“目标部位 × 动作模式”桶中的第一条，再返回各桶第二条，避免候选集被胸部、徒手或名称靠前的动作占满。相同数据和输入必须得到稳定顺序，便于测试和排查。

`focusAreas` 只影响优先级，不会完全排除其他部位；训练计划仍需一定的辅助动作和全身平衡。结果中的覆盖统计由完整的 eligible 集合计算，而不是只统计当前页。

## 有界扩展

计划 Skill 默认只能调用第一页。当且仅当返回的 `coverageGaps` 表明目标部位或关键动作模式缺失，并且 `hasMore=true` 时，才允许以相同筛选条件调用第二页。

第二页不能放宽难度、器械或冲击硬限制，只返回稳定排序后的后续最多 12 条。Tool 的 `defaultMaxCallsPerRun` 设为 2；新 Agent 版本的全局 `maxToolCalls` 设为 16，为档案、候选、详情、训练摘要、日程和保存链路保留余量，不依赖 50 次上限。

如果第二页后仍存在缺口，模型应明确说明动作库或用户条件导致的限制，而不是继续搜索或私自放宽约束。

## Skill 调整

更新 `fitness.plan.skill` 的运行指令：

1. 确认计划目标和必要约束。
2. 调用 `fitness.exercise.candidates.query(page=1)`。
3. 仅在返回明确覆盖缺口时调用一次 `page=2`。
4. 从候选集中选出计划动作，避免同一模式无意义重复。
5. 一次调用 `fitness.exercise.details.query` 查询最终动作详情。
6. 生成摘要和日程并调用 `fitness.plan.save`。

Skill 不再指示模型“按目标部位分批搜索”，也不再使用 `fitness.exercise.catalog.search` 发现计划动作。通用搜索 Tool 保持原有合同和调用上限，供非计划场景使用。

## 错误与降级

- 没有任何 eligible 动作时返回成功的空候选结果及明确原因，不抛出无法解释的 Tool 异常。
- 元数据缺失动作被排除并计数。
- 用户器械无法识别时报告原值，不猜测等价器械。
- 数据库或 JSON 解析错误仍作为真实 Tool 错误上报，不返回伪候选。
- Tool 次数耗尽错误应保留底层限额原因，避免只暴露通用 `Tool failed`；此项可在既有 Runtime 错误映射范围内一并验证，但不扩大为 Runtime 重构。

## 模块边界

- Agent 仍只能通过 Spring Bean `FitnessTools` 访问 Fitness 数据。
- SQL 位于 Fitness infrastructure 的 Agent 专用 read store，不进入 `agentbuilder/**`。
- 事务边界位于 Fitness service use case；Controller 不参与本次改动。
- 不跨 `fitness` 与 `agent` schema 查询、建外键或开启跨 schema 事务。
- 公开移动端 API 不新增字段，本次不触发 Contract-first API 变更。

## 测试与验收

### Dataset 与 Seed

- 55 条普通动作和 4 条 Demo 动作的五类选择元数据全部存在。
- 数组非空、枚举闭合、Slug/名称/ID 不重复。
- Seed SQL 写入新增字段，重复执行结果一致。
- Demo upgrade 路径同样更新选择元数据。

### Migration

- 新列和 Check Constraints 正确创建。
- Migration 在旧 59 条数据仍未补标时可以执行。
- Seed 后查询确认 59 条记录的选择元数据完整率为 100%。

### SQL 与服务

- 初学者无器械时只返回徒手、初级动作。
- 有哑铃时允许徒手和哑铃动作，不返回需要杠铃或训练凳的动作。
- 一个动作需要多个器械时必须全部满足。
- 明确避免跳跃时只返回 `LOW` 冲击动作。
- 模糊身体限制不触发未经定义的硬过滤。
- 候选按目标部位和动作模式均衡，排序稳定。
- 第一页最多 32 条，第二页最多 12 条，第二页不重复第一页。
- 元数据缺失、器械未识别和覆盖不足均在结果中可见。

### Tool 与计划流程

- Tool 输入 Schema 不含 `userId`，可信身份从执行上下文注入。
- 候选输出不包含步骤、错误或图片等详情字段。
- 正常计划流程只调用一次候选 Tool 和一次详情 Tool。
- 只有明确覆盖不足时才允许第二次候选调用，第三次调用被限额拒绝并保留可诊断原因。
- 保存的计划中所有动作 ID 都来自候选结果且能查询到详情。

### 回归验证

- 运行相关 Fitness service、JDBC、Tool 和计划流程测试。
- 运行 Spotless、全量编译和 architecture tests。
- 验证现有动作问答、页面 Bootstrap、训练计划保存和通用搜索 Tool 未受影响。
- 不修改 `frontend/**`、公开 OpenAPI、Controller 或 Agent schema Migration。

## 发布与回滚

本地和部署环境按以下顺序执行：

1. 应用 Fitness V16 Migration。
2. 运行更新后的 Exercise Seed。
3. 校验 59 条现有动作的选择元数据完整率为 100%。
4. 发布包含新候选 Tool 绑定和新版 `fitness.plan.skill` 的不可变 Agent 版本。

应用代码在 Seed 完成前会排除未标注动作并报告 `unlabeledCount`，因此不会把不完整数据交给模型。回滚 Agent 版本可以恢复旧计划流程；数据库新增列为向后兼容列，不需要破坏性回滚。Dataset 和 Seed 是标签修订的持续入口。

## 成功标准

- 当前 59 条动作全部具有完整、可审查的选择标签并写入 Fitness DB。
- 动作库规模增长不会线性扩大模型上下文；每次计划最多返回 44 条候选，其中正常路径最多 32 条。
- 计划生成不再通过多次关键词搜索遍历动作库，也不依赖将 Tool 调用上限提高到 50。
- 器械、难度等硬约束由确定性代码和 SQL 保证，训练结构与语义权衡由模型完成。
