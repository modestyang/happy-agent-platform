# 阿里云 ECS 无域名一键部署设计

日期：2026-08-13
状态：已确认，可进入实施

## 1. 目标

将当前 Happy Agent Platform 从开发机一次性迁移到现有阿里云 ECS，并形成可重复发布、备份、恢复和回滚的单机生产部署能力。

首期没有自有域名，正式入口使用 `https://39.101.65.254`。通过 Let’s Encrypt 公网 IP 短期证书提供浏览器可信 HTTPS；以后获得域名后只替换入口和证书，不迁移数据库、媒体或 Agent 凭据。

## 2. 已确认环境

### 2.1 目标 ECS

- 实例：`i-0jlfb8o4hqpjekoudg4x`
- 地域/可用区：乌兰察布 / `cn-wulanchabu-c`
- 系统：Ubuntu 22.04 x86_64，内核 5.15
- 规格：2 vCPU、4GB 标称内存，系统内可见约 3495MB
- 磁盘：60GB ESSD Entry 单系统盘，当前可用约 53GB
- 公网 IP：`39.101.65.254`
- 当前状态：Docker/Compose 未安装，无现有 Web/数据库服务，80/443/5432/8080 均未占用
- 当前安全组：22、3389、ICMP 对 `0.0.0.0/0` 开放，80/443 未开放
- SSH：本机 `~/.ssh/id_ed25519` 已获 root 授权，服务器主机 ED25519 指纹已验证
- 其他：无 Swap，NTP 正常，自动安全更新已启用，阿里云云助手在线

### 2.2 迁移源

- PostgreSQL 16.14，数据库逻辑大小约 24MB
- `fitness` schema：Fitness Flyway V1–V16
- `agent` schema：预生产单一 Agent Flyway V1
- 本地媒体：2 个文件，约 472KB
- 原 Agent master key：`deploy/secrets/agent-master-key`
- 迁移范围：用户、目标、身体/训练/饮食记录、媒体元数据、Agent 组件/草稿/发布版本、会话、Run、Trace、后台任务、加密 Provider 凭据和本地媒体文件

原 master key 必须逐字节迁移。目标端不能重新生成它，否则现有 Provider 密文无法解密。

## 3. 部署拓扑

生产环境持续运行三个容器：

```text
Internet
  ├── :80  -> Nginx（仅 ACME HTTP-01 与 HTTPS 跳转）
  └── :443 -> Nginx
                 ├── 静态前端
                 └── /api/* -> App :8080
                                  └── PostgreSQL :5432
```

- Nginx 是唯一发布宿主机端口的容器。
- App 和 PostgreSQL 只加入 Compose 私有网络，不向宿主机或公网发布端口。
- Nginx、App、PostgreSQL 使用独立健康检查和 Docker 日志轮转。
- PostgreSQL、媒体、Secrets、证书、备份和发布元数据使用宿主机持久目录。
- Certbot 只作为定时临时容器运行，不作为第四个常驻服务。

## 4. 宿主机目录

```text
/opt/happy-agent/
├── current -> releases/...  # 指向当前健康 release 的原子软链接
├── releases/                # 按 release id 保存的不可变部署包
├── data/
│   ├── postgres/            # PostgreSQL 数据目录
│   ├── media/               # 本地上传媒体
│   └── acme-webroot/        # HTTP-01 challenge
├── secrets/                 # 0600，数据库密码与原 master key
├── certificates/            # Certbot 状态与证书
├── backups/                 # 数据库/媒体/密钥备份
└── logs/                    # 部署、证书续期与必要运维日志
```

`releases/` 中的镜像归档和部署清单不可原地覆盖。`current` 是软链接，只在新版本健康检查通过后原子切换。生产 Secret 不进入 Git、镜像层或命令输出。

## 5. 资源预算

目标机没有 Swap 且系统可见内存小于 4GB，初始化时创建 2GB Swap 文件，权限为 `0600`，写入 `/etc/fstab` 并保持幂等。

初始资源限制：

| 服务 | 容器内存上限 | 关键设置 |
|---|---:|---|
| App | 1800MB | `-Xms256m -Xmx1200m -XX:MaxMetaspaceSize=256m -Xss512k` |
| PostgreSQL | 768MB | `shared_buffers=128MB`、`work_mem=4MB` |
| Nginx | 128MB | 仅静态文件、TLS、代理和访问日志 |

三餐后台生成并发从本地默认 3 调整为生产 2。两个 Hikari 连接池继续各最多 3 个连接。图片识别沿用现有单 Worker 语义。

## 6. 构建与制品

ECS 不安装 Maven、Node 或 JDK 构建环境。可信构建机负责：

1. 校验工作区和源码 commit；
2. 运行后端测试、前端测试/类型检查/构建和格式检查；
3. 构建 Linux amd64 App 镜像与 Nginx/前端镜像；
4. 为镜像、Compose、Nginx 配置和部署脚本生成 SHA-256 manifest；
5. 生成唯一 release id；
6. 通过已验证主机密钥的 SSH/SCP 上传到目标 `releases/<release-id>`。

首次发布由当前 Mac 执行，因为迁移源数据位于此机。后续发布只需要源码、Docker 和 SSH 权限，不依赖当前 Mac 的数据库或媒体。

## 7. 生产配置

新增明确的生产配置闭环：

- 两个 DataSource 均指向 Compose 内部 PostgreSQL 服务；
- `fitness_app` 与 `agent_app` 使用独立密码和 schema；
- Fitness 与 Agent Flyway 保持独立历史表，Agent 继续依赖 Fitness 先完成；
- `cleanDisabled=true` 保持不变；
- `local-seed` 在生产明确关闭；
- 单机首期明确启用本地媒体适配器，并把其现有相对路径绑定到 `/opt/happy-agent/data/media`；
- master key 只读挂载到 App；
- 数据库密码以 Docker Secret 文件形式挂载，App entrypoint 从文件读取并只导出到子进程环境，不写日志或镜像层；
- Nginx 传递可信的 Forwarded 头并关闭 SSE buffering；
- 生产会话 Cookie 使用 `Secure; HttpOnly; SameSite=Lax`，本地 HTTP profile 保持现有开发体验。

手机端与管理端 Cookie 当前写死 `Secure=false`，实施时必须先通过回归测试改为生产可配置策略，再允许部署。

## 8. IP HTTPS

首期证书注册邮箱为 `modest_yang@126.com`。

使用 Certbot 5.4 或更高版本申请：

- 标识：IPv4 `39.101.65.254`
- ACME profile：`shortlived`
- 验证方式：HTTP-01 webroot
- 有效期：约 160 小时

流程：

1. 安全组开放 80/443；
2. 启动仅承载 ACME challenge 的 HTTP Nginx；
3. 先向 Let’s Encrypt staging 申请并验证流程；
4. staging 成功后申请 production IP 证书；
5. 启用 443，并将其他 HTTP 请求跳转到 HTTPS；
6. systemd timer 每 12 小时运行一次 Certbot renew；
7. 续期成功后校验证书 SAN/到期时间，再原子重载 Nginx；
8. 证书剩余时间低于 48 小时且续期失败时，续期任务返回非零并写入独立日志。

不在 IP 阶段启用 HSTS。以后提供域名时，新增域名证书、完成 HTTPS 验收后再决定 HSTS，避免把临时 IP 策略固化到浏览器。

## 9. 首次全量数据迁移

首次迁移是一次有短暂停写窗口的逻辑迁移：

1. 记录当前 Git commit、PostgreSQL 版本、两张 Flyway history 表和关键对象计数；
2. 停止当前本地后端，确认没有本项目进程继续写入数据库；
3. 使用 PostgreSQL 16 `pg_dump --format=custom` 导出完整 `happy_agent` 数据库；
4. 复制媒体目录和原 master key；
5. 生成 manifest，包含文件大小、SHA-256、schema/Flyway 元数据和导出时间；
6. 通过 SSH 上传到目标权限为 `0700` 的迁移暂存目录；
7. 逐项复核目标端 checksum；
8. 初始化目标数据库角色和新随机数据库密码；
9. 断言目标数据库没有业务表/业务行后，才允许恢复；
10. 恢复时保留 schema owner 与 ACL，随后重新施加 schema 隔离规则；
11. 使用与导出对应的同一源码 commit 启动 App，让 Flyway 只执行 validate/no-op；
12. 比较源/目标 Flyway history、表数量、关键对象计数和媒体 checksum；
13. 验证 Provider 凭据可解密，但绝不输出明文；
14. 完成应用验收后再将源端保持为停止状态，源数据库、媒体和 master key不删除。

首次恢复只允许空目标。普通发布、回滚和重复执行不得自动使用 `pg_restore --clean` 覆盖线上数据库。

## 10. 发布与回滚

普通发布不传输数据库、媒体或 master key：

1. 构建并上传新 release；
2. 在 ECS 创建数据库 custom-format 备份、媒体/Secrets manifest；
3. 校验新镜像和当前发布 Agent 所需 Tool；
4. 加载新镜像，保持旧镜像与旧 release；
5. 记录旧镜像后停止旧 App，启动新 App；
6. 等待 PostgreSQL、Flyway 和应用探测通过；
7. 原子切换 `current`，重载 Nginx，并从公网 HTTPS 执行手机端、管理端、静态路由和 SSE 烟雾检查；
8. 健康失败时立即停止新 App，使用已记录的旧镜像和旧 release 恢复服务。

目标机内存不足以可靠地同时运行两个完整 App 实例，因此不声称零停机或蓝绿发布。普通发布接受一次受控的短暂停机，以确定性回滚换取资源安全。

应用版本回滚不自动回滚数据库。若新版本执行了不可逆 schema migration，则只能在有明确恢复授权和维护窗口时从部署前备份恢复。当前 Agent V1 baseline 仍处于预生产可折叠阶段，因此发布 manifest 必须固定源码 commit 和 Agent V1 checksum，避免同版本脚本漂移。

## 11. 健康检查与验收

当前项目未引入 Actuator，旧 `/actuator/health` 代理不能作为真实健康检查。实施采用以下分层门：

- PostgreSQL：`pg_isready`，并验证两个应用角色只访问各自 schema；
- App：进程启动、端口可达、Flyway 成功，现有 bootstrap/auth 端点返回预期 200/401；
- Nginx：80 challenge、HTTP→HTTPS、证书链、443 静态页面和 `/api` 反向代理；
- 数据：Flyway history、关键表计数、Agent 发布版本、媒体 checksum；
- 业务：手机端登录/首页、管理端登录、Agent 发布状态、一次只读 AI/Provider 健康路径；
- 恢复：重启 App、PostgreSQL 容器和 ECS 后数据仍存在；
- 回滚：在本地隔离 Compose fixture 中部署故意不健康的测试 release，确认脚本不会标记其为 current 且能恢复旧版本；生产 ECS 不执行故意破坏服务的演练。

所有自动化测试、格式检查、Compose 校验、Shell 静态检查和本地容器验收通过后，才执行 ECS 首次迁移。

## 12. 安全与云资源改动

获得授权后执行以下云端改动：

- 安全组新增 TCP 80/443 对公网；
- 删除 Ubuntu 不需要的 TCP 3389 全网规则；
- 暂时保留 TCP 22，避免部署期间失联；部署稳定后另行确认可信来源 CIDR；
- 开启 ECS Deletion Protection；
- 不开放 5432、8080；
- 不修改生产 SSHD 为密码登录，不降低主机密钥校验；
- 不提交或回显 AccessKey、数据库密码、Provider 凭据和 master key。

单系统盘仍是故障域。首次方案至少保留：发布前数据库备份、媒体/密钥 manifest，以及构建机通过 SSH 拉回、权限为 `0600` 的恢复包。恢复包是否进一步使用独立离线密钥加密，作为异地备份设计单独确认；本次不引入未确定的加密密钥托管方式。自动 ECS 快照或 OSS 异地备份涉及额外费用，留作单独授权项，不在本次隐式开启。

## 13. 脚本边界

最终对外提供三个稳定入口：

- `bootstrap`：幂等初始化 Ubuntu、Docker、Swap、目录、证书定时器和云安全前置条件；
- `migrate`：仅首次执行的停写、导出、上传、空库恢复和一致性校验；
- `release`：普通构建、上传、备份、健康切换和失败回滚。

另提供服务器端 `status`、`backup`、`restore`、`rollback` 和 `renew-certificate` 子命令。`restore` 和任何覆盖性操作必须要求显式归档路径与交互/标志确认，不能作为普通一键发布的隐式分支。

## 14. 非目标

- 不修改 CI/CD 或创建 GitHub Actions；
- 不新增/修改数据库 migration；
- 不迁移到 RDS、OSS、Redis、Kubernetes；
- 不在 ECS 安装 Maven、Node 或生产外的开发工具链；
- 不自动 commit、push 或创建 PR；
- 不删除本地数据库、媒体、密钥和首次迁移包；
- 不在缺少外部配置时注入假数据或 mock runtime。

## 15. 完成标准

- `https://39.101.65.254` 使用浏览器可信 IP 证书；
- 80 只完成 ACME/跳转，443 提供前端和 API；
- 8080/5432 不可从公网访问；
- 当前本地数据库、媒体和 Agent 加密凭据完整迁移并可用；
- App/PostgreSQL/ECS 重启后数据完整；
- 普通发布不覆盖线上持久数据；
- 新版本不健康时旧版本继续服务或自动恢复；
- 证书续期定时器经过 staging 与一次实际 dry-run 验证；
- 所有相关测试、格式、构建、Compose/Shell 校验和页面烟雾验收有明确通过记录；
- 未执行未经授权的 CI/CD、migration、commit、push、快照收费或域名操作。
