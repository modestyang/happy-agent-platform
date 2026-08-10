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
