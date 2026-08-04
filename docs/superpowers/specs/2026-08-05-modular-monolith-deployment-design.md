# Happy Agent Platform 模块化单体设计

日期：2026-08-05  
状态：待用户书面确认

## 1. 目标与背景

Happy Agent Platform 是一个可承载多个 AI 应用的自用平台，首个应用为 AI 健身伴侣，后续可增加辅食助手等应用。生产环境为阿里云 2C4G ECS，因此不再采用两个 Spring Boot 服务、两个 PostgreSQL 实例和两个 Redis 的部署方式。

本设计选择模块化单体：代码保持明确模块边界，运行时合并为一个 Spring Boot 进程；使用一个 PostgreSQL 实例和数据库，通过 schema 保留数据归属。手机端、Agent 管理端和全部 API 同源部署。

旧工程 `/Users/modest/IdeaProjects/fitness` 只作为需求和行为参考，不再修改，也不复制其源码。新工程位于 `/Users/modest/IdeaProjects/happy-agent-platform`，从干净结构重新实现。

## 2. 已确认决策

- 根项目、GitHub 仓库和 Maven 根 artifactId：`happy-agent-platform`。
- Maven `groupId` 与 Java 根包：`happy.jayden.yang`。
- GitHub 仓库为 Private。
- 一个 Spring Boot 启动模块、一个 JVM、一个应用容器。
- 一个 PostgreSQL 容器、一个物理数据库、`fitness` 与 `agent` 两个 schema。
- V1 不部署 Redis；不以 Redis 作为缓存、锁、会话或任务系统。
- 手机端与 Agent 管理端构建后打入同一应用 JAR。
- 保留轻量 Nginx，仅负责 HTTPS、反向代理、上传限制和访问日志。
- 同项目内 Agent 工具通过 Spring Bean 本地注入；不再通过内部 HTTP 调用 Fitness。
- 外部第三方工具继续支持 HTTP Adapter。
- 阿里云百炼和 OSS 保持外部依赖。
- 日志写入磁盘并轮转；V1 不配置生产告警和自动备份，保留手动数据库导出脚本。

## 3. 项目结构

```text
happy-agent-platform
├── application
│   ├── pom.xml
│   └── fitness
│       ├── pom.xml
│       ├── fitness-common
│       ├── fitness-service
│       └── fitness-infrastructure
├── agentbuilder
│   ├── pom.xml
│   ├── agentbuilder-core
│   ├── agentbuilder-persistence
│   ├── agentbuilder-runtime-bailian
│   ├── agentbuilder-tool-fitness
│   └── agentbuilder-admin
├── starter
│   ├── pom.xml
│   └── src/main/java/happy/jayden/yang
│       ├── StarterApplication.java
│       ├── controller
│       │   ├── fitness
│       │   └── agent
│       ├── config
│       ├── scheduler
│       ├── security
│       └── exception
├── frontend
├── deploy
├── docs
└── pom.xml
```

### 3.1 Fitness 应用模块

- `fitness-common`：领域对象、值对象、命令、查询结果、错误类型和稳定公共契约。
- `fitness-service`：健身用例、业务编排、事务入口和 Repository/外部能力接口。
- `fitness-infrastructure`：PostgreSQL、OSS、定时任务和其他基础设施接口实现。
- Controller 不进入 Fitness 模块，统一放在 `starter.controller.fitness`。

未来增加辅食助手时，按相同结构新增 `application/babyfood`，而不是向 Fitness 模块堆叠代码。

### 3.2 Agent Builder 模块

- `agentbuilder-core`：Agent 定义、版本、会话、Run 状态机、Tool SPI、审批、预算与运行时接口，不依赖任何具体业务应用。
- `agentbuilder-persistence`：Agent 配置、版本、会话、Run、Trace 和评测的 PostgreSQL 实现。
- `agentbuilder-runtime-bailian`：百炼 Provider 与模型调用实现。
- `agentbuilder-tool-fitness`：把健身应用服务适配为 Agent Tool。
- `agentbuilder-admin`：Agent 工作台的 Provider、凭据、草稿、评测、发布、回滚和查询用例。
- Controller 统一放在 `starter.controller.agent`。

### 3.3 Starter 模块

`starter` 是唯一可执行模块，只负责：

- Spring Boot 启动；
- Controller 与全局异常处理；
- Security、数据库、Flyway、Agent、Web 和调度配置；
- 模块 Bean 装配；
- 前端静态资源和 SPA 路由回退；
- 健康检查。

Starter 不承载核心业务规则。

## 4. 依赖边界

```text
starter
  ├── application/fitness/*
  └── agentbuilder/*

agentbuilder-tool-fitness
  ├── agentbuilder-core
  └── fitness-service

fitness-service
  └── fitness-common

fitness-infrastructure
  ├── fitness-common
  └── fitness-service 中声明的 Port

agentbuilder-runtime-bailian
  └── agentbuilder-core
```

强制规则：

- `agentbuilder-core` 不得依赖任何 `application/*` 模块。
- Agent 不得直接调用 Fitness Repository 或直接查询 `fitness` schema。
- Fitness 不依赖 `starter`、Controller 或 Agent Builder。
- 基础设施模块只能实现上层定义的接口，不能反向泄露数据库类型。
- 依赖规则由 Maven 和 ArchUnit 测试共同校验。

## 5. 前端与 HTTP 入口

一个 React/Vite 工程同时包含手机端和管理端：

```text
/app/**     手机端 AI 健身伴侣
/admin/**   Agent 管理工作台
/api/**     后端 API
```

生产构建把前端产物打入 `starter` 的最终 JAR。Spring Boot 提供静态资源和 API，Nginx 只做 HTTPS 入口。因此前后端同源，不需要 CORS，也不需要独立前端服务或生产 Node 进程。

## 6. Agent 工具注入

同项目工具调用链：

```text
ReactAgent
  → ToolRegistry
  → FitnessToolSet
  → Fitness Application Service
  → Fitness Repository
  → fitness schema
```

`FitnessToolSet` 是 Spring Bean，并实现 Agent Builder 的 Tool SPI。Starter 在启动时发现并注册工具，Agent 工作台的已发布版本决定允许运行的工具版本、超时、最大调用次数、审批规则和输入输出 Schema。

模型输入不能指定可信用户身份。`userId`、`runId`、权限和 operationId 由服务端 `ToolExecutionContext` 注入。Fitness Application Service 继续负责权限、校验、幂等和事务。

移除旧架构中的 Fitness↔Agent 内部 HTTP、服务 Token、委托换票和网络鉴权。外部工具通过统一 Tool SPI 的 HTTP Adapter 执行，因此未来接入第三方系统不需要修改 Agent Core。

## 7. 数据库设计

一个 PostgreSQL 容器承载一个数据库：

```text
happy_agent
├── fitness schema
└── agent schema
```

- `fitness`：用户、目标、身体指标、饮食、训练、动作、媒体引用和当前目标累计报告。
- `agent`：Provider、加密凭据、Agent 草稿与版本、工具配置、会话、Run、Trace 和评测。
- 不建立跨 schema 外键，不允许跨 schema 业务查询。
- Agent 仅保存关联业务用户的标识，不把 Fitness 数据复制成第二事实源。

应用使用两个指向同一数据库的小型 Hikari 连接池：

- `fitnessDataSource`：最多 3 个连接，只拥有 `fitness` 权限。
- `agentDataSource`：最多 3 个连接，只拥有 `agent` 权限。
- 两个连接池 `minimumIdle=0`。

两个 schema 分别维护 Flyway 历史表。Starter 启动时按确定顺序执行两个迁移，生产环境禁止 Flyway Clean。

## 8. 事务、幂等与异步任务

不创建跨 schema 数据库事务。Agent 工具调用使用可恢复协议：

1. Agent schema 保存工具调用意图和唯一 operationId。
2. FitnessToolSet 调用健身用例。
3. Fitness schema 使用 operationId 幂等执行。
4. Agent schema 保存工具结果。

应用在第 3、4 步之间重启时，Agent Run 根据 operationId 查询或重放结果，不重复业务写入。

Redis 能力由 PostgreSQL 和单 JVM 机制代替：

- 幂等：唯一键和请求摘要。
- 调度抢占：PostgreSQL advisory lock 或带租约的条件更新。
- Agent Run 恢复：持久状态机。
- 会话历史：PostgreSQL。
- SSE：单 JVM 连接，客户端携带事件游标重连。
- 可丢失热点缓存：Caffeine；重启后从数据库加载。

定时饮食生成、图片识别和报告生成均持久化任务状态、租约、重试次数和输入 checksum。应用重启后可重新领取过期任务。

## 9. 安全与配置

- Provider、模型、Prompt、Tools、Skills、超时、预算和结构化输出 Schema 由 Agent 工作台配置，不写死在业务代码中。
- 百炼凭据以加密密文存入 `agent` schema；加密主密钥只从服务器 Secret 文件加载。
- OSS 优先使用 ECS RAM Role，不在仓库保存长期 AccessKey。
- JWT 私钥、数据库密码、加密主密钥和生产证书不进入 GitHub。
- 仓库只提交 `.env.example` 与幂等密钥生成脚本。
- Fitness 与 Agent Admin 保留独立的外部授权边界；删除仅供两个旧服务通信的内部授权机制。
- 正式产物不得包含 Fake Runtime、Fake Media 或缺少外部配置时返回伪造结果的降级实现。测试替身只能存在于 test scope。

## 10. 部署拓扑与资源预算

生产只运行三个容器：

| 容器 | 内存限制 | 作用 |
|---|---:|---|
| Nginx | 64MB | HTTPS、反向代理、上传限制、访问日志 |
| Happy Agent App | 1.8GB | 手机端、管理端、全部 API、Agent 与后台任务 |
| PostgreSQL | 768MB | 单数据库、双 schema |

预留约 1.3GB 给 Linux、Docker、文件缓存和突发请求。

JVM 初始配置：

```text
-Xms256m
-Xmx1300m
-XX:MaxMetaspaceSize=256m
-Xss512k
```

运行限制：

- Agent 并发 Run 最多 2 个。
- 图片识别并发最多 1 个。
- 后台任务线程 2 个。
- 两个数据库连接池各最多 3 个连接。
- 媒体采用流式上传，不把完整文件载入 JVM 内存。
- 模型调用设置超时、输入输出长度、总工具次数和成本上限。

生产不运行 Redis、MinIO、Node、Maven 构建环境、内部 egress proxy 或第二个 Java 进程。

## 11. 持久化与重启恢复

宿主机持久目录：

```text
/opt/happy-agent
├── data/postgres
├── logs/app
├── logs/nginx
├── secrets
└── deploy
```

PostgreSQL 数据目录绑定挂载到 `/opt/happy-agent/data/postgres`。应用日志和 Nginx 日志绑定挂载到宿主机并轮转。Secrets 以只读挂载提供给应用。三个容器使用 `restart: unless-stopped`。

以下操作不得丢失数据：应用重启、容器重建、PostgreSQL 容器重启、ECS 正常重启和应用版本升级。应用启动后从 PostgreSQL 恢复用户数据、Agent 配置、会话、Run 和后台任务；SSE 客户端使用游标续传。

数据目录被删除、云盘损坏、误执行破坏性 SQL 或数据库文件损坏仍可能造成数据丢失。V1 不启用自动备份，但提供手动 `pg_dump` 导出脚本，并建议重大升级前执行。

## 12. GitHub 构建与发布

服务器不执行 Maven、Node 或 Docker 多阶段构建。GitHub Actions 流程：

1. Java 和前端测试；
2. 构建 React 产物；
3. 打包单一应用 JAR；
4. 构建单一应用镜像；
5. 保存并压缩镜像；
6. 通过手动 `workflow_dispatch` 把镜像传输到 ECS；
7. ECS 执行 `docker load` 与滚动式容器替换；
8. 健康检查失败则恢复上一镜像。

仓库为 Private。部署密钥存放在 GitHub Actions Secrets；生产运行密钥只存在 ECS `/opt/happy-agent/secrets`，不通过构建参数写入镜像。

## 13. 测试与质量门

- Java 单元测试和业务状态机测试。
- ArchUnit 模块依赖测试。
- Testcontainers 使用一个 PostgreSQL 实例验证双 schema、角色权限和迁移。
- Controller/API 契约与集成测试。
- Agent 工具注册、身份上下文、审批、幂等和恢复测试。
- React 单元测试、生产构建和 Playwright 双端关键流程测试。
- 正式 JAR/镜像扫描：不得包含 Fake、Redis 或内部 Fitness HTTP Client。
- 重启恢复测试：写入业务数据、会话和任务后重启应用及 PostgreSQL，再验证完整恢复。
- 在 Docker 2C4G 限制下执行启动和关键流程冒烟测试。

所有质量门通过后才允许 GitHub Actions 的手动生产部署任务运行。

## 14. 首版产品范围

新项目从零实现此前确认的首版功能：

- 手机端：目标、首页四功能块、训练计划、动作替换、跟练语音、饮食推荐及反馈、图片识别与手工饮食记录、身体指标记录、动作库、当前目标累计报告、历史记录、偏好和 AI 花爷会话。
- Agent 工作台：Provider 与加密凭据、Agent 草稿、工具/Skills、版本、模型探测、评测、发布、回滚、会话、Run、Trace 和 Playground。
- 报告只提供当前目标累计报告；客观身体、饮食和训练记录不与单一目标强绑定。
- AI 输出采用结构化数据，由前端固定组件渲染，不让模型生成任意 HTML。

## 15. 非目标

- 不接入运动手表。
- 不实现支付、社交和医疗诊断。
- 不部署微服务、Redis、消息队列、Kubernetes、ACR、生产告警或自动备份。
- 不在 V1 为未来多实例提前实现分布式缓存或消息广播。
- 不复用旧项目中的双服务部署和内部 HTTP 架构。

## 16. 完成标准

- 新代码全部位于 `happy-agent-platform`，旧项目保持不变。
- Maven 模块、Java 包名和依赖方向符合本设计。
- 生产只包含一个应用进程、一个 PostgreSQL 实例和一个 Nginx 入口。
- 正式环境不依赖 Redis，也没有 Redis 客户端依赖。
- Agent 通过本地 Tool Bean 调用 Fitness Application Service，且不能直接访问 Fitness 数据库。
- 正常服务、容器和 ECS 重启后数据完整恢复。
- 2C4G 限制下关键流程通过。
- GitHub CI 全绿，私有仓库可以通过手动任务发布至 ECS。
