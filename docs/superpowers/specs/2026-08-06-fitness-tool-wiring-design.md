# 健身工具接线设计

## 目标

让 Agent 工作台中登记的健身 Tool 与真实 Spring Bean 一一对应：可用工具能够由 Agent 运行时调用健身应用服务，不再以预置的“等待接线”数据伪装为工具目录。

## 方案

健身模块在 `fitness-infrastructure` 提供 `FitnessTools` Bean。每个公开的 Tool 方法用 `@AgentTool` 与 `@AgentToolParam` 描述名称、用途、输入、风险、权限和副作用；工具只调用 `FitnessApplicationService`，不直接访问数据库。

Starter 负责组装 Bean：从所有 `AgentToolContributor` 收集注册项，创建 `ToolRegistry`；同时把扫描出的 `ToolDescriptor` 交给管理工作台的组件投影同步器。Agent Builder 不依赖健身实现类，也不持有业务逻辑。

## 首批工具

| Tool key | 行为 | 风险 | 数据范围 |
| --- | --- | --- | --- |
| `fitness.profile.query` | 读取当前用户资料、目标、最近身体指标 | LOW / READ_ONLY | 当前 `ToolExecutionContext.userId` |
| `fitness.workout.query` | 读取当前训练计划与训练完成数 | LOW / READ_ONLY | 当前用户 |
| `fitness.meal.query` | 读取当前饮食记录与当日推荐 | LOW / READ_ONLY | 当前用户 |
| `fitness.plan.generate` | 生成本期计划建议；本期不写数据库 | LOW / READ_ONLY | 当前用户 |

计划生成暂时以确定性建议输出实现，避免在模型 Provider 未配置时制造写入副作用。真正的 AI 计划写入将在已有模型 Provider、Agent 发布和幂等写入能力全部可用后，作为一个单独的 `WRITE` Tool 版本发布。

## 安全与执行

- 所有工具从 `ToolExecutionContext` 获取 userId；模型参数不能传入或替换用户身份。
- 四个首批工具都要求 `fitness.read` scope。
- 工具入参与返回值使用显式 record，扫描器据此生成 JSON Schema。
- 运行时仍通过 `DefaultToolRegistry` 的 scope 校验后才调用反射处理器。

## 工作台同步

启动时使用真实注册项上报工具投影：key、版本、展示名、说明、标签、风险与副作用均来自 `@AgentTool`。已有本地种子中同 key 的“不可用”记录将升级为 `AVAILABLE`，并清除旧的 `reason` 字段。

## 验收

1. Spring 上下文包含四个已扫描的 Tool 注册项和一个 `ToolRegistry`。
2. 以含 `fitness.read` scope 的上下文执行三个查询工具，返回当前用户真实数据库数据。
3. 缺少 scope 时执行被拒绝。
4. `/api/admin/workbench` 中四个 Tool 为 `AVAILABLE`，不包含“等待 Fitness Tool Bean 接线”。
5. 后端模块测试与 Starter 集成测试通过。
