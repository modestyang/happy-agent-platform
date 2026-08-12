# Agent UI 组件体系与移动端体验修复设计

## 背景

当前移动端暴露了三类直接问题：

- 首页快捷卡使用双层 `1fr` Grid，随着屏幕变高被强制拉伸，内容之间出现大面积空白。
- AI 回复中的 Markdown 表格凭借固有宽度撑大消息，导致整个聊天区域横向溢出。
- 前端、Agent 初始化数据和系统提示词中仍有“瘦瘦”品牌残留。

同时，后续对话还会增加确认卡、详情卡、趋势图等结构化内容。如果继续把这些分支直接写进 `AgentRunMessage`，组件会持续膨胀，交互和样式也难以统一。

项目的 OpenAPI 已定义 `AiContentBlock` 判别联合和 `STRUCTURED_COMPONENT` SSE 事件，包含文本、目标摘要、训练计划、动作详情、饮食计划、体重趋势、当前目标报告和确认等领域块。这已经是项目自己的受控组件 Catalog。本设计借鉴 A2UI 的 Catalog、声明式数据、命名 Action、Schema 校验和安全降级思想，但不在本轮引入完整 A2UI runtime 或依赖。

## 目标

1. 首页在高屏手机上保持紧凑信息密度，并增加体重变化趋势。
2. 聊天消息始终限制在屏幕宽度内；表格保持二维结构，只在自身区域横向滑动。
3. 表格、趋势图、动作图和饮食照片复用同一套可访问的放大浮层。
4. 建立可扩展的 `AiContentBlock` renderer registry，新增结构化卡片时不再修改聊天容器主体。
5. 确认操作继续经过现有受信任接口，不允许 Agent 下发任意代码、URL 或 Tool 调用。
6. 所有面向用户的 AI 名称统一为“花爷”。

## 非目标

- 本轮不完整接入 A2UI Surface、DataModel、表达式求值、能力协商或 `web_core`。
- 本轮不调整 Tool 清单、Tool 入参、Tool 返回或业务查询逻辑。
- 本轮不新增依赖、不修改 API 契约、不修改数据库 migration。
- 本轮不实现跨端 renderer，也不声明与 A2UI wire protocol 兼容。
- 本轮不部署、不跑全量测试或完整验收。

## 总体决策

采用“现有领域契约 + A2UI 设计思想”的分层方案：

```text
Agent / Backend
  -> TEXT_DELTA / APPROVAL / STRUCTURED_COMPONENT
  -> message reducer
  -> AiContentRenderer registry
  -> domain renderer
  -> shared UI primitives
  -> trusted action dispatcher
```

当前 SSE 仍以文本和审批事件为主。本轮先建立前端 renderer 与通用 UI 基础层，并把现有 Markdown、确认卡、趋势和媒体接入。未来服务端开始投影 `STRUCTURED_COMPONENT` 时，直接把生成的 `AiContentBlock` 追加到消息块列表，不再更换渲染架构。

## 分层设计

### 1. 传输契约

唯一结构化内容契约继续使用生成的 `AiContentBlock`：

- `TEXT`
- `GOAL_SUMMARY`
- `WORKOUT_PLAN`
- `EXERCISE_DETAIL`
- `MEAL_PLAN`
- `MEAL_RECOGNITION`
- `BODY_TREND`
- `CURRENT_GOAL_REPORT`
- `CONFIRMATION`

通用表格在真正由 Agent 结构化生成时，再按 contract-first 流程新增 `DATA_TABLE` block。当前 Markdown 表格由 Markdown renderer 在客户端转换为同一个 `DataTable` 视觉组件，不提前变更契约。

不新增通用、无边界的 `DETAIL_CARD` wire type。详情在传输层保持领域强类型，在前端视觉层复用 `DetailList`、`MetricGrid` 等基础组件，避免 Agent 把任意 JSON 倾倒到界面。

### 2. Renderer Registry

新增 `AiContentRenderer`，只负责：

- 根据 `block.kind` 查找已注册 renderer。
- 传入统一 `RenderContext`，其中只有白名单回调和导航能力。
- 未注册或校验失败时渲染安全降级卡，不执行未知行为。

Registry 使用 TypeScript 判别联合保持类型收窄。新增一种已存在的 `AiContentBlock` 只需增加一个领域 renderer 和注册项，不改 `AgentRunMessage`。

第一阶段至少注册：

- `TEXT` -> `ChatMarkdown`
- `BODY_TREND` -> `BodyTrendRenderer`
- `CONFIRMATION` -> `ConfirmationCard`

生成式 `CONFIRMATION` block 与现有 `APPROVAL` 事件分别通过适配器转换为同一份
`ConfirmationViewModel`，共享 `ConfirmationCard` 的展示和交互；现有审批事件中更丰富的提案数据保持不变。

训练计划、动作详情、饮食计划和当前目标报告保留清晰的扩展入口；已有页面组件优先复用，不重复实现。

### 3. 通用 UI 基础层

#### `SurfaceCard`

统一结构化内容的内边距、圆角、标题区、状态区和视觉层级。领域 renderer 决定内容，不直接重写卡片壳。

#### `ExpandableSurface`

为高信息密度视觉提供统一的内联入口和浮层：

- 内联内容保持页面布局。
- 明确的“放大查看”按钮，不依赖隐藏手势。
- 浮层支持关闭按钮、点击遮罩、Escape、焦点恢复和滚动锁定。
- 浮层内容自行决定滚动方式；图片/SVG 适配可视区，表格独立横纵滚动。

支持放大的场景：

- 首页和个人页体重趋势。
- 当前目标报告的体重趋势与训练量图。
- 训练计划、动作详情和跟练页中的非 compact 动作图。
- 饮食识别的用户照片预览。

不支持放大的场景：

- 吉祥物、头像和功能图标。
- 动作库 compact 缩略图；其点击行为继续进入详情。
- 纯色快捷卡和低信息密度的运动部位概览。

#### `DataTable`

聊天内表格保持真实表格语义和行列关系：

- 外层严格限制为消息正文宽度。
- 只有表格 viewport 可横向滑动，聊天页面本身禁止横向滚动。
- 表格保留不换行的短字段，避免列错位；必要的长说明列可以在受控最大宽度内换行。
- 边缘渐隐和“左右滑动查看”提示表明局部还有内容。
- 右上角提供放大入口。
- 浮层使用更大可视区域、粘性表头和首列、独立横纵滚动。

`ChatMarkdown` 通过 ReactMarkdown 的 `table` renderer 把 Markdown 表格转换为 `DataTable`，其他 Markdown 节点保持原解析路径。

#### `ConfirmationCard`

确认卡复用 `SurfaceCard` 和 `ActionBar`，领域详情通过受控 slot 展示。当前训练计划 proposal 继续使用现有审批事件数据和审批接口。

确认按钮只能触发 `RenderContext` 中注册的命名行为。前端不接受 Agent 下发的 URL、JavaScript、HTTP method 或 Tool key。

### 4. 消息状态与数据流

#### Markdown 表格

1. 文本增量继续进入当前 assistant 文本块。
2. `ChatMarkdown` 解析 GFM 表格。
3. `DataTable` 渲染内联表格和放大入口。
4. 用户滚动表格时只改变表格 viewport，不改变 `.ai-scroll` 的横向位置。

#### 审批卡

1. `APPROVAL` SSE 事件继续由 reducer 归一化。
2. `AgentRunMessage` 把审批数据交给 `ConfirmationCard`。
3. 用户确认或取消时调用现有审批 handler。
4. 服务端继续验证用户、Run、审批状态和幂等键。

#### 未来结构化组件

1. 服务端将可信的 `AiContentBlock` 投影为 `STRUCTURED_COMPONENT`。
2. reducer 追加或更新对应消息块。
3. `AiContentRenderer` 根据 `kind` 渲染。
4. 未知 `kind` 安全降级并记录诊断信息。

当前 `ComponentEventData` 只有 `messageId + block`，足以追加完整组件。如果未来需要流式更新同一组件，再通过 OpenAPI 增加稳定 `componentId` 和 `UPSERT/REMOVE` operation；本轮不提前实现完整 Surface 状态机。

## 首页布局

首页从“填满剩余视口”改为“内容自然高度、顶部紧凑排列”：

1. 问候区。
2. 当前目标卡。
3. 紧凑体重趋势条。
4. 两行快捷卡。

体重趋势直接复用 bootstrap 的 `bodyRecords` 和 `WeightSparkline`，不新增接口。趋势条展示标题、首末体重和曲线，并支持放大；无体重记录时显示现有空状态。

快捷卡使用内容驱动的固定高度范围，不再使用两行 `1fr` 吸收全部剩余空间。高屏手机保留自然底部留白；只有内容真实超过短屏可用高度时才允许页面纵向滚动。

## 聊天宽度约束

- `.ai-scroll` 禁止横向滚动。
- `.conversation`、`.message`、`.message-body` 和 Markdown 根节点都允许 flex/grid 子项收缩，并限制最大宽度。
- 普通段落、长链接和 inline code 使用安全断行。
- `pre` 代码块和 `DataTable` 只在自身 viewport 内横向滚动。
- assistant 图标保持固定宽度，不参与正文宽度计算。

## 品牌统一

所有用户可见名称统一为“花爷”：

- 前端静态文案和加载状态。
- Agent baseline 中的草稿名称、Prompt 展示名和系统提示词。
- Java fallback 初始化数据。
- 相关测试夹具和断言。

技术标识如 `fitness.coach`、数据库键、API 路径和类名保持不变。

为兼容已有历史回复，assistant Markdown 展示层将旧品牌词规范化为“花爷”；用户输入不做替换。后续发布的新 Prompt 从源头只使用“花爷”。

## 可访问性与安全

- 所有放大入口都有可理解的 `aria-label`。
- 浮层使用 dialog 语义，打开后聚焦关闭按钮，关闭后恢复触发元素焦点。
- Escape 和遮罩关闭不触发业务 Action。
- 表格保留原生 table、thead、tbody、th、td 语义。
- 未知结构化组件不渲染为 HTML，也不解析为代码。
- 所有确认行为仍由服务端授权和幂等校验，UI 描述不携带任意执行能力。

## 错误与降级

- 空表格：显示“暂无表格数据”，不打开空浮层。
- 未知 block：显示紧凑的“不支持此内容”卡片，聊天正文继续可用。
- 图片加载失败：继续使用现有动作 SVG fallback；用户照片显示加载失败状态。
- 趋势数据为空：使用现有记录空状态。
- 浮层内容超高：仅浮层内部滚动，不影响底层页面位置。

## 测试策略

按 TDD 分别覆盖：

1. 高屏首页快捷卡不再随剩余高度拉伸，并渲染体重趋势。
2. Markdown 表格被 `DataTable` 接管，表格容器可局部滑动且存在放大按钮。
3. 消息容器不产生页面级横向溢出约束。
4. `ExpandableSurface` 的打开、关闭、Escape 和焦点恢复。
5. 非 compact 动作图可放大，compact 缩略图保持原导航语义。
6. 报告趋势和饮食照片接入统一浮层。
7. Renderer registry 对已注册 block 正确分派，对未知 block 安全降级。
8. 确认卡仍调用原审批 handler，状态展示不回归。
9. 用户可见“瘦瘦”文案消失，assistant 历史文本被规范化，用户消息保持原文。

验证范围仅包括相关 Vitest、TypeScript、定向 ESLint、前端 build 或格式检查，以及一轮本次改动代码审查。本轮不部署、不执行全量后端测试或完整验收。

## 后续演进

满足以下任一条件时，再正式评估 A2UI runtime：

- 同一 Agent UI 需要 React、iOS、Android 等多个 renderer。
- 需要接收第三方 Agent 动态生成的通用 Surface。
- 需要完整 DataModel 双向绑定、客户端函数或组件 capability 协商。
- 需要与 A2A/MCP 上的 A2UI 客户端直接互操作。

届时优先编写 `AiContentBlock` 与 A2UI Catalog 的 adapter，或迁移到官方 `web_core`；不让领域服务直接依赖前端框架。
