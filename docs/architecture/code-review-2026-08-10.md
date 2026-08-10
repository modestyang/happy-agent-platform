# 全仓代码审查报告（2026-08-10）

> 审查范围：整体架构设计、编码规范、代码可读性、历史包袱/死代码。
> 审查方式：多代理并行静态分析（只读），覆盖后端 779 个 Java 文件与前端 43 个 TS/TSX 文件。
> 背景：项目处于 0→1 阶段、尚未上生产，Agent 库迁移折叠进 V1 属允许行为，本报告不将其列为问题。

## 总体结论

骨架健康：Maven 依赖图与 `docs/architecture/module-boundaries.md` 声明的模块边界基本一致（agentbuilder 无对 fitness 的反向依赖、core 无 Spring 注解、Controller 均在 starter、前端 typecheck/lint 全绿）。

主要问题集中在四个方面：

1. **文档承诺与实现脱节**（认证契约、fitness-common 空壳、ArchUnit 门禁过弱）；
2. **粒度倒挂**：模块切得碎、类却不拆，少数巨型类承载过多职责；
3. **契约层在前端名存实亡**（3198 行生成代码零引用）；
4. **历史包袱约占全仓库代码量一半**（约 20,700 行可瘦身；另有 framework-adapter 模块属"设计意图未实现"，应接线而非删除）。

---

## 一、架构设计问题

### P1 — 认证实现与 OpenAPI 契约是两套体系

- `docs/architecture/openapi/public-v1.yaml:2477` 声明 JWT bearer（audience `happy-agent-public-v1`），文档 §8 要求两条独立 bearer 边界；
- 但 starter 源码中不存在任何 JWT/audience 处理（grep `Jwt|audience` 零命中），公开 API 实际用 `FITNESS_SESSION` cookie 认证：
  - `starter/src/main/java/happy/jayden/yang/fitness/LocalAuthController.java:43-61`（发 cookie）
  - `FitnessV1Controller.java:63` 等处用 `@CookieValue`
- 契约、文档、实现三者不一致，需二选一统一。

### P1 — starter 越界承载业务与基础设施

- `starter/.../fitness/OssPresignedMediaUploadPort.java`（287 行）：starter 内实现完整 OSS 签名/JDBC 适配器，且持有长期 `accessKeySecret` 字段（第 40-41 行）——违反"OSS 归 fitness-infrastructure"及"不保存长期 AccessKey"两条规则；
- `starter/.../AgentRuntimeConversation.java`（679 行）、`FitnessAgentRunService.java`（510 行，`@Service`）在 starter 做持久化 Agent 编排；`FitnessAgentRunService.java:5` 直接 import 具体实现类 `JdbcRunTraceRepository`，绕过 Port；
- `FitnessV1Controller.java:104-120` 在 Controller 内做日期范围校验 + 逐日循环聚合，超出"auth/DTO/HTTP 状态映射"职责。

### P2 — 声明的分层不存在或被穿透

- `application/fitness/fitness-common/` 是零源码空壳（仅 pom.xml）；DTO、异常全在 fitness-service（`FitnessDtos.java` 421 行 75 个类型、`FitnessExceptions.java`），fitness-service 的 pom 甚至未声明对 common 的依赖；
- `agentbuilder-core` 的公共 SPI `core/runtime/AgentFrameworkAdapter.java:4` 在签名中暴露 `reactor.core.publisher.Flux`，框架类型泄漏进 core；`core/defaults/`（23 个文件）实现了文档划给 service 层的"默认解析"职责；
- ArchUnit 只有 4 条规则（`architecture-tests/.../ModuleBoundaryTest.java:16-51`），文档宣称的多数禁令（core 框架无关、starter 无业务规则、Controller 包位置等）均无门禁，上述违规全部能穿过测试。

### 合规面（正面证据）

- 各 pom 依赖边与文档 §3 完全一致；agentbuilder 全模块无 `happy.jayden.yang.fitness` 引用；
- agentbuilder-service 无 Spring/SQL import；core 无 Spring 注解；Controller 均位于 starter。

---

## 二、历史包袱与死代码清剿（0→1 视角）

> 估算可瘦身约 20,700 行（高置信 4,100 + 中置信 16,600），接近全仓库代码量（Java 主 22k + 测试 12k + TS 10k ≈ 45k）的一半。另有 agentbuilder-framework-adapter 模块（约 3,400 行）属于"设计意图未实现"，应接线而非删除，见下文专门小节。
> 注意：工作区有一笔进行中的重构（`agent_component_projection` → 独立资源表），部分"死代码"是重构残留。

### 可立即删除（高置信，约 4,100 行）

**前端**

| 项 | 位置 | 理由 |
|---|---|---|
| 生成的 admin.ts 全文件 | `frontend/src/api/generated/admin.ts`（3198 行） | 全仓库零 import，admin 前端用手写 `admin/api.ts` |
| 漂移的类型副本 | `frontend/src/admin/types.ts`（85 行） | 零 import，与 `admin/api.ts` 重复且字段已漂移 |
| 重复/未用 API 方法 | `frontend/src/api.ts:54`（meal）、`:69`（goal）、`:70`（aiMessage）、`:73`（appAiMessage） | 70/73 逐字重复，四者全局零调用 |
| 未用 admin API | `frontend/src/admin/api.ts:225-228`（debugMessage） | 零调用，Playground 已走 v1 runs |
| 永不赋值的 notice | `frontend/src/admin/AdminWorkbench.tsx:68,158` + `Notice` 组件 `:59-61` | setNotice 从未被赋非空值 |
| 未用 CSS 类 | `admin/admin.css` 21 个、`frontend/src/app.css` 15 个 | TSX 中零引用（约 150 行） |
| Playwright | `frontend/package.json:12,24` | 无 config、无 e2e 用例 |

**Java / 仓库**

| 项 | 位置 | 理由 |
|---|---|---|
| 死接口 ×2 | `agentbuilder-core/.../core/tool/AgentToolContributor.java:6`、`ToolMetadataCatalog.java:6` | 零引用，仅旧文档提及 |
| 无注入点的 Bean ×5 | `starter/.../agentbuilder/AdminWorkbenchConfig.java:117-146` | 注释自认 "kept as typed catalog adapters"，无任何消费者 |
| 过程残留文档（已入库） | 根目录 `findings.md`、`progress.md`、`task_plan.md`、`task-4-report.md`、`task-5-review.md`、`task-5-rereview.md`、`task-6-report.md`（共 601 行） | AI 工作流过程稿，不该入库 |
| 本地残留 | `.worktrees/`（361MB 过期 worktree ×2 + stash ×3）、`happy-agent-platform (1).iml` | 已 gitignore，本地清理即可 |

### 需确认后删除（中置信，约 16,600 行）

1. **Catalog 死轨道（约 4,600 行主+测，已确认可删）**：`core/catalog`（141 行）、`core/defaults`（1408 行，24 类中 20 个零活引用）、`service/catalog`（410 行）、`infrastructure/catalog`（1014 行）+ 测试约 1680 行；连带 `V1__agent_baseline.sql:309-428` 的 11 张 `*_catalog` 表与 `evaluation_jobs`/`probe_jobs`（无任何 Java 代码读写）。
   - **它是什么**：早期的"通用组件目录"子系统——为 Framework/Provider/Model/Prompt/Tool/Skill/Hook/MemoryPolicy 等全部 Agent 构件建的统一版本化仓库（每类一张表 + 一个 JDBC 仓储），外加 `EffectiveConfigResolver` 的四层默认值合并（平台限额 → 组件默认 → 应用默认 → Agent 覆盖）。
   - **为什么确认可删**：现行架构是"请求进来 → 按 agentKey 查已发布版本 → 快照 JSON 自带全量配置（framework/provider/模型/凭据/工具清单）"，catalog 的存配置与合并配置两件事都被快照机制取代；设计文档 `docs/superpowers/specs/2026-08-10-agent-workbench-resource-boundaries-design.md:39,153` 亦已明确废弃（"不扩大到删除"只是当时未清理）。
   - **唯一残余耦合**：`AgentFrameworkAdapter.run(RunRequest)` 的签名引用了 `core/defaults` 的 `ResolvedAgentConfig`（仅一个三字段 record：运行限额/模型参数/重试策略）。处置二选一，均无需保留 catalog：a) 接通 adapter 时从发布快照直接构造该 record（快照中本就有 temperature、maxToolCalls 等值）；b) 将 SPI 签名改为快照派生的配置类型。`ResolvedAgentConfig` 若保留应迁出 defaults 包、与 catalog 解耦。
2. **`docs/architecture/openapi/admin-v1.yaml`（10,937 行）**：描述 68 个 operation 的平台化契约（probe-jobs、credential-rotation 等），后端实际只有手写 `/api/admin/**` 小子集，前端也不消费其生成物。要么砍到真实面要么删除；连带 `scripts/contracts/fixtures/admin-coverage.json`（619 行）。
3. **旧版 Playground 链路**：`AdminPlaygroundController.java`（`/api/admin/playground/messages`）+ `PublishedAgentPlaygroundRuntime.java:48 send()`（唯一公开方法仅被它调）+ `FitnessApplicationService.java:1047 sendAiMessageForDeveloper`。已被 `AdminPlaygroundV1Controller`（SSE）取代，仅 `AdminWorkbenchIntegrationTest.java:197-204` 还在打旧端点。
4. **旧版移动端点**：`FitnessAppController.java:53`（/meals）、`:69`（/goals）、`:85`（/ai/messages）+ `FitnessApplicationService.java:348/1024/1035`。前端全走 `/api/v1/**`，只有集成测试在用——典型"被测试续命的遗产"。
5. **遗留测试段落**：`FitnessExperienceIntegrationTest.java:1176-1357、2006-2039` 等仍 INSERT/UPDATE 已从 baseline 删除的 `agent_component_projection` 表，clean 库上必失败。

### 设计意图未实现：agentbuilder-framework-adapter（不应删除，应接线）

- **设计意图**：开发者在工作台搭建 Agent 时通过 `frameworkKey` 自行选择 harness 框架；core 定义了框架中立的 SPI `AgentFrameworkAdapter`（`key()`/`capabilities()`/`validate()`/`run(RunRequest) → Flux<RunEvent>`），agentscope 与 spring-ai-alibaba 两个 adapter 均已实现该 SPI（各约 800 行 + 测试）。
- **实现断层**：运行时从未解析 SPI——
  - starter 不依赖任何 adapter 模块，没有任何 `Map<key, AgentFrameworkAdapter>` 注册表或 Spring 装配点（仅 architecture-tests 引用）；
  - `AgentRuntimeConversation.java:530` 硬编码 `!"agentscope".equals(frameworkKey)` 即拒绝，且**连 agentscope 自己也不走 `AgentScopeAdapter`**——其后的模型调用、工具循环、hook 门、trace 落库全部是手写的 OpenAI-compatible HTTP 流程，`PublishedAgentPlaygroundRuntime` 同样如此；
  - `FrameworkCapabilities` 声明的能力（工具/钩子/记忆/流式等）没有反哺工作台校验，绑组件时不按所选框架能力约束。
- **接线路径**（由浅入深）：
  1. starter 依赖 adapter 模块，Spring 收集 `List<AgentFrameworkAdapter>` 按 `key()` 建注册表；运行时按已发布快照的 `frameworkKey` 解析 adapter，未注册才报"不受支持"；
  2. `AgentRuntimeConversation` 的 send/sendStreaming 改为消费 `adapter.run(request)` 的 `Flux<RunEvent>`，trace 持久化退化为事件流的订阅者——主类随之大幅瘦身（与第四节的拆分方案天然契合）。`RunRequest` 所需的 `ResolvedAgentConfig` 直接从已发布快照 JSON 构造（快照已含 temperature、maxToolCalls 等全量配置），不依赖 catalog 子系统；也可以选择把 SPI 签名换成快照派生的配置类型；
  3. 工作台发布校验引入 `capabilities()`，按框架能力约束可绑定的组件类型；
  4. 之后新增 harness 框架（如 LangChain4j）= 新增一个 adapter 模块，不改运行时。

### 需要重构而非删除

1. **`ToolRegistry.java:12-14` 空回退**：`descriptors()` default 返回 `List.of()`，唯一实现 `DefaultToolRegistry` 已覆写，仅为保住 `@FunctionalInterface`。违反 AGENTS.md "生产代码禁止 stub 回退"——应升为抽象方法（或去掉 @FunctionalInterface）。
2. **同类回退**：`FitnessPorts.java:159-166`（verifyUploaded/readUploaded 默认抛异常，两个实现均已覆写）、`:211-217`（sendStreaming 默认退回同步 send，唯一实现已覆写）——应改抽象。
3. **演示种子在生产类里**：`JdbcFitnessStore.java:928-1116`（约 190 行硬编码"小秦"演示数据）+ `FitnessExperienceConfig.java:189-215`。建议迁到 `deploy/` SQL 或测试夹具。
4. **重构残留双轨**：`JdbcAdminWorkbenchStore.java:57-77（snapshot）、143-165（updateComponent）、319/518/645（reconcileRuntimeCapabilities）` 仍读写已删除的 `agent_component_projection`；`AdminWorkbenchConfig.java:161-166` 的启动 runner 在 clean 库上会打到不存在的表。应由新 `AdminResourceStore` 路径收编后删旧方法。连带 `AgentRuntimeConversation.java:530` 的 `!"agentscope".equals(frameworkKey)` 硬编码兼容判断可简化。

---

## 三、编码规范问题

### 安全/异常处理坏味道（建议优先修）

- **`StreamingChatClient.java:133`**：`new String(apiKey).trim()` 把刻意 clone+清零的 char[] 密钥转成不可清零的 String，且 trim 可能改变密钥——与同文件 40 行（clone）、108 行（清零）的安全设计自相矛盾，**是真 bug**；
- `MealRecognitionWorker.java:32`：`catch (Throwable)` 吞掉 Error 级异常仅转为 FAILED，无日志；
- 全项目无 SLF4J：`AgentRuntimeConversation.java:491,510` 用 `System.err.printf` 记录 hook 失败；
- `FitnessApplicationService.java:532-536, 736-740`：catch RuntimeException 后构造 FAILED 结果，原始异常消息丢弃；
- `FitnessAgentRunService.java:223`：`exception.getMessage().contains("not found")` 用异常消息字符串匹配做控制流；
- `JdbcFitnessStore.java:224, 361, 889`：`jdbc.queryForObject(...).toInstant()` 无 null 检查，NPE 隐患；
- `JdbcRunTraceRepository.java:122`：catch DuplicateKeyException 后**递归**调用自身重试。

### 复制粘贴（高频）

- `truncate(String,int)` 逐字复制 3 处：`AgentRuntimeConversation:651`、`JdbcRunTraceRepository:575`、`PublishedAgentPlaygroundRuntime:223`；
- `AgentRuntimeConversation` 与 `PublishedAgentPlaygroundRuntime` 大段雷同：同一条 `PUBLISHED_AGENT_SQL` 各自定义（57 行 vs 28 行）、相同的 JSON 校验助手、appendHistory、`long[] sequence={0}` 计数器、decrypt 凭据模式——应抽公共的 PublishedConfigLoader；
- `JdbcFitnessStore.java:300-306`：if 分支与分支外返回完全相同的语句（冗余死分支）；
- `FitnessApplicationService.java:540-563`：三个几乎相同的 `transactionRunner.inTransaction(...)` 样板；
- 状态字符串字面量 `'GENERATING'/'READY'/'FAILED'...` 在 `JdbcFitnessStore` 出现 53 次，无枚举/常量；
- 魔法数字：`estimateCost` 价格公式（`AgentRuntimeConversation:643`）、评分公式 `55 + progress/2 + min(20, meals*2)`（`FitnessApplicationService:1109`）、maxTokens=1500 与 history=20 多处各一份、`SseEmitter(130_000L)` + `Thread.sleep(150)` 轮询（`FitnessAgentRunService:314,337`）、`USER_ZONE = Asia/Shanghai` 两处重复定义。

### 命名

- 包名 `happy.jayden.yang` 为个人 ID 式命名，285 个 Java 文件全部受其影响，开源/协作前越早迁移成本越低；
- `FitnessAgentRunService.RunAccepted`（448-455 行）：字段叫 `sessionId` 实际赋的是 conversationId（436-446 行）；
- `JdbcAdminResourceStore.java:241-245 requireExistingOrConflict`：行存在时无条件抛 Conflict，方法名与语义不符；
- 空值风格不一致：`JdbcFitnessStore.latestGoal()`（1135 行）、`planForDate()`（1180 行）返回 null，同类其他方法返回 Optional；`FitnessAgentRunService:230,246,291` 用 `.orElse(null)` 把 Optional 打回 null。

### 新增代码质量（未提交改动中）

- `JdbcAdminResourceStore`：`SELECT *`（205/216 行）、`requireModel` 全表查出后内存过滤（236-238 行）、update 后 list 全量再 filter 取单行（155/178/199 行）。

---

## 四、结构与排版可读性（严格标准）

### 量化体检表

| 指标 | 数值 |
|---|---|
| Java 主/测试文件 | 230 / 54 |
| >300 行文件 | 30 个（>500 行 10 个） |
| 单行 >100 / >120 字符 | 380 行 / 155 行，最长 681 字符（`JdbcAdminWorkbenchStore.java:339`） |
| 方法 >50 行代表 | sendStreaming 163 行、callModel 130 行、resolve ~130 行、currentGoalReportFacts 122 行、decide 103 行 |
| 最大包（单目录文件数） | component 37、tool 24、defaults 23、catalog(infra) 20 |
| 单文件聚合类型数 | FitnessDtos 75 个类型/421 行；AdminResourceDtos 15 个 |
| import 峰值 | JdbcFitnessStore 62、FitnessApplicationService 58；另有 79 处内联全限定名 |
| 测试对称性 | fitness-service、fitness-infrastructure 测试为 0；全部压进 starter 的 `FitnessExperienceIntegrationTest.java`（2457 行） |

### 结构问题

1. **粒度倒挂（核心诊断）**：fitness 三模块总共装 8 个文件（fitness-common 空壳、fitness-service 仅 5 个文件），模块切得很碎；而 `FitnessApplicationService` 一个类 1148 行 76 个方法、4 个链式构造器（L77-140，构造器内还嵌"运行时未配置返回 FAILED"的桩逻辑，违反 no-fallback 禁令）。**该拆的类不拆、不该拆的模块乱拆**。
2. **包组织失败**：`component` 包 37 个顶层类 + 9 个子包中 8 个只装 1 个文件；`*Ref`/`*Binding`/`ConfigValue` 六胞胎平铺顶层，双重标准。
3. **starter 根包堆放**：`happy/jayden/yang/fitness` 根包 18 个文件、agentbuilder 根包 9 个，Controller/Worker/Runtime/Config/Port 全混一层。
4. **命名双轨制**：持久层 `Store` 后缀（JdbcFitnessStore/JdbcAdminWorkbenchStore）与 `Repository` 后缀（JdbcRunTraceRepository + core/catalog 下 10 个）并存；Port 组织也不统一（fitness 聚合嵌套接口 vs agentbuilder 一接口一文件）。
5. **测试与主代码不对称**：fitness 两模块零测试，全压进 starter 2457 行的集成测试。

### 排版问题

1. **巨型方法 + 高嵌套**：`AgentRuntimeConversation.sendStreaming` 163 行、花括号嵌套 6 层（L326）、`long[] sequence = {0}` 单元素数组当可变计数器（L112-113）；
2. **超长行**：155 行 >120 字符，SQL 种子语句整行 681 字符（`JdbcAdminWorkbenchStore.java:339`）、`JdbcFitnessStore.java:394` 达 449 字符；
3. **import 墙 + 内联全限定名并存**：JdbcFitnessStore 62 个 import 外仍内联 79 处（`FitnessDtos.DailyMealPlanRunDto` 单名出现 6 次）；FitnessApplicationService 已 import `NotFoundException` 却在 4 处写全限定名；
4. **前端 import 沉底**：`AdminWorkbench.tsx` 的 8 个 import 写在 L162-172（文件末尾），`Overview.tsx:136` 同样；
5. **注释不均**：FitnessPorts 有 17 段合格 javadoc，但 agentbuilder-core 的 10 个公共 `*Repository` Port 全部零 javadoc；运行事件文案中英混排（"调用 Tool"、"Tool 返回"，`AgentRuntimeConversation.java:157-160`）。

### 三个"最费劲"文件的拆法

1. **FitnessApplicationService.java（1148 行/76 方法）**：按聚合拆 5 个 use-case 类——`AuthSessionService`（L159-240）、`IdempotencyService`（L265-332）、`MealRecordService`（L348-472）、`MealPlanGenerationService`（L473-660）、`GoalReportService`（L718+）。每个 <250 行；删掉 4 个构造器中的 3 个，桩 lambda 移到 starter wiring 层；58 个 import 摊薄到每类 <20。
2. **AgentRuntimeConversation.java（679 行）**：抽 `RunTraceRecorder`（封装 5 类 append 调用与 sequence 自增，消灭 `long[]` hack）、`SafetyHookGate`（L402-464）、`ModelCallPipeline`（callModel 130 行按"建 prompt→调用→解析→落库"分 4 段，每段 <30 行）。主类只留 send/sendStreaming 编排，目标 <300 行、嵌套 ≤3 层。
3. **JdbcFitnessStore.java（1380 行/68 方法）**：按表簇拆 `JdbcAccountStore`、`JdbcPlanStore`、`JdbcMealStore`、`JdbcGenerationJobStore`，共享 `FitnessRowMappers`；79 处内联全限定名提为 import；`seedLocalExperience` SQL 块抽 text block 常量；449 字符行按列对齐折行。每类 <250 行、import <25。

---

## 五、前端专项

- **契约层失效（最高风险）**：`api/generated/admin.ts` 全项目零引用，admin DTO 全部手写于 `admin/api.ts:33-214`；URL 硬编码且大量路径不在 OpenAPI 契约中（`/api/app/body-records` vs 契约 `/api/v1/app/body-metric-records` 等，`api.ts:48-70`；admin 几乎全部 `/api/admin/**` vs 契约 `/api/v1/admin/**`，`admin/api.ts:219-296`）。契约漂移不会产生任何编译错误。`App.tsx:443` `api.bootstrap()` 返回 `unknown` 再 `as Dashboard`，类型与校验双失效。
- **疑似 bug**：`ComponentType.tsx:117` 保存后用 `item.version === updated.version` 匹配旧目录项，version 已变，匹配永远失败→列表过期；同文件 163 行提供 DRAFT/UNAVAILABLE 选项但 104 行保存时一律折叠为 DISABLED；`MealRecommendationPage.tsx:162` fire-and-forget 轮询（最长 60s）无卸载保护。
- **重复**：组件目录装配 + `ACTIVE→AVAILABLE` 状态映射重复 4 处（PlaygroundPage/AgentEditor/Overview/ComponentType）；SSE `applyEvent`/`decide` 在 `App.tsx:288-315` 与 `PlaygroundPage.tsx:116-143` 逐字重复；`idempotencyKey()` 重复 2 处。
- **硬编码**：`fitness.coach` 写死于 `PlaygroundPage.tsx:52/71/74`、`AgentEditor.tsx:19`；`admin/api.ts:233` 写死全零 UUID `businessUserId` 与 `revision: 1`。
- **结构**：mobile 端无 pages 分层，`App.tsx`（457 行）塞 10 个组件，与 admin/ 的 pages/components 分层不一致；`ConversationTracePage.tsx:70` 用 `<a href>` 做内部跳转（同文件 46 行用 `<Link>`）。
- **工具链**：ESLint 仅 js/ts recommended，未装 react-hooks 插件，Hook 依赖类问题不会被捕获。

---

## 六、建议行动顺序

1. **删高置信死代码**（约 4,100 行，零风险，半天内）：前端生成物/漂移类型/死 API/死 CSS、根目录过程文档、死接口与无消费者 Bean；顺手修 `StreamingChatClient:133` 密钥 String 化。
2. **删除已确认的 catalog 死轨道**（约 4,600 行）：11 张表 + 四个模块的 catalog 代码 + 测试；注意与第 3 步协同处理 SPI 签名中的 `ResolvedAgentConfig`（从快照构造或换签名，见第二节）。其余中置信档（admin-v1.yaml 契约、旧版端点链、遗留测试段落，约 12,000 行）逐项确认后删除。
3. **接通 framework-adapter SPI**：注册表 + 运行时按 `frameworkKey` 分发 + 工作台 capabilities 校验，让"开发者自选 harness 框架"的设计落地（路径见第二节专门小节）。
4. **统一认证**：要么实现契约里的 JWT，要么把契约改成 cookie 认证，消除三套说辞。
5. **拆三个上帝类**：FitnessApplicationService → AgentRuntimeConversation → JdbcFitnessStore（方案见第四节）。注意 AgentRuntimeConversation 的拆分应与第 3 步合并实施——消费 `Flux<RunEvent>` 后该类自然瘦身，避免先拆后改的双重返工。
6. **统一约定并固化**：持久层后缀、Port 组织方式、starter 分包写进 AGENTS.md，补 ArchUnit 规则（core 框架无关、starter 无业务、Controller 位置、infra 不暴露 SDK 类型）。
7. **工程卫生**：引入 SLF4J 替代 System.err 与吞异常；状态字符串枚举化；前端补 react-hooks lint 插件。
