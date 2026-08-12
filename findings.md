# Findings

- 正式仓库工作区初始状态干净，HEAD 为 `eec807d fix: finalize local experience flow`。
- 正式前端为 React 19 + React Router + Lucide + Vitest，主要页面集中在 `frontend/src/App.tsx` 与 `frontend/src/app.css`。
- 正式版本已有真实登录、首页记录抽屉、训练完成、AI 请求、动作详情、目标报告等 API 连接。
- 当前首页仍有“今天的节奏”，四个功能卡片以文字为主；导航未强化中间 AI Tab。
- 当前动作指导已经有四宫格信息结构，但需要把视觉焦点从文字块升级为图片/GIF 演示优先。
- demo 的动作图不是外部版权素材，而是代码生成的 SVG 姿态插画；可在正式项目里用独立组件承载，并优先显示后端 `imageUrls`。
- 正式前端基准测试为 6/6 通过，后续可以通过新增失败测试执行 TDD。
- 浏览器验收确认数据库的 `imageUrls` 目前是含文字的 SVG 占位图，不是真实动作素材；正式页面应识别该占位格式并使用代码姿态插画兜底，避免大段占位文字溢出卡片。
- 正式前端当前没有 `/admin` 路由或管理台组件；`App.tsx` 仅承载移动端五个 Tab。
- Agent Builder 已有 Framework/Provider/Model/Skill/Hook/Memory/Prompt/Output/Evaluation 的强类型、JDBC Repository、默认配置解析和两套框架适配器，但 `starter` 尚未暴露任何管理 API。
- `agent` schema 当前有版本、评测/探测任务、幂等与强类型组件表，没有 Agent 草稿、运行/Trace 的可操作读模型。
- demo 管理台的可复用视觉语言是：浅蓝灰画布、窄侧栏、白色实体卡片、深海军蓝文本、克制蓝/绿/琥珀状态色；不复制其 demo repository 和权限假数据。
- 用户截图要求计划卡片左侧动作图容器与右侧标题、要点、错误区整体等高；应由 grid stretch 和图片容器 `height:100%` 实现，而不是固定图片比例。
- 2026-08-07 浏览器验收确认：健身端和 Agent 工作台核心链路可用，但饮食记录没有图片上传/识别入口，饮食推荐没有赞踩反馈，目标报告仍为对话文本。
- 当前跟练使用 Web Speech API，但 `speak()` 每次先 `cancel()`，连续倒数可能互相打断；应改为基于训练状态 transition 的去重队列。
- `ComponentType` 在 `type` 改变时只重新加载列表，没有清空 `selected`，因此模型详情会残留到技能/Hook 路由。
- 当前工作台把已发布版本运行态与草稿发布准备度混合展示，容易出现“运行准备就绪”同时存在待完成组件的矛盾文案。
- 当前 `AdminWorkbenchController` 直接注入 `FitnessApplicationService` 并认证 `FITNESS_SESSION`，与仓库 AGENTS 的“双独立 bearer boundary”约束冲突；这正是用户看到“工作台暂时无法打开”的根因。
- `workbench-components-demo.html` 的可取之处是卡片检索/筛选、详情编辑、工具只读 Schema、Skill 与 Tool 依赖校验、浮动保存条；其不覆盖真实调试与追踪，因此本项目必须保留后两者。
- 完整 `public-v1.yaml` 已经定义上传票据、识别 Job、饮食记录和当前目标报告等方向，但当前 `/api/app` 体验切片尚未实现对应闭环；整改必须坚持 contract-first。
- 2026-08-10 用户反馈手工填写首次目标正常，因此上轮自动化失败不能继续作为产品缺陷结论；本轮将重点核对日期控件输入事件与自动化方式。
- 首次目标表单使用原生 `input type="date"`，React 仅检查 `targetDate` 非空；此前自动化虽让 DOM 显示日期，但提交时仍命中空值校验，更符合自动化未正确触发受控组件状态，而非日期格式业务缺陷。
- Agent 管理后台当前 Provider 页面只支持编辑既有 Provider 与保存加密凭证，没有新增 Provider 的页面入口；现有运行时采用 OpenAI-compatible 调用方式，MiniMax 是否只需目录/模型配置仍待核实。
- 下一步需核对 Agent 初始数据、模型绑定、运行时请求格式，以及 MiniMax 官方兼容地址和模型标识。
- 真实页面复核中，普通体重/腰围输入可正常进入 React 状态；自动化对原生日期控件使用 ISO `2026-10-10`、连续数字和分段按键均未改变控件显示，提交仍收到空日期。这是浏览器自动化无法驱动原生 date 控件的伪异常，应撤销上一轮“问题 1”缺陷结论。
- MiniMax 中国区官方文档当前给出的 OpenAI-compatible base URL 为 `https://api.minimaxi.com/v1`；当前语言模型包含 MiniMax-M3、M2.7 系列等，M3 面向 Agent、工具调用和长上下文，适合作为本项目文本 Agent 的默认候选。
- 现有 Agent Runtime 已固定调用 `{endpoint}/chat/completions`，模型可显式配置 `model` 字段，因此协议层可以直接复用 MiniMax OpenAI-compatible 接口。
- 用户明确产品口径：健身应用所有 AI 能力统一由同一个 `fitness.coach` Agent 和同一个底层模型提供，不接受分别维护文本模型与视觉模型。
- 当前实现没有完全满足该口径：对话和报告读取已发布快照，三餐生成读取已发布 Agent 选择但再查实时组件投影，图片识别甚至直接读取可变草稿；Provider/模型切换后存在能力间配置漂移。
- MiniMax-M3 官方说明支持 OpenAI-compatible Chat Completions 的文本、图片和视频输入，因此可作为单一多模态模型候选；各任务仍可保留独立 Java handler，但配置必须统一解析同一份不可变已发布 Agent 快照。
- MiniMax 实际会在 `content` 中包含 `<think>` 推理块，且结构化调用会忽略 `response_format.json_schema`、使用 Markdown JSON 围栏；运行时必须清理推理内容和完整围栏，并继续做服务端闭合结构校验。
- 真实页面目标报告在增加输出上限和明确字段提示后成功生成完整结论、评分、指标、趋势、优缺点和行动项。
- `application-local.yml` 原先没有开启 local media，导致拍照识别在上传工单阶段被空 OSS 配置拦截；启用本地媒体后，真实页面上传、MiniMax 视觉识别、结果回填与保存成功。
- 新用户空饮食建议页没有“立即生成/重试”按钮，前端也未调用已有 `generateDailyMealPlan` operation；普通用户无法从页面主动触发三餐生成，是当前主要功能完整性缺口。
- 新用户 AI 回复偶发把 Markdown 加粗标记渲染成裸 `*`，信息本身可读但首屏观感不够稳定，适合改为纯文本安全渲染或在展示前做轻量 Markdown 规范化。
- 管理台 Trace 首页要求手工输入用户 UUID，纯小白无法从页面获知该值；个人应用可直接默认展示最近会话或提供用户名选择，无需引入复杂检索平台。
- 上游 `StreamingChatClient` 已按 MiniMax SSE 逐块读取，但健身 Runtime 与 Playground 都传入空回调并等待聚合结果，因此卡顿根因位于应用运行层和 HTTP/UI 边界，不是 Provider 不支持流式。
- `public-v1` 与 `admin-v1` 已定义 Run 创建和 SSE 读取事件，但 starter 尚无对应控制器实现；协议含 `WAITING_APPROVAL`/`APPROVAL`，却没有批准/拒绝写回 operation，需先补 OpenAPI。
- `ChatMarkdown` 只解析段落、列表、`**bold**`，裸星号来自不完整自制解析器；完整 GFM 渲染应替换该组件，且默认禁用原始 HTML。
- `fitness.plan.generate` 是只读当天建议且明确不落库；Fitness service/store 也没有创建训练计划端口。现有 `workout_plans` + `workout_plan_exercises` 足以存当天和逐日周计划，但需要新增业务用例、幂等写入与 Agent 确认状态表。
- MiniMax 官方 OpenAI-compatible 文档确认支持 stream、reasoning 分离和多轮 Tool Call；本实现仍只向用户公开安全执行摘要，不直接转发原始推理。
- agent schema 已使用到 V11，因此确认/流事件 migration 必须从 V12 开始；契约覆盖 fixture 的实际文件名为 `public-coverage.json` 与 `admin-coverage.json`。
- `agent_runs` 已有 `user_id`、`conversation_id`，`agent_run_events` 已有单 Run 单调 sequence；最小实现可扩展现有表存事件 JSON，而无需复制 Run/会话模型。
- Trace 小白问题实际位于 `ConversationTracePage.tsx`：`userId` 为空时禁止查询；Admin API 也只暴露按用户 UUID 的会话列表。Run 列表本身已经默认按开始时间倒序，因此应让 Trace 首屏先展示最近 Run/会话，再把 UUID 留作可选筛选。
- 前端尚未封装 daily meal plan generate operation，只有 generated 类型；P1 需要在 `api.ts` 接上现有 `/api/v1/app/meal-plans/daily/generate` 并让空状态复用轮询。
# 2026-08-10 Provider / Model 可维护性审计

- 当前 Provider 和 Model 都来自 `agent_component_projection` 预置投影；页面只能编辑既有 Provider endpoint/凭据和既有 Model 元数据，没有创建入口。
- 数据层其实已经在 `model_catalog` 中表达 `provider_key + provider_version + model_id`，但管理投影的 Model 配置和页面没有把这种归属关系作为一等交互。
- `agent_drafts` 同时保存 `provider_key` 与 `model_key`，目前缺少数据库外键/服务校验来保证所选 Model 属于所选 Provider。
- Provider 凭据与公开配置已经分离：`agent_provider_credentials` 可继续复用 AES-GCM 加密存储；新增 Provider 不需要改变密钥安全边界。
- 当前通用组件更新 API 只能更新已存在投影；要支持无需发版的新增，需要为 Provider/Model 增加明确的创建、更新与引用保护行为，而不是继续扩大通用 JSON 编辑器。
- 发布检查已经有 `requireModelProviderAlignment`，运行时快照也验证 Model 的 `providerKey` 与 Agent 当前 Provider 一致；因此核心运行时边界是正确的，缺口主要在创建 API、持久投影约束和前端联动。
- 当前 Provider 运行配置只要求 endpoint，实际请求统一追加 `/chat/completions`，天然适合把首版协议固定为 OpenAI-compatible；不需要在首版引入任意协议插件。
- 已发布 Agent 会冻结 Provider、Model 和凭据快照。后续编辑目录不会改写旧发布版本，这一不可变语义应保持；Agent 只有重新发布后才使用新配置。
- 当前模型配置已包含 `providerKey`、可选实际 `model` 名称以及 streaming/toolCalling/vision 能力。手动创建页面可将这些从不透明 JSON 提升为正式字段。
- 当前 Provider/Model 种子由 Java 启动逻辑写入且 `ON CONFLICT DO NOTHING`。改为可维护目录后，种子可以继续作为初始数据，但用户新增和编辑必须完全落库，不再要求代码发版。

## 2026-08-10 `fitness.plan.save` 控制台缺失

- 现场数据库的 `agent_component_projection` 只有 5 个 Tool：profile/workout/meal/feedback/plan.generate，确实没有 `fitness.plan.save`，所以工具页稳定无法显示它。
- `FitnessTools.savePlan` 已通过 `@AgentTool(key = "fitness.plan.save")` 正确注册，`AdminWorkbenchConfig` 也会扫描整个 `FitnessTools` Bean 构造 `ToolRegistry`；确认流程通过 `ToolRegistry.invoke("fitness.plan.save", ...)` 实际执行，运行能力本身存在。
- 根因是工作台 Tool 列表依赖手写的 `seedDefaults()` 投影，而该方法只登记到 `fitness.plan.generate`；新增 save 工具后没有同步投影。
- 启动时的 `reconcileRuntimeCapabilities` 明确只同步 Skill/Hook，不会把 `ToolRegistry` 描述符投影到工作台，因此重启也无法自动补齐。
- 这是目录同步缺陷，不是工具注册或保存流程失效；合理修复应让 Tool 投影来自扫描后的运行时清单，并补迁移/兼容逻辑，而不只是往当前数据库临时插一行。
- 用户指出更深一层问题成立：当前健身聊天 `AgentRuntimeConversation` 直接调用 `StreamingChatClient`，没有把 `config.toolKeys()` 解析为模型 Tool Schema；Tool 只被 Skill 在模型调用前确定性执行，因此模型本身不知道任何 Tool。
- Core 已有 `ToolBinding.approvalPolicy`（NEVER/RISK_BASED/ALWAYS）和 `ResolvedTool.approvalPolicy`，但 AgentScope `StrictAgentTool` 当前无条件执行 handler，完全没有消费 approvalPolicy；这是“模型可见、确认后执行”缺失的核心接线点。
- 健身聊天目前给 Tool 上下文的 scope 只有 `fitness.read`；确认后的保存路径由 `FitnessAgentRunService` 单独使用 `fitness.write` 和 `operationId=approval.execute` 调用。可复用这个可信执行边界，不能把 write scope 直接授予模型。
- 当前训练计划确认由输入文本启发式检测并由编排服务冻结提案，不是由模型真实发起 Tool Call。目标修复应以模型提交的 `fitness.plan.save` 参数创建待确认记录，确认后执行同一冻结参数；启发式只能在迁移期保留兼容，不能继续作为主路径。
- `/api/admin/workbench` 的 `WorkbenchSnapshot` 同时返回 overview、全部 Agent、全部通用组件、Provider 和近期 Run。AgentList、AgentEditor、Provider、每一种目录页、Playground、Overview 共 7 个页面都依赖它，任何一条坏数据在 DTO 映射/序列化时都会让所有页面一起失败，用户指出的故障域问题成立。
- `AdminWorkbenchPort` 只有 `snapshot()`、通用 `updateComponent()` 等少量入口，导致应用服务与前端都围绕 `ComponentView(type, componentKey, config Map)` 工作；这不是单纯命名问题，而是资源边界被抹平。
- AgentEditor 已在前端临时实现 Provider→Model 筛选，但数据仍来自整个 snapshot；Provider/Model 归属没有专用查询/API/数据库约束，页面联动无法成为可靠边界。
- 现有正式 `admin-v1.yaml` 已经存在 Provider、Model、Tool、Skill、Hook 等独立资源风格的契约定义，当前 `/api/admin/workbench` Controller 是另一套未按正式契约拆分的捷径。修复应回归资源型 API，而不是再新增一个聚合 DTO。
- Overview 只需要独立统计、当前 Agent 摘要和最近 Run；Playground 只需要“可调试 Agent 就绪视图”。它们应使用小型专用读模型，不能重新拼接全量目录。
- 仓库已有一套 `provider_catalog/model_catalog/...` 类型化版本目录和大而全的 `admin-v1` 契约，但现场这些表全是 0 行，当前产品实际只使用 `agent_component_projection` 的 19 行数据。直接启用旧目录会引入版本、checksum、payload、健康与分页等平台化复杂度，不符合个人应用目标。
- 推荐新建简单的独立工作台资源表并迁移现有投影数据：Provider、Model、Prompt、Skill、Hook、Framework、Memory 各自拥有明确字段；Tool 则直接来自 Spring Tool 扫描清单，避免代码能力与手写数据库投影再次漂移。
- 推荐停止读写 `agent_component_projection`，但暂不在 migration 中删除历史表；这既移除生产概念依赖，又避免破坏性迁移。后续确认数据稳定后再单独清理。
- Tool 审批主路径应在 OpenAI-compatible 流式 tool_calls 解析层实现：模型收到已绑定 Tool Schema；READ 工具可按 scope 执行，`fitness.plan.save` 的 WRITE/MEDIUM 描述符触发 WAITING_APPROVAL，参数校验并冻结后写 approval；只有 `approval.execute` 才获得 `fitness.write`。

## 2026-08-10 管理后台交互审计

- `Models.tsx` 把新增表单直接插在模型卡片列表上方，展开时会改变主布局并把已有模型整体下推；应改为不参与文档流的模态弹层。
- 模型表单把 streaming、tool calling、vision 三项同权平铺。流式输出是当前聊天产品的统一要求，不适合作为每个模型的日常开关；工具与视觉更适合作为能力声明，以紧凑高级选项或只读能力标签呈现。
- Trace 首屏同时出现最近运行、UUID 查询、空会话列表和空详情，占用了四个视觉焦点；用户无法判断应先点最近 Run 还是输入 UUID。
- 当前 Trace 的会话查询仍要求用户手工提供 UUID，与“纯小白管理个人应用”的目标冲突。页面应默认展示最近会话或让最近 Run 成为一级入口，UUID 仅保留为次级筛选。
- 后台现有视觉语言为浅灰画布、白色卡片和克制状态色，本轮应做精炼的信息架构与组件一致性，不改成新的主题或平台化监控大盘。
- 真实模型页现有 3 张卡片，每张卡片的停用按钮挤在底部元数据行右侧，既打断“Provider · Model ID”的阅读，也让危险操作成为卡片最显眼的常驻动作。
- 提示词和技能页都只有搜索、状态筛选与既有资源编辑，没有新增入口；后端独立资源 API 也只有 list/update，缺少 create，因此必须先补契约和持久层创建用例，不能只做前端假按钮。
- 提示词/技能的现有详情编辑结构可以复用字段定义，但新增应使用与模型相同的模态容器：提示词填写 key、名称、说明、模板；技能填写 key、名称、使用/禁用时机、内容和依赖工具。
- 当前模型默认数据包含三个模型，其中 MiniMax M3 实际承担统一 Agent；能力标签应帮助管理员判断模型是否适配，而不应把底层传输策略伪装成普通偏好开关。
- 用户进一步明确 Trace 不应暴露 UUID 搜索。UUID 是内部关联键，正常管理流程没有保留筛选入口的产品价值，应从页面移除。
- `RunTracePage` 当前把状态、耗时、Token、成本四个指标卡以及 input/output/事件纵向时间线作为主体，确实是技术日志视角；它没有把 Run 放回触发它的用户消息与 Agent 回复上下文中。
- Trace 的正确主模型应是对话：用户消息与 Agent 回复组成主轴，每条 Agent 回复下附属一个可展开的执行过程，内部再展示模型、工具、确认、结果和耗时；原始事件序列只作为次级信息，不单独抢占主视图。
- 真实 Run 详情确认当前问题：Markdown 输出被压成单个纯文本段落；22 条事件中包含重复的 TOOL_STARTED/TOOL_COMPLETED 和完整 TOKEN 输出，技术事件与用户可读结果重复。新设计需合并成业务阶段并复用 Markdown。
- `ConversationMessage` 已包含可选 `runId`，因此会话页可以把执行过程挂在对应 Agent 回复下并按需读取现有 Run Trace，不需要扩大会话详情响应或新增事件聚合表。
- 后端 `agent_prompts`、`agent_skills` 已是独立表，字段完整，创建只需新增 DTO/Port/Service/Store/Controller，不需要 migration；这符合开发期保持单一 V1 的约束。
- `JdbcAdminResourceStore` 已有 Provider/Model 创建时的冲突映射模式，可直接复用于 Prompt/Skill 唯一 Key 冲突；Skill 的 `required_tool_keys` 已是 JSONB。
- 模型后端 DTO 仍允许 `supportsStreaming=false`。本轮前端会固定发送 true，同时服务端创建/更新应归一为 true，避免其他调用方绕过统一流式要求。
- `AdminResourceService` 当前只依赖资源 Port；Skill 创建的工具校验应注入 `ToolRegistry` 并基于 `descriptors()` 建立可用 Key 集合，不能查询已废弃的数据库工具投影。
- 会话查询实际由 `JdbcRunTraceRepository` 直接提供，最小改动是把 `listConversationSummaries(UUID, page, size)` 改为无用户条件的 `listRecentConversationSummaries(page, size)`，无需牵动 Agent 资源 Port。
- 当前 `admin-v1.yaml` 仍主要描述 `/api/v1/admin/**` 平台契约，没有覆盖已经运行的简化 `/api/admin/providers|models|prompts|skills|traces` 页面 API。这是既有 contract-first 偏差；本轮应把实际个人工作台资源补入正式契约和 coverage，而不是继续只改 Java/TS。
- 契约 lint 对所有 POST 强制要求 Idempotency-Key 以及 400/409/422 错误闭包。Prompt/Skill 创建前端与控制器需要遵循此门禁；不能为了个人应用方便绕过正式契约规则。

## 2026-08-11 通用 Agent 调试台根因

- 数据库中的 `food-recomend` 已是 `PUBLISHED v2`，因此选不到并非未发布或缓存问题。
- `PlaygroundPage` 在两个位置额外过滤 `agentKey === 'fitness.coach'`。
- `AdminPlaygroundV1Controller` 同样拒绝所有非 `fitness.coach` 请求；仅删除前端过滤会让请求变成 400。
- 现有 `PublishedAgentPlaygroundRuntime` 已能读取任意 Agent 的不可变发布快照，但旧入口同步返回且丢弃流式 chunk；它适合作为通用 Agent 流式运行的最小扩展点。
- 健身 Agent 的运行器包含用户档案、Skill/Hook、Tool 与确认计划逻辑，不能用于任意 Agent；正确边界是 Controller 下方按 Agent Key 分流并共用持久 SSE/Trace 表。

## 2026-08-11 Harness 统一运行时审计

- `agentscope-adapter` 与 `spring-ai-alibaba-adapter` 都包含真实框架依赖与 `AgentFrameworkAdapter.run(RunRequest)` 实现，并非占位代码。
- `starter` 没有依赖两个 Adapter 模块，生产运行入口也没有创建或查找任何 `AgentFrameworkAdapter`；Adapter 当前只被模块自身测试使用。
- 通用调试与健身对话都直接创建 `StreamingChatClient`，后者通过 `HttpURLConnection` 请求 Provider 的 `/chat/completions`。
- `frameworkKey` 当前只参与发布配置、Trace 字段和 `fitness.coach` 的值校验，没有决定实际执行框架。
- 数据库 `agent_frameworks` 当前只登记 `agentscope`；SAA 模块存在但管理后台不可选。
- `RunRequest` 已有模型、Tool、Skill、Hook、Memory 和可信 Tool 上下文，但 `RunEvent.Type` 只有 RUN/MODEL_DELTA/TOOL/RUN 事件，缺少上下文、记忆、Skill、Hook、思考和模型调用边界。
- `food-recomend v3` 的发布配置包含 `skillKeys=[fitness.meal.skill]`，技能表内容是“只推荐用户水果”；通用运行时只加载 Prompt，证明 Skill 未进入模型上下文或 Harness。
- 当前 Adapter 锁定 AgentScope Java 1.0.12；它只是现状和迁移起点，不再作为目标版本。
- AgentScope 当前 Bridge 错误地把整个 `REASONING` 消息压成 `MODEL_DELTA` 文本，没有按 Block 类型翻译，也没有保留 response/block 生命周期。
- Spring AI Alibaba 1.1.2.2 没有与 AgentScope 一一对应的统一 Block 基类。它建立在 Spring AI 消息模型上：`AssistantMessage` 提供文本、metadata 和 `ToolCall` 列表，`ToolResponseMessage` 提供工具结果，`Generation` metadata 提供 finishReason；SAA 再通过 ReactAgent/Graph 节点与 Hook 表达 Agent 生命周期。
- SAA 当前 Bridge 只读取 `Message.getText()` 并发出 `MODEL_DELTA`，工具调用由 ToolCallback 旁路记录，因此同样丢失模型回复块和响应边界。
- MiniMax 等 OpenAI-compatible Provider 若没有被 Spring AI 映射为独立 reasoning 字段，可能把推理放在 `<think>` 片段中；这种 Provider 差异必须在 Adapter 的流式分类器中消化，Core 不识别厂商标签。
- 用户提供的 SAA 示例使用 `ReactAgent.stream(question, config)` 获取 `Flux<NodeOutput>`，并以 `StreamingOutput.outputType` 区分 `AGENT_MODEL_STREAMING`、`AGENT_MODEL_FINISHED` 与 `AGENT_TOOL_FINISHED`；这比当前项目的 `streamMessages()` 更适合作为完整 Trace 的原生输入。
- 示例优先读取 `AssistantMessage.metadata["reasoningContent"]` 作为显式思考增量；普通文本先缓冲，如果同一轮随后出现 ToolCall，则归类为 `PRE_TOOL_REASONING`，否则在模型轮结束时归类为 `ANSWER`。
- 官方 SAA v1.1.2.2 release 明确增加 `_FINISHED` OutputType 的完整流式节点输出；官方仓库也把 Graph 定义为提供状态、流式与长运行时能力的底层，因此 Adapter 应消费 NodeOutput，而不是只消费最终 Message 文本。
- `reasoningContent` 优先、轮次缓冲回退的识别规则应局限在 SAA Adapter；统一 Core 只接收 Thinking/Text/ToolCall/ToolResult Block 生命周期。
- SAA 官方消息文档确认 `AssistantMessage` 使用 text、metadata、toolCalls、media 四类字段，Tool 结果位于独立 `ToolResponseMessage`，没有 AgentScope 风格的 Block 联合类型。
- SAA issue #4649 截至核对时仍为 open，并明确 1.1.2.2 的 `AGENT_MODEL_STREAMING` 无法区分思考和最终答案。因此附件的 Turn Buffer 可用于维护响应轮边界，但普通文本随后出现 ToolCall 不能被当作事实上的 Thinking Block。
- 百炼官方 OpenAI-compatible 示例明确把 `reasoning_content` 与 `content` 分开；只有这种显式字段、框架原生 ThinkingBlock 或明确 Provider 标签才能产生统一 Thinking Block。
- Core Block 按 AgentScope 的丰富度设计，但增加 fidelity 与 `CapabilityDegraded(REASONING_UNAVAILABLE)`；SAA 能填则填，不能填时透明降级而非猜测。
- 同一框架不能保证完全抹平不同模型服务商：官方 Model/ChatModel 通常统一文本、工具调用和基础 metadata，但 reasoning 扩展、流式 ToolCall 分片、finishReason、usage 与输出 media 仍可能缺失或保留为 Provider metadata。
- 正确边界是两级归一化：框架 Connector 先把原始响应变成框架消息，Framework Adapter 内的 response normalizer 再变成 Core Block；Core 不按 Provider Key 分支。
- 对手动新增 OpenAI-compatible Provider，扩展识别应基于实际字段/标签能力，而不是硬编码供应商名称；无法识别时发出透明降级事件。
- AgentScope Java 2.0.2 已在 Maven Central 发布 `agentscope-core` 与 `agentscope-harness`；2.0.6 Java artifact 当前不存在。目标 Adapter 应迁移到 2.0.2 `HarnessAgent.streamEvents()`，不能继续围绕 1.0.12 API 扩建。
- 用户提供的 AgentScope 2.0.6 文档属于 Python SDK，示例导入为 `agentscope.message`。其“完整 Message + 增量 Event”“一次 reply 的事件可重建一个 Assistant Msg”“Block start/delta/end”“ToolCall/ToolResult 状态机”适合作为 Core 协议参考，但 Python API 不能直接用于 Java 模块。
- AgentScope Java 2.0.2 原生具备 HarnessAgent、强类型 ContentBlock/AgentEvent、Context Compaction、Permission、Memory、Skill 与 Middleware；Core 应统一跨框架产品语义，AgentScope Adapter 应复用这些 Harness 设施而不是重复实现。
- AgentScope 的 Block Type 是统一目标模型，不是所有 Provider 都能无损填充的保证；未知字段名、正文内 `<think>`、不完整兼容或 Connector 未支持的原生协议仍会退化为 TextBlock 或丢失扩展信息。

## 2026-08-11 手机输入框自动缩放验收修复

- `frontend/index.html` 已使用 `width=device-width, initial-scale=1.0`，且没有禁用用户缩放；viewport 本身不是异常来源。
- `frontend/src/app.css` 的 `button, input, select { font: inherit; }` 让控件继承父级 `label` 的字号；通用 label 为 12px，首次目标 `.onboarding-card label` 为 11px。
- 首次目标体重控件没有独立字号，因此手机 Safari 聚焦小于 16px 的表单控件时会触发页面自动放大；同一模式也影响登录、记录、搜索和聊天输入。
- 修复方向应是将移动端可编辑控件的实际字号统一到至少 16px，并保留现有 viewport 的主动缩放能力；不应通过禁止缩放掩盖问题。
- 所有健身端页面都位于 `.phone` 容器中，而 `/admin` 不使用该容器；用 `.phone :is(input, select, textarea)` 设定 16px 可以覆盖登录、首次目标、抽屉、搜索、聊天及个人资料表单，同时不改变桌面管理后台。
- 当前 `.composer input`、`.onboarding-card input` 和 `.training-profile-form input` 有更高特异度的 11–12px/继承规则；修复规则需放在这些规则之后或使用足够明确的移动端作用域，避免被后续覆盖。
- `App.test.tsx` 已真实渲染首次目标页；可直接对目标体重控件的 computed style 断言 16px，测试生产 DOM 与加载后的真实 CSS，不新增源文本正则测试。
- Vitest 默认转换组件中的 CSS import 但未把样式注入当前 jsdom；直接读取渲染控件的 computed style 得到浏览器默认 16px，无法捕获生产 CSS 的 11px 覆盖。回归测试必须把真实 `app.css` 内容作为 `<style>` 注入测试 DOM，再检查首次目标结构下的计算字号。
- 部署后在 390×844 真实页面打开身材记录抽屉：体重输入框计算字号为 16px；聚焦前后 `visualViewport.scale` 都是 1，`visualViewport.width` 与 `innerWidth` 都保持 390px。
- 页面使用的 viewport 仍为 `width=device-width, initial-scale=1.0`，没有加入 `user-scalable=no`；浏览器控制台 error 为 0。

## 2026-08-11 AI 聊天缩放与自动跟随修复

- `AiPage` 的所有用户消息、assistant 占位消息和每个 SSE 事件都会更新 `messages`，但组件没有滚动 ref 或跟随消息变化的 effect，因此提交、流式增量和恢复消息都不会主动滚到底部。
- DOM 结构是 `.ai-page` → `.ai-scroll` → `.conversation`，输入区 `.fixed-composer` 与 `.ai-scroll` 同级；需要核对 CSS 后把滚动动作绑定到真实可滚动节点，而不是 `window`。
- 上轮 `.phone :is(input, select, textarea)` 位于 `.composer input { font-size: 12px; }` 之后，按相同特异度应得到 16px；用户仍观察到放大，需用当前聊天页实际计算样式进一步确认，而不能假定仍是同一根因。
- 当前 390×844 运行页面的聊天输入实际计算字号是 16px，聚焦前后 `visualViewport.scale` 都为 1，页面及 `.ai-scroll` 横向宽度也没有溢出；因此现有通用 16px 规则在 Chromium 生效，用户看到的放大更可能是 iOS Safari 的自动文字调整，而非当前规则仍被 `.composer input` 覆盖。
- 真实长会话中 `.ai-scroll` 的 `scrollHeight=2570`、`clientHeight=534`，但进入页面后 `scrollTop=0`，直接复现了“没有跟随到底部”；这也确认 `.ai-scroll` 是应操作的滚动节点。
- `.ai-page` 当前计算 `text-size-adjust: auto` 且没有 WebKit 专用值；在不禁用 pinch-to-zoom 的前提下，应把聊天页自动文字调整固定为 100%，并把 `.composer input` 自身明确为 16px，避免依赖文件末尾的通用覆盖规则。
- 自动跟随应直接写 `.ai-scroll.scrollTop = .ai-scroll.scrollHeight`，依赖 `messages`（覆盖用户消息、assistant 占位和每个 SSE 增量）并兼顾 error/sending 状态；不调用 window 滚动，也不影响其他页面。

## 2026-08-11 首页适配、独立目标报告与饮食中文化

- 首页原布局由 102px 问候区、目标卡、两行每张最小 166px 的快捷卡和 118px 底部留白叠加，在较矮手机上必然让 `.page` 产生纵向滚动。
- 首页改为不滚动的三行 Grid，快捷区占用剩余高度；820px 以下启用紧凑排版。浏览器测量 390×667 与 320×568 时页面 `scrollHeight === clientHeight`，四张卡均位于底栏上方且自身无内容溢出。
- 初版 700px 紧凑断点在 701px 高度出现卡片内部裁切；代码审查将首页断点独立调整为 820px，并实测 701px 和 821px 两侧均无裁切。
- 首页目标箭头和报告卡原本都导航到 `/ai?report=current`；`AiPage` 根据查询参数生成报告并把 `CurrentGoalReportCard` 插在 `.ai-scroll` 顶部，导致报告与聊天信息架构耦合。
- 新路由 `/report/current` 独立负责报告入队、轮询、失败重试、记录和生成计划动作；AI 页已移除全部报告状态与渲染逻辑。
- 三餐推荐不是普通聊天：HTTP/05:30 调度只持久化任务，`DailyMealPlanGenerationWorker` 领取租约后调用 `MealPlanGenerationRuntime`。
- `MealPlanGenerationRuntime` 通过 `PublishedFitnessAgentRuntime` 读取已发布 `fitness.coach` 的不可变 Provider、模型与凭据快照，然后直调 OpenAI-compatible `/chat/completions`，以严格 JSON Schema 接收早餐/午餐/晚餐并持久化。
- 英文输出根因是三餐专用 system prompt 完全使用英文且没有中文约束，解析器也只校验 JSON 结构。现已明确要求 `items.name` 与 `reason` 使用简体中文，并拒绝缺少汉字的用户文案。
- 对已经持久化的英文 READY 推荐，饮食页会自动调用原生成接口；服务只把缺少中文用户文案的 READY 任务重置为 GENERATING，worker 成功后原子覆盖三餐内容，正常中文 READY 不会重复调用模型。

## 2026-08-12 指定 Skill 的三餐 Agent 后台任务

- 当前 05:30 只有一个全局 Spring 定时器，不会为每个用户创建定时器；它遍历 `activeUserIds()` 并按用户/日期写入唯一的持久任务。
- `activeUserIds()` 目前只判断 `users.status='ACTIVE'`，所以长期不用的账户仍会每天消耗一次模型调用。
- `users.updated_at` 已存在且适合记录最近应用访问；在 bootstrap 时触碰该字段即可覆盖登录后持续使用，无需新增 migration。
- 回访按需恢复必须只入队“当天不存在”的计划；失败计划仍由显式重试入口处理，避免每次 bootstrap 无限重试并重复消耗 Token。
- 当前三餐运行时绕过 Harness，固定 system prompt 后直调 `/chat/completions`；反馈数据由服务预先塞入请求，训练档案和历史推荐未参与。
- 已发布 Agent 快照包含 Skill 的内容、revision 和 `requiredToolKeys`，但 `PublishedAgentPlaygroundRuntime` 当前把所有绑定 Skill、所有 Agent Tool 和聊天记忆一起传入 `RunRequest`。
- 两个框架 Adapter 都只消费 `RunRequest.skills()`；因此后台入口可以在运行时层精确裁剪为一个 Skill，并从该 Skill 快照解析必要 Tool，做到确定性装载而非提示词暗示。
- 后台任务可使用派生内部会话键记录 Run/Trace，同时 Run 仍标记真实 `fitness.coach`；这样保留 agentVersion/Skill 事件审计，又不会进入用户 `fitness.coach` 聊天会话。
- 当前 `fitness.meal.query` 只返回最近 5 次实际餐食和当天推荐，`fitness.workout.query` 只返回当天计划和累计完成次数；用户已要求本轮不调整 Tool 逻辑，这部分上下文增强留到后续 Tool 清单优化。
- 当前 Worker 每 500ms 同步领取并运行一条任务；模型调用最长 45 秒。生产实现应使用队列容量为 0、默认 3 线程的专用执行器，避免无限排队并保持 Provider 并发可控。
- 专用 `ThreadPoolTaskExecutor` 如果作为普通默认候选 Bean 注册，会使 Spring Boot 不再自动创建 `applicationTaskExecutor`，从而阻断既有 AI 流任务；使用 `defaultCandidate=false` 后可按 Qualifier 注入三餐 Worker，同时保留 Boot 默认执行器。
- 首次真实后台运行暴露 AgentScope Harness 会自动注入 `wait_async_results`；该任务已显式关闭异步 Tool 和子 Agent，模型误调用它只会产生无法满足的人工确认。运行时在 Agent 构建后移除这个内部辅助 Tool，四个已发布业务 Tool 及其输入输出均未变化。
- 本地发布的 `fitness.meal.skill` 为 revision 2，`fitness.coach` 为 version 10。真实运行 `5071be2d-dc20-43fe-ab9f-553d6446ebd7` 成功装载指定 Skill，只调用原有四个只读 Tool，并返回三餐简体中文 JSON。
- 390×844 手机视口从“今天还没有饮食建议”点击生成后进入轮询，最终自动展示早餐、午餐和晚餐；对应持久任务为 READY，页面无需手动刷新。

## 2026-08-12 首页密度、聊天宽度与花爷命名修复

- 用户截图中的首页约为 945×2048 像素比例；目标卡高度正常，但两行快捷卡被平均拉到约 540 像素，标题与底部摘要之间出现大面积无意义空白。问题特征与此前“快捷区占满剩余视口”的 Grid 自适应策略一致。
- 聊天截图中 assistant 消息卡自身已接近手机内容宽度，但 Markdown 表格的最小内容宽度超过卡片，右侧列与普通段落一起被裁出屏幕，说明溢出源在消息/Markdown 子元素而非输入框。
- 用户可见页头和输入占位已显示“花爷”，但回复正文仍可能出现“瘦瘦”；需要同时排查静态前端文案、Agent prompt/Skill 默认文案与测试夹具，技术键名不应替换。
- 本轮视觉方向保持现有温暖、玩具感卡片语言，但从“填满屏幕”改为“紧凑信息密度”：快捷卡由固定/自然高度承载，不用空白制造视觉重量。
- 首页根因已定位：`.home-page` 第三行使用 `minmax(0, 1fr)`，`.home-actions` 的两行也使用 `repeat(2, minmax(0, 1fr))`，因此所有目标卡以下的剩余高度都会被四张快捷卡均分；在高屏手机上必然形成截图中的超高空卡。
- 聊天宽度根因已定位：`.message` 只有 `max-width: 87%`，缺少 `min-width: 0`/明确可用宽度；`.message-body` 也没有宽度收缩边界，而 Markdown 表格单元格设置了 `white-space: nowrap`。表格的固有最小宽度会反向撑大 flex item，导致整条 assistant 消息超出屏幕。
- 现有 `.md table { display:block; overflow-x:auto; width:100% }` 本意是局部横向滚动，但父级 flex item 未允许收缩，且单元格禁止换行，所以局部滚动容器无法先缩到消息宽度。
- 当前前端导航、页头、占位和报告摘要已经使用“花爷”；残留主要出现在 Agent 工作台初始化/基线 Prompt 的“瘦瘦健身教练”“瘦瘦系统提示词”“瘦瘦 AI 花爷”，模型回复中的“瘦瘦”很可能来自仍在发布快照中的系统提示词，而不是前端渲染文案。
- 用户补充：首页需要增加体重变化趋势图，并希望合理选择需要点击放大的视觉内容。初步边界为体重趋势、动作示意和报告数据图；头像、功能图标、装饰性吉祥物与纯色卡片没有放大价值。
- 项目已有可复用的 `WeightSparkline`，直接消费 bootstrap 的 `bodyRecords`，所以首页趋势无需新增接口；无记录时组件已有明确空状态。
- 现有高信息视觉共四类：`WeightSparkline` 体重曲线、当前目标报告的体重/训练量图、`ExerciseVisual` 的真实动作图片或 SVG 姿势图、饮食识别的用户照片预览。`BodyActivation` 是低密度部位概览，放大收益较低；列表里的 compact 动作缩略图已有“进入详情”行为，不应叠加放大手势。
- 合理放大边界：个人页与首页体重趋势、报告页两张趋势图、动作详情/训练计划/跟练页的非 compact 动作图、饮食照片预览支持全屏；动作库 compact 缩略图保持进入详情，吉祥物/头像/图标不支持放大。
- 放大能力适合由一个可复用、可访问的全屏查看器承载，支持关闭按钮、遮罩点击和 Escape；图表与 SVG 直接复用原组件内容，不把它们转换成位图。
- 浏览器实测确认首页拉伸随视口高度线性加剧：390×844 时四张快捷卡各高 168.25px；430×932 时各高 212.25px。目标卡和问候区高度保持不变，全部额外高度都被 `.home-actions` 的两个 `1fr` 行吸收，根因假设成立。
- 首页修复不能继续让快捷卡承载剩余空间。更稳妥的是将页面内容改为自然高度/顶部对齐，快捷卡行使用内容驱动的紧凑固定范围；新增体重趋势作为独立横向信息条，不把趋势塞进目标卡或某张快捷卡。
- 430×932 聊天页实测 `.conversation` 可用宽度为 392px，但其 `scrollWidth` 已达到 406px；`.ai-scroll` 的横向 overflow 计算为 `auto`。即使当前恢复会话没有表格，普通消息结构已经产生 14px 横向溢出，验证父级收缩约束确实不足。
- `.message--assistant` 当前 `min-width:auto`，`.message-body` 同样为 `min-width:auto` 且 `max-width:none`；修复应在消息 flex item 和正文两级都补 `min-width:0`/明确最大宽度，同时把 `.ai-scroll` 固定为 `overflow-x:hidden`。Markdown 表格自身可以保留局部滚动，但普通段落、列表、代码和链接必须换行。
- 用户明确不接受把 Markdown 表格所有单元格强制换行，因为会破坏行列对应关系。正确交互是：消息正文保持屏幕宽度，表格作为独立 UI 组件局部横向滑动，并提供放大按钮进入浮层查看完整表格。
- 表格浮层应保留二维结构和单元格内容，使用更大可视区域、粘性表头与首列、独立横纵滚动；内联表格增加边缘渐隐/滑动提示，避免用户误以为整个页面可以左右拖动。

## A2UI 与通用 Agent UI 组件协议评估

- A2UI 的核心思想与本项目后续方向高度相关：Agent 不生成 HTML/JSX，而是发送可校验的声明式组件树、数据模型更新和命名 Action；客户端只渲染白名单 Catalog 中的组件，因此能保持视觉一致性与安全边界。
- 值得借鉴的不是立刻完整接入 A2UI SDK，而是四个协议边界：版本化组件 Catalog、UI 描述与数据分离、命名 Action 回传、渲染前 Schema 校验/未知组件降级。
- A2UI 组件以稳定 ID 组织，可增量更新 Surface；这与当前持久 SSE Run Event 很契合，但项目目前只需要对话消息内的有限卡片，不值得马上引入完整 Surface 生命周期、通用表达式求值和跨端传输协议。
- 官方当前状态为 v0.9.1 production/current、v1.0 candidate；项目仓库仍标注 early-stage public preview，并明确还在推进规范稳定和更多官方 renderer。现在把它作为设计参考比直接绑定候选协议更稳妥。
- A2UI v1.0 支持 renderer/agent Catalog capability 交换、命名 action、`actionResponse`、自定义 Catalog 与跨 Catalog 混用；Catalog 自身用 JSON Schema 约束组件和函数，正好能避免 Agent 任意构造前端代码。
- 官方 renderer 指南建议用 `web_core` 承担约 3000 行 Surface 状态、消息处理与 Schema 校验；如果未来真的需要跨 Agent/跨客户端的任意 UI Surface，再评估正式接入。当前只做有限业务卡片时直接引入会扩大协议和状态复杂度。
- 项目现有 Core 已有 framework-neutral `ResponseBlock` 和有序 `RunEvent`，前端 SSE 也已区分 TEXT、RUN_EVENT、APPROVAL；因此不应再造一套平行传输。通用 UI 描述应作为新的受控内容块/组件事件接到现有 reply/run 流中。
- 当前 `AgentRunMessage` 把 Markdown、进度和训练计划确认卡硬编码在一个组件中；继续增加详情卡、趋势图会形成分支膨胀。应拆为“消息状态归约器 + 组件注册表 + 通用壳层/浮层 + 领域组件”。
- A2UI 的 Surface/DataModel 对当前单条聊天回复偏重，但它的 Catalog 对应项目中的组件注册表，Action 对应现有确认接口，稳定 component id 对应现有 blockId；可以做一个兼容其思想、但不宣称协议兼容的轻量子集。
- 更关键的发现：`public-v1.yaml` 已经预留 `AiContentBlock` 判别联合，包含 TEXT、GOAL_SUMMARY、WORKOUT_PLAN、EXERCISE_DETAIL、MEAL_PLAN、MEAL_RECOGNITION、BODY_TREND、CURRENT_GOAL_REPORT、CONFIRMATION，并已有 `STRUCTURED_COMPONENT` SSE 事件。项目其实已经有自己的领域 Catalog，只是运行时投影和前端 renderer 尚未真正接通。
- 因此不应再发明新的通用 `UiBlock` 协议。应把现有 `AiContentBlock` 作为唯一传输契约，前端建立按 `kind` 注册的 renderer；需要通用表格时按 contract-first 新增 `DATA_TABLE` block，而详情、趋势、确认继续使用强类型领域 block。
- 现有 `ComponentEventData` 只有 messageId + block，若以后需要对同一卡片流式更新，应增加稳定 componentId/operation（UPSERT/REMOVE）后再支持，而不是靠数组顺序猜测。当前第一阶段可以只追加完整 block，避免提前实现完整 Surface 状态机。
# 2026-08-12 训练播放器媒体、计时与语音修复

- 用户报告五项现象：计划页图片不轮播、训练准备倒计时过快、训练页图片不轮播、中段缺少逐秒节拍音、语音包无法选择风格。
- 任务至少包含两个共享边界：动作媒体展示，以及训练时钟/音频调度；语音风格选择可能进一步涉及浏览器 SpeechSynthesis 能力或服务端音频资源。
- 当前前端与训练相关文件已有其他未提交修改，后续必须先读真实 diff，再决定最小编辑面。
- 初步定位到共享媒体组件 `frontend/src/components/ExerciseVisual.tsx`：它按传入 `step` 选择单张 `imageUrls`，组件内部没有自动轮播时钟；计划页 compact 使用默认 step，因此始终停在首图。
- 跟练语音集中在 `frontend/src/workout/voiceGuidance.ts`；当前转换提示只为准备阶段、动作/休息结束前 3 秒生成语音数字，没有独立的逐秒节拍音通道，也没有声音风格模型。
- 现有 `App.test.tsx` 已覆盖跟练打开、语音不支持、退出取消等行为，可在同一测试边界补失败用例，不需要另造页面测试框架。
- `ExerciseVisual` 的图片选择规则是 `imageUrls[step - 1] ?? imageUrls[0]`；计划页传 `compact` 且不传 `step`，所以稳定停在第 1 张。训练页若传固定 session step，同样不会按动作节奏自动切换。
- `voiceGuidance.ts` 当前完全依赖浏览器 Web Speech API，固定 `lang='zh-CN'`、`rate=1`，接口没有 voice/style 参数，也没有 `getVoices()` 选择逻辑。
- 当前声音是语音队列：准备阶段读 3/2/1，动作和休息阶段只在剩余 3 秒读数字；中间没有 Web Audio/HTMLAudio 的短促节拍音，因此用户听到大段静音与代码一致。
- 既有假时钟测试只验证“3 秒准备后进入 2 秒动作”，没有验证真实墙钟下准备数字每个至少显示约 1 秒；必须继续审计 App 中 interval effect 的依赖和重建频率。
# 2026-08-12 训练播放器媒体、计时与语音修复（补充调查）

- `workoutSession.ts` 的纯状态机一次推进只递减 1 秒，3 次推进后才从准备阶段进入训练；“读秒过快”更可能发生在 `App.tsx` 的计时器注册、清理或重复调度层。
- 训练计划接口与集成测试已经证明单个动作可返回 4 张 `imageUrls`，无需为轮播新增数据库字段或接口。
- 当前样式只有单张动作图容器，没有轮播指示、切帧或无动画偏好处理。
- 语音引擎固定使用 `zh-CN`、`rate=1`，接口未暴露 `voice` / `pitch` / `getVoices()`；目前无法选择或持久化语音风格。
- `WorkoutPlayer` 当前每 1000ms 推进一步，effect 依赖 `paused` 与 `session.phase`，清理函数也存在；静态检查暂未发现显式的双 interval，需要用真实页面/可控时钟复现并检查首秒边界。
- 训练中的动作图片并非按秒切换，而是写死为 `(setIndex % 4) + 1`：同一组训练期间永远停在同一张，只有组数变化才换图。
- 训练预览与计划页都没有传入动态 `step`，因此默认固定显示第 1 张。
- 当前工作树中 `App.tsx` 与 `ExerciseVisual.tsx` 已有未提交修改，后续实施必须先区分并保留用户/其他任务的改动。
- 当前本地账号今天与本周一均无可进入的训练计划，浏览器暂时无法直接复现倒计时；需要继续检查其他日期或依赖可控时钟的组件测试。
- 用户已确认至少存在“语音 3、2、1 挤在一起”的问题。结合现有 FIFO 语音队列，根因是较长的开场播报占用通道时，后续数字持续入队；开场结束后积压数字会无间隔连续播放。
- 倒计时语音应采用“只保留当前有效提示”的时效策略，而不是普通播报所用的完整 FIFO；过期秒数必须丢弃，不能补播。
- `ExerciseVisual.tsx` 已含其他任务新增的可展开媒体容器改动；轮播必须在该结构上增量实现，不能回退或覆盖 `ExpandableSurface variant="media"`。
- `App.test.tsx` 与 `app.css` 有大量并行改动。为降低冲突，新逻辑优先放入独立的 workout 单元测试与组件测试，只在 `WorkoutPlayer`、`PlanPage` 调用点和末端样式区做最小增量。
- 当前 App 集成测试已经覆盖训练进入、暂停、完成、StrictMode 语音去重和静音/跳过；新测试应聚焦“过期倒计时不补播”“动作帧随秒数往返”“预设参数与持久化”“每秒节拍”和“每个数字至少展示 1 秒”。
# 2026-08-12 训练计划确认卡、全身候选查询与聊天字号修复

- 根因：保存 Tool 冻结参数只有 exerciseIds，Runtime 原样投影 proposal，前端把 ID 回退为 name。
- 可信名称来源：同 Run 中实际绑定、key 精确匹配的候选/详情 Tool 在确认前返回的 exerciseId + name；未绑定同名事件不采信，只丰富展示，不修改审批参数。
- “全身”语义：等价于空 focusAreas，继续应用经验、器械和冲击限制；与具体部位混用为参数错误。
- 字号边界：正文 16px，确认卡/进度/建议词 14px，辅助说明 11px；标题与底部导航不变。
- 设计和计划见 docs/superpowers 下本轮文件。

## 2026-08-12 已注册 Tool 历史兼容与使用情况审计

- 审计口径：源码注册是“可用集合”，当前发布 Agent/Skill 绑定是“当前可达集合”，真实 Run Trace 是“近期实际调用集合”；只有三层证据一致且不承担恢复/审批兼容时，才建议直接删除。
- 历史设计文档显示最初只有 `fitness.profile.query`、`fitness.workout.query`、`fitness.meal.query` 三个聚合只读 Tool；后续又增加计划候选/详情等细粒度 Tool，因此这三个聚合 Tool 是重点兼容候选，但尚不能仅凭文档判定可删。
- 当前代码还存在 AgentScope/Harness 注入的 `wait_async_results` 等内部 Tool；它们不属于 Fitness 业务 Tool，必须单独审计其运行模式，不能按业务目录使用率直接删除。
- `FitnessTools` 当前注册 23 个业务 Tool，其中存在四组明显的功能重叠/代际关系：
  - 旧聚合 `fitness.profile.query` 对应细粒度 `fitness.user.profile.query` + `fitness.goal.current.query` + `fitness.training.constraints.query` + `fitness.nutrition.preferences.query` + `fitness.body.latest.query`。
  - 旧聚合 `fitness.workout.query` 对应 `fitness.workout.schedule.query` + `fitness.workout.summary.query`/`history.query`。
  - 旧聚合 `fitness.meal.query` 对应 `fitness.meal.history.query` + `fitness.meal.recommendations.query`。
  - `fitness.exercise.search` 与较新的 `fitness.exercise.catalog.search`/`fitness.exercise.candidates.query` 重叠；`fitness.meal.feedback_context` 与较新的 `fitness.meal.feedback.query` 重叠。
- 代码基线仍让 `fitness.meal.skill` 依赖四个旧式 Tool：`fitness.profile.query`、`fitness.workout.query`、`fitness.meal.query`、`fitness.meal.feedback_context`；因此它们即使属于历史兼容，也不能在迁移当前 Skill/发布快照前直接删除。
- 代码基线的 `fitness.coach` 当前绑定 13 个 Tool，包含旧式三餐四件套、计划 Skill 的 8 个 Tool，以及额外的 `fitness.exercise.catalog.search`；已注册的其余细粒度饮食/身体趋势类 Tool 可能尚未进入当前发布 Agent，需要查数据库确认。
- 五个旧式实现并不是新 Tool 的简单别名：
  - `fitness.profile.query`、`fitness.workout.query`、`fitness.meal.query`、`fitness.exercise.search` 都通过 `FitnessApplicationService.loadForTool()` 读取一份大聚合，再在内存裁剪；新 Tool 通过 `FitnessAgentQueryService` 做目的明确、带边界的查询。
  - 旧 `fitness.exercise.search` 会把步骤和常见错误一起返回、允许最多 50 条；新 `fitness.exercise.catalog.search` 只返回紧凑目录，`fitness.exercise.details.query` 再批量读取 1–8 个详情，计划场景另有带用户硬限制的 candidates Tool。
  - `fitness.meal.feedback_context` 直读旧应用服务；`fitness.meal.feedback.query` 走新查询服务和安全 DTO，描述明确把自由文本标为不可执行数据。
- 因而旧式 Tool 的删除收益不仅是少几个名称，还会移除一条高负载、宽数据面的 `loadForTool()` 兼容访问路径；但四个三餐旧 Tool 目前仍被 Skill 强依赖，需要先迁移 Skill。
- Agent 发布快照保存在 `agent.agent_versions.configuration`，当前草稿/Skill 绑定分别在 `agent.agent_drafts.tool_keys` 与 `agent.agent_skills.required_tool_keys`；Run 调用证据在 `agent.agent_run_events.payload`，审批 Tool 另有 `agent.agent_run_approvals.tool_key`。
- 本地 PostgreSQL 容器 `deploy-postgres-1` 正常运行，数据库为 `happy_agent`；可通过容器内 `postgres` 账户执行只读 SQL，不需要输出或复制任何凭证。
- 实际数据库已比源码基线演进到 `fitness.coach` 发布版 v16：当前草稿绑定了全部 23 个 Fitness Tool，以及 `plan/meal/analysis/knowledge` 四个 Skill。
- 当前 `fitness.meal.skill` revision 3、`fitness.analysis.skill` revision 1、`fitness.knowledge.skill` revision 1 已全部改用细粒度新 Tool，不再要求旧聚合 Tool。
- 当前 `fitness.plan.skill` revision 7 的 `required_tool_keys` 却包含全部 23 个 Tool；这使所有历史 Tool 在静态发布校验上仍是强依赖。需要继续核对该 Skill 正文是否真的引用它们，判断这是实际能力要求还是发布配置膨胀。
- 历史发布快照共有 `fitness.coach` v1–v16。删除运行时 Tool 前必须明确旧 Run 的恢复策略：当前新 Run 使用最新快照，但等待审批的旧 Run 可能继续按其原版本绑定执行 `fitness.plan.save`；只读历史消息本身不需要重新调用 Tool。
- 发布历史清楚显示代际切换：v1–v5 曾绑定已从源码注册表移除的 `fitness.plan.generate`；v6 起加入 `fitness.exercise.search`；v12 起加入细粒度查询族；v15 起加入 `fitness.exercise.candidates.query`。这证明系统已经允许旧快照保留不存在于当前 registry 的 Tool，关键约束应落在“是否还有可恢复的旧 Run”，而不是永远保留所有历史实现。
- 当前四个 Skill 正文的实际 Tool 引用：
  - plan：只引用 `body.latest`、`exercise.catalog.search`、`exercise.details`、`goal.current`、`plan.save`、`training.constraints`、`workout.schedule`、`workout.summary`。
  - meal：只引用 10 个细粒度新 Tool；不引用四个旧式三餐 Tool。
  - analysis/knowledge：也只引用细粒度新 Tool。
- 因此 `fitness.plan.skill` revision 7 把全部 23 个 Tool 写入 `required_tool_keys` 与正文不一致，是配置膨胀而非实际编排需要；应先收敛 required keys，才能安全从 Agent 绑定移除旧 Tool。
- v16 Skill 快照结构把依赖放在 `config` 内（顶层只有 `key/version/status/config`）；后续如审计历史快照依赖，应读取 `skills[].config.requiredToolKeys`，不能误读顶层字段。
- 全量真实 Trace 显示旧 Tool 确实曾被使用：`profile.query` 成功 19 次、`workout.query` 19 次、`meal.query` 1 次、`meal.feedback_context` 2 次、`exercise.search` 60 次；这些调用集中在旧发布版本，不能把它们描述成“从未使用”。
- 新 Tool 已接管主要场景：`exercise.catalog.search` 成功 30 次、`exercise.candidates.query` 14 次、`goal.current` 21 次、`training.constraints` 20 次、`workout.schedule` 19 次、`workout.summary` 16 次等。
- 当前仍有 v15 和 v16 各一个 `WAITING_APPROVAL` Run，且所有审批记录都只涉及 `fitness.plan.save`（12 已批准、3 已拒绝、2 待定）。旧只读 Tool 不参与审批执行，但删除前仍需核对恢复实现是否会强制解析完整旧快照的所有 Tool。
- Trace 还保留已经移除的 `fitness.plan.generate`（9 次成功），进一步证明“历史 Trace 中出现”不等于必须继续保留运行时实现。
- 按版本看，旧聚合 Tool 的实际调用在 v10 后基本停止；v15 完全只使用细粒度新 Tool，v16 只有 `fitness.profile.query` 又被模型调用 1 次，其余 `workout.query`、`meal.query`、`meal.feedback_context`、`exercise.search` 在 v15/v16 均为 0。
- `fitness.profile.query` 的 v16 单次调用说明：只要旧 Tool 仍暴露在 Agent 全量绑定中，即使 Skill 正文已迁移，模型仍可能基于旧 Tool 的宽泛描述自行选择它。因此安全清理顺序必须是“收敛 Skill required keys → 从草稿解绑并发布 → 验证新 Trace → 删除注册实现”，不能直接删方法。
- 源码中旧四个三餐 Tool 还被 `FitnessSkillRegistry.MealSkill.execute()` 硬编码调用，但全仓生产代码没有调用 `FitnessSkillRegistry.skill()` 或 `ExecutableSkill.execute()`；当前运行适配器消费的是发布 Skill 快照。这部分看起来是更早期“确定性 Skill 预取事实”的遗留实现，需作为删除旧 Tool 时同步清理/改造的源码依赖，而非当前真实调用证据。
- `fitness.exercise.search` 在生产源码中除注册、旧默认种子投影外没有业务调用者；当前 Skill 正文已改用 `exercise.catalog.search`、`exercise.candidates.query` 和 `exercise.details.query`。
- 两个待审批 Run 恢复时，`decide()` 不会重新解析或 resolve 旧发布快照的全部 Tool；它只从冻结审批记录取 `tool_key=fitness.plan.save` 和参数，直接通过当前 registry 执行。因此保留 `fitness.plan.save` 即可，移除旧只读 Tool 不会阻断现有两张确认卡的批准/拒绝。
- 对新 Run，Runtime 会对最新发布版本的 `toolKeys` 全量 resolve；所以必须先发布一个不含旧 Tool 的新版本，再删除注册实现。反过来先删实现会让最新 v16 在启动运行时直接报“已发布 Agent 绑定的 Tool 不可用”。
- 生产 `@AgentTool` 只出现在 `FitnessTools` 一个类中；源码注册表即这 23 个业务 Tool。AgentScope 的 `wait_async_results` 是框架内部辅助 Tool，构建 Agent 后已显式移除，不属于管理后台的 Fitness Tool 清单。
- 旧 Tool 最终审计结论：以下 5 个均可在配置迁移后删除，且恰好是 23 个注册 Tool 与 18 个当前细粒度能力 Tool 的差集：
  1. `fitness.profile.query`
  2. `fitness.workout.query`
  3. `fitness.meal.query`
  4. `fitness.meal.feedback_context`
  5. `fitness.exercise.search`
- `fitness.plan.generate` 是已完成清理的历史样例：源码 registry 已无该 Tool，测试明确断言不存在，但历史 v1–v5 快照和 Trace 仍可保留审计数据。
- 建议安全顺序：先把 `fitness.plan.skill.required_tool_keys` 去掉上述 5 项，随后从 `fitness.coach.tool_keys` 解绑并发布新版本；跑计划、三餐、分析、知识四类真实验收确认新 Trace 无旧 runtime name；最后删除五个 `@AgentTool` 方法、旧 DTO、`loadForTool()` 专用聚合路径、默认种子/基线引用及对应测试。

## 2026-08-12 历史 Tool 清理实施边界

- 当前工作分支与本地主 `main` 同起点，已包含上一轮训练确认卡、候选查询、聊天字号和处理进度位置的未提交改动；用户要求“统一处理后推送 main”，本轮会将这些连续需求作为同一发布批次验证与提交。
- 默认种子仍同时在 `JdbcAdminWorkbenchStore.seedDefaults()` 与 Agent V1 baseline 中维护，二者都必须移除历史键，否则新环境或空库会重新暴露已删除 Tool。
- `FitnessToolsTest` 当前直接测试旧 profile/search 行为，是最合适的 RED 入口：先把注册契约改为精确的 18 个 Tool 并断言 5 个旧键不存在，再删除实现。
- baseline 实际路径是 `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`，不是 starter 资源目录。
- `FitnessSkillRegistry.MealSkill` 仍按顺序调用旧 profile/workout/meal/feedback_context；测试也把这些键写入 allowed tools。若保留该兼容执行器，迁移后的对应事实调用应改为当前 Meal Skill 所需的细粒度 Tool，而不是只删除断言。
- `FitnessApplicationService.loadForTool()` 当前只承担旧聚合 Tool 的可信用户宽读取；五个方法移除后可连同该入口删除。`mealRecommendationFeedbackContext(UUID)` 是否还能删除需要再次核对所有生产调用，不能与 `loadForTool()` 一并假定。
- 当前持久化本地数据库的 v16/草稿仍绑定旧键；仅改 V1 baseline 只能保证新库正确。页面验收前必须确认项目对既有开发库的预期迁移方式，避免源码删除后当前发布 Agent 无法解析 Tool。
- 删除后的已知代价：旧 v1–v16 快照仍可查看，但若未来加入“直接按旧版本重新执行/回滚”的能力，引用已删除 Tool 的旧版本将不可运行。当前实现只运行最新发布版本，审批恢复又只调用冻结的 `fitness.plan.save`，因此现状不受影响。
