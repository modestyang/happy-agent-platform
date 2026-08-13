# 阿里云 ECS 无域名 HTTPS 部署 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前 Happy Agent Platform 及其 PostgreSQL、媒体文件和 Agent master key 一次性迁移到现有阿里云 ECS，并交付可重复的 `bootstrap`、`migrate`、`release`、`backup`、`rollback`、`status` 和证书续期入口。

**Architecture:** 可信构建机生成 Linux amd64 App/Web/PostgreSQL 镜像归档和 SHA-256 manifest；ECS 只运行 Nginx、App、PostgreSQL 三个 Compose 服务。Nginx 独占公网 80/443，App/PostgreSQL 只在 Compose 网络中可达。状态数据固定在 `/opt/happy-agent/data`，发布包固定在 `/opt/happy-agent/releases`，首次迁移只允许恢复到空目标数据库。

**Tech Stack:** Java 17、Spring Boot、Maven Wrapper、Vite/npm、Docker Engine、Docker Compose v2、PostgreSQL 16.14 + pgvector 0.8.1、Nginx Alpine、Certbot 5.7.0、Bash、systemd、阿里云 CLI。

## Global Constraints

- 已确认目标：`cn-wulanchabu` / `i-0jlfb8o4hqpjekoudg4x` / `sg-0jlb5v2njkb2jbzrvurr` / `39.101.65.254` / Ubuntu 22.04 x86_64 / 2C4G / 60GB。
- 用户已授权首次 ECS 实施、Docker/Compose 安装、2GB Swap、安全组 80/443、删除 3389、Deletion Protection、首次数据库与媒体迁移。
- 不修改 CI/CD，不新增或修改数据库 migration，不 commit/push，不删除源端数据，不开启收费快照或 OSS。
- Secret 只从 gitignored 文件或运行时环境读取；不得写入 Git、镜像层、manifest、命令行参数或日志。
- 首次恢复只接受空目标；普通发布和回滚不得调用覆盖性数据库恢复。
- ECS 不安装 Maven、Node 或 JDK；所有生产镜像均在可信构建机生成 `linux/amd64` 制品。
- IP HTTPS 固定使用 `certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4`、`--preferred-profile shortlived`、HTTP-01 webroot；不使用 `latest`，不启用 HSTS。

## File Structure

```text
deploy/production/
├── deploy.sh                         # 可信构建机唯一入口与子命令路由
├── .env.example                      # 非敏感部署变量示例
├── base-images.lock                  # 四个官方基础镜像的不可变 digest
├── compose.yml                       # Nginx/App/PostgreSQL 三服务定义
├── app.Dockerfile                    # 只打包 exec jar 的 Java 17 运行时镜像
├── app-entrypoint.sh                 # 读取 Docker Secret 后 exec Java
├── web.Dockerfile                    # 只打包 frontend/dist 的 Nginx 镜像
├── nginx/
│   ├── ip-http.conf.template         # ACME challenge 与 HTTP 阶段配置
│   └── ip-https.conf.template        # IP TLS、SPA、API/SSE 代理配置
├── postgres/
│   ├── init-roles.sh                 # 首次启动只创建数据库角色
│   ├── init-roles.sql                # roles-only 权限初始化
│   └── enforce-isolation.sql         # restore 后恢复双 schema 隔离
├── scripts/
│   ├── common.sh                     # 路径、锁、hash、Compose 安全原语
│   ├── build-release.sh              # 测试、构建、镜像归档与 manifest
│   ├── export-initial-data.sh        # 源端停写与首次迁移包导出
│   ├── cloud-guardrails.sh           # 精确安全组和 Deletion Protection 变更
│   ├── bootstrap-host.sh             # Ubuntu/Docker/Swap/目录初始化
│   ├── issue-certificate.sh          # staging 后 production IP 证书申请
│   ├── renew-certificate.sh          # IP 短证书续期、验证与 Nginx reload
│   ├── backup.sh                     # DB/媒体/master key 原子备份
│   ├── restore-initial-data.sh       # 仅空目标首次恢复与一致性检查
│   ├── activate-release.sh           # 备份、切换、健康检查和失败回滚
│   ├── rollback.sh                   # 显式 application-only 回滚
│   └── status.sh                     # 非敏感生产状态摘要
├── systemd/
│   ├── happy-agent-cert-renew.service
│   └── happy-agent-cert-renew.timer
└── tests/
    ├── compose-contract.test.sh
    ├── server-script-safety.test.sh
    ├── local-orchestrator.test.sh
    └── migration-rehearsal.sh
```

---

### Task 1：让生产会话 Cookie 可安全配置

**Files:**

- Create: `starter/src/main/java/happy/jayden/yang/config/SessionCookieFactory.java`
- Create: `starter/src/test/java/happy/jayden/yang/config/SessionCookieFactoryTest.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/LocalAuthController.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminAuthController.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`
- Modify: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**

- Consumes: Spring property `happy.security.secure-cookies`，默认值 `false`。
- Produces: `public ResponseCookie SessionCookieFactory.create(String name, String value, Duration maxAge)`；两个 Controller 构造器新增 `SessionCookieFactory` 参数。

- [ ] **Step 1：写失败的 Cookie 工厂测试**

覆盖以下行为：

```java
@Test
void productionCookieIsSecureHttpOnlyLaxAndRootScoped() {
  ResponseCookie cookie = new SessionCookieFactory(true)
      .create("SESSION", "token", Duration.ofDays(14));

  assertThat(cookie.isSecure()).isTrue();
  assertThat(cookie.isHttpOnly()).isTrue();
  assertThat(cookie.getSameSite()).isEqualTo("Lax");
  assertThat(cookie.getPath()).isEqualTo("/");
}

@Test
void localCookieCanRemainNonSecure() {
  assertThat(new SessionCookieFactory(false)
      .create("SESSION", "token", Duration.ofDays(14)).isSecure()).isFalse();
}
```

同时在两个现有集成测试中增加登录 Cookie 默认 `secure=false` 的断言，避免破坏本地 HTTP；增加 logout Cookie 的 `HttpOnly`、`SameSite=Lax`、`Path=/` 和 `Max-Age=0` 断言。

- [ ] **Step 2：运行测试并确认失败原因正确**

Run:

```bash
./mvnw -q -pl starter -am \
  -Dtest=SessionCookieFactoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，因为 `SessionCookieFactory` 尚不存在。

- [ ] **Step 3：实现单一 Cookie 构造点**

`SessionCookieFactory` 是 `starter` 内的 Spring component，读取：

```java
@Value("${happy.security.secure-cookies:false}") boolean secure
```

唯一公开行为：

```java
ResponseCookie create(String name, String value, Duration maxAge)
```

它统一设置 `HttpOnly`、`Secure`、`SameSite=Lax`、`Path=/` 和 `Max-Age`。两个 Controller 通过构造器注入工厂，登录和退出都调用该工厂，删除两处重复且写死的 Cookie builder。

- [ ] **Step 4：运行聚焦与集成测试**

Run:

```bash
./mvnw -q -pl starter -am \
  -Dtest=SessionCookieFactoryTest,FitnessExperienceIntegrationTest,AdminWorkbenchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS；默认 profile 的登录/退出 Cookie 保持非 Secure，但所有安全属性一致。

- [ ] **Step 5：格式化并检查差异**

Run:

```bash
./mvnw -q -pl starter spotless:apply
git diff --check
```

Expected: PASS；只包含本 Task 文件，不 commit。

---

### Task 2：建立生产 Spring 配置与 App/Web 镜像契约

**Files:**

- Create: `starter/src/main/resources/application-prod.yml`
- Create: `starter/src/test/java/happy/jayden/yang/config/ProductionProfileStaticTest.java`
- Create: `deploy/production/app.Dockerfile`
- Create: `deploy/production/app-entrypoint.sh`
- Create: `deploy/production/web.Dockerfile`
- Create: `deploy/production/.env.example`
- Create: `deploy/production/base-images.lock`

**Interfaces:**

- Consumes: `FITNESS_DB_PASSWORD_FILE`、`AGENT_DB_PASSWORD_FILE`、`HAPPY_AGENT_MASTER_KEY_FILE` 和 `SPRING_PROFILES_ACTIVE=prod`。
- Produces: `happy-agent-app:${RELEASE_ID}`、`happy-agent-web:${RELEASE_ID}` 两个 `linux/amd64` 镜像；App 在容器网络监听 8080，Web 提供 `/usr/share/nginx/html`。

- [ ] **Step 1：写失败的生产配置静态测试**

读取 `application-prod.yml` 和镜像文件，断言：

- 两个 DataSource 都使用 `jdbc:postgresql://postgres:5432/happy_agent`；
- Fitness/Agent 分别读取 `FITNESS_DB_PASSWORD`、`AGENT_DB_PASSWORD`；
- 两个 local seed 均明确为 `false`；
- local media 为 `true`；
- meal-plan concurrency 为 `2`；
- master key 路径为 `/run/secrets/agent-master-key`；
- `happy.security.secure-cookies=true`；
- `server.forward-headers-strategy=framework`；
- App Dockerfile 不复制 Secret、不包含 Maven/Node，Web Dockerfile 只复制 `frontend/dist`；
- entrypoint 对三个 Secret 文件执行可读、非空、无 NUL/换行约束后才 `exec java`。
- `base-images.lock` 精确包含以下已从 registry 解析的 multi-arch digest：

```text
eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38
nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46
certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4
postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
```

- [ ] **Step 2：运行测试并确认失败**

Run:

```bash
./mvnw -q -pl starter -am \
  -Dtest=ProductionProfileStaticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，因为生产文件尚不存在。

- [ ] **Step 3：实现 `application-prod.yml`**

生产配置只引用运行时环境变量，不写入真实值：

```yaml
happy:
  datasource:
    fitness:
      url: ${HAPPY_DB_URL:jdbc:postgresql://postgres:5432/happy_agent}
      username: fitness_app
      password: ${FITNESS_DB_PASSWORD}
    agent:
      url: ${HAPPY_DB_URL:jdbc:postgresql://postgres:5432/happy_agent}
      username: agent_app
      password: ${AGENT_DB_PASSWORD}
  security:
    secure-cookies: true
  fitness:
    local-seed:
      enabled: false
    local-media:
      enabled: true
    meal-plan:
      concurrency: 2
  agent:
    workbench:
      local-seed:
        enabled: false
      master-key-file: ${HAPPY_AGENT_MASTER_KEY_FILE:/run/secrets/agent-master-key}

server:
  port: 8080
  forward-headers-strategy: framework
```

- [ ] **Step 4：实现运行时镜像**

- App 镜像只包含 `starter-*-exec.jar`、entrypoint 和容器内 HTTP 健康检查所需的最小工具；`WORKDIR /app`。
- entrypoint 从 `/run/secrets/fitness_db_password` 与 `/run/secrets/agent_db_password` 读取密码，导出给 Java 子进程；master key 保持文件读取，绝不回显。
- JVM 参数固定为 `-Xms256m -Xmx1200m -XX:MaxMetaspaceSize=256m -Xss512k`。
- Web 镜像基于版本固定的 Nginx Alpine，只复制 `frontend/dist` 到 `/usr/share/nginx/html`。
- `.env.example` 只提供非敏感路径/镜像变量示例；不包含密码、AccessKey 或 master key。

- [ ] **Step 5：运行测试和 Dockerfile 静态检查**

Run:

```bash
./mvnw -q -pl starter -am \
  -Dtest=ProductionProfileStaticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
bash -n deploy/production/app-entrypoint.sh
git diff --check
```

Expected: PASS。

---

### Task 3：建立三服务 Compose、Nginx IP HTTPS 和空库初始化

**Files:**

- Create: `deploy/production/compose.yml`
- Create: `deploy/production/nginx/ip-http.conf.template`
- Create: `deploy/production/nginx/ip-https.conf.template`
- Create: `deploy/production/postgres/init-roles.sh`
- Create: `deploy/production/postgres/init-roles.sql`
- Create: `deploy/production/postgres/enforce-isolation.sql`
- Create: `deploy/production/tests/compose-contract.test.sh`
- Modify: `starter/src/test/java/happy/jayden/yang/config/DatabaseInfrastructureStaticTest.java`

**Interfaces:**

- Consumes: Task 2 的 App/Web 镜像标签、`/opt/happy-agent` 持久目录和三个数据库 Secret 文件。
- Produces: `docker compose -p happy-agent -f deploy/production/compose.yml`；服务名固定为 `postgres`、`app`、`nginx`；恢复后入口为 `postgres/init-roles.sh` 与 `postgres/enforce-isolation.sql`。

- [ ] **Step 1：写失败的 Compose 契约测试**

脚本必须先在临时目录创建假的 Secret 和部署目录，然后运行：

```bash
docker compose \
  --env-file deploy/production/.env.example \
  -f deploy/production/compose.yml config --quiet
```

并静态断言：

- 只有 Nginx 发布 `80:80` 和 `443:443`；
- App/PostgreSQL 不含 `ports`；
- 三个服务均有 healthcheck、`restart: unless-stopped` 和日志轮转；
- 内存限制分别为 App 1800MB、PostgreSQL 768MB、Nginx 128MB；
- PostgreSQL 参数含 `shared_buffers=128MB`、`work_mem=4MB`；
- PostgreSQL、媒体、ACME、证书、Secret 均来自 `/opt/happy-agent` 持久路径；
- App 把媒体目录挂载到 `/app/deploy/.local/media`；
- Nginx 代理仅指向 `app:8080`，SSE buffering 关闭；
- HTTP 配置只有 challenge 与 HTTPS 跳转；HTTPS 配置不启用 HSTS；
- `certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4` 由运维脚本临时运行，不是常驻服务。

Java 静态测试增加生产初始化约束：roles-only 初始化不能创建 `fitness`/`agent` schema，恢复后的隔离 SQL 必须撤销跨 schema 权限并设置各自 search path。

- [ ] **Step 2：运行测试并确认失败**

Run:

```bash
bash deploy/production/tests/compose-contract.test.sh
./mvnw -q -pl starter -am \
  -Dtest=DatabaseInfrastructureStaticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，因为生产 Compose 和 SQL 尚不存在。

- [ ] **Step 3：实现 Compose**

Compose 使用固定 project name `happy-agent`，服务职责如下：

```text
postgres: custom PostgreSQL 16.14 + pgvector image, bind data, roles-only init
app:      exec jar, bind media/master key, Docker secrets for DB passwords
nginx:    web image, bind active nginx.conf/acme/certificates, publish 80/443
```

App healthcheck 请求 `http://127.0.0.1:8080/api/app/bootstrap`，仅接受 `200` 或未认证预期的 `401`；这同时证明 Web 线程和数据库认证路径可用。PostgreSQL 使用 `pg_isready`。Nginx 提供内部 `/healthz`。

- [ ] **Step 4：实现首次恢复专用数据库初始化**

- `init-roles.sql` 只创建/更新 `fitness_app` 与 `agent_app` 角色、撤销 public 权限、授予数据库 CONNECT；不得提前创建业务 schema。
- `enforce-isolation.sql` 在 `pg_restore` 后把 `fitness` owner 设为 `fitness_app`、`agent` owner 设为 `agent_app`，撤销互访，设置 search path 与 default privileges。
- 所有 `psql` 调用使用 `ON_ERROR_STOP=1`，Secret 通过 psql variable 传入并禁止命令 trace。

- [ ] **Step 5：实现 Nginx 双阶段配置**

HTTP 阶段：

```nginx
location ^~ /.well-known/acme-challenge/ {
    root /var/www/acme;
    try_files $uri =404;
}
```

HTTPS 阶段额外提供静态 SPA fallback、`/api/` 代理、可信 Forwarded 头、SSE `proxy_buffering off`、`proxy_connect_timeout 5s`、`proxy_send_timeout 3600s`、`proxy_read_timeout 3600s` 和 `client_max_body_size 20m`。80 除 challenge 外永久跳转到相同 host 的 HTTPS；不启用 HSTS。

- [ ] **Step 6：运行契约和格式验证**

Run:

```bash
bash deploy/production/tests/compose-contract.test.sh
./mvnw -q -pl starter -am \
  -Dtest=DatabaseInfrastructureStaticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

Expected: PASS。

---

### Task 4：实现服务器端幂等初始化、证书、备份、恢复和回滚

**Files:**

- Create: `deploy/production/scripts/common.sh`
- Create: `deploy/production/scripts/bootstrap-host.sh`
- Create: `deploy/production/scripts/issue-certificate.sh`
- Create: `deploy/production/scripts/renew-certificate.sh`
- Create: `deploy/production/scripts/backup.sh`
- Create: `deploy/production/scripts/restore-initial-data.sh`
- Create: `deploy/production/scripts/activate-release.sh`
- Create: `deploy/production/scripts/rollback.sh`
- Create: `deploy/production/scripts/status.sh`
- Create: `deploy/production/systemd/happy-agent-cert-renew.service`
- Create: `deploy/production/systemd/happy-agent-cert-renew.timer`
- Create: `deploy/production/tests/server-script-safety.test.sh`

**Interfaces:**

- Consumes: Task 3 的 Compose/Nginx/PostgreSQL 文件、release 目录、migration bundle 与固定环境变量 `HAPPY_AGENT_ROOT`。
- Produces: 可直接在 Ubuntu root 下执行的 `bootstrap-host.sh`、`issue-certificate.sh`、`renew-certificate.sh`、`backup.sh`、`restore-initial-data.sh`、`activate-release.sh`、`rollback.sh`、`status.sh`；所有修改型脚本以 `flock` 串行化。

- [ ] **Step 1：写失败的服务器脚本安全测试**

使用 fake `docker`、`systemctl`、`apt-get` 和临时 `HAPPY_AGENT_ROOT`，覆盖：

- 非 root、非 Ubuntu 22.04、非 x86_64 时 bootstrap 拒绝执行；
- 重复 bootstrap 不重复追加 `/etc/fstab`，2GB Swap 仅创建一次；
- 所有 Secret 文件为 `0600`，目录为 `0700`；
- restore 缺少归档、checksum 不匹配、目标非空、缺少明确 `--initial-empty-target` 时退出非零；
- restore 命令绝不包含 `pg_restore --clean`；
- activate 先备份、再替换容器、健康后才切换 `current`；
- 新 release 健康失败时调用旧 release compose，并保持/恢复旧 `current`；
- rollback 不触碰数据库和媒体；
- renew 使用锁定 digest 的 `certbot/certbot:v5.7.0`、`--preferred-profile shortlived`，剩余小于 48 小时且续期失败时退出非零；
- `status` 不打印 Secret 内容。

- [ ] **Step 2：运行测试并确认失败**

Run:

```bash
bash deploy/production/tests/server-script-safety.test.sh
```

Expected: FAIL，因为脚本尚不存在。

- [ ] **Step 3：实现共享安全原语**

`common.sh` 提供：

- `require_root`、`require_command`、`require_file`；
- 对 `/opt/happy-agent` 目标的绝对路径校验，拒绝 `/`、`~`、空变量和未解析 glob；
- `sha256sum --check`；
- `flock` 部署互斥锁；
- `compose` 封装固定 `-p happy-agent`；
- 原子 symlink 切换；
- 不输出 Secret 的结构化日志。

- [ ] **Step 4：实现 `bootstrap-host.sh`**

动作顺序：

1. 校验 Ubuntu 22.04 x86_64、root、磁盘和端口；
2. 使用 Docker 官方 apt repository 安装 Engine 与 Compose plugin；
3. 创建 2GB `/swapfile`、`0600`、启用并幂等写入 `/etc/fstab`；
4. 创建 `/opt/happy-agent/{releases,data/postgres,data/media,data/acme-webroot,secrets,certificates/staging,certificates/production,backups,logs}`；
5. 生成三个数据库随机密码文件（仅在不存在时）；
6. 安装 systemd renew service/timer，但只有证书存在后才启用 timer；
7. 输出 Docker/Compose/Swap/目录的非敏感摘要。

- [ ] **Step 5：实现 Certbot 申请与续期**

首次申请：

```bash
docker run --rm \
  -v /opt/happy-agent/certificates/production:/etc/letsencrypt \
  -v /opt/happy-agent/data/acme-webroot:/var/www/acme \
  certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4 certonly \
  --webroot -w /var/www/acme \
  --ip-address 39.101.65.254 \
  --preferred-profile shortlived \
  --cert-name happy-agent-ip \
  --email modest_yang@126.com --agree-tos --non-interactive
```

脚本先把相同命令改用 `/opt/happy-agent/certificates/staging`、`--cert-name happy-agent-ip-staging` 和 `--staging` 验证 Let’s Encrypt staging，成功后才执行上述 production 命令。续期每 12 小时运行；成功后用 OpenSSL 验证 SAN 含 `39.101.65.254`、到期时间和链文件，再 `nginx -t` 与 reload。失败写入 `/opt/happy-agent/logs/cert-renew.log`。

- [ ] **Step 6：实现首次恢复、备份与发布切换**

- `restore-initial-data.sh`：校验 manifest；确认 PostgreSQL 用户对象为零；导入 custom dump；运行 `enforce-isolation.sql`；校验两个 Flyway history、表数、关键对象计数、媒体 checksum；复制 master key 时保持 byte-exact 和 `0600`。
- `backup.sh`：先令 `timestamp="$(date -u +%Y%m%dT%H%M%SZ)"`，在 `/opt/happy-agent/backups/${timestamp}` 创建 custom dump、媒体 archive、master key copy、release/manifest metadata；完成前使用同一父目录下的 `.pending-${timestamp}`，最后原子 rename。
- `activate-release.sh`：校验 release manifest、加载镜像、调用 backup、保留旧 release、启动新服务、等待 health、切换 `current`、公网烟雾检查；失败自动回到旧 release。
- `rollback.sh`：要求显式 release id，只切应用镜像/配置，不自动恢复数据库。
- `status.sh`：输出 current release、三服务状态、证书 SAN/expiry、磁盘/内存/Swap、最近备份。

- [ ] **Step 7：实现并验证 systemd 单元**

Service 使用 `Type=oneshot`、明确 WorkingDirectory 和绝对脚本路径；Timer 使用：

```ini
OnBootSec=10min
OnUnitActiveSec=12h
Persistent=true
```

Run:

```bash
bash deploy/production/tests/server-script-safety.test.sh
find deploy/production/scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
git diff --check
```

Expected: PASS。

---

### Task 5：实现可信构建机的一键入口与首次迁移包

**Files:**

- Create: `deploy/production/deploy.sh`
- Create: `deploy/production/scripts/build-release.sh`
- Create: `deploy/production/scripts/export-initial-data.sh`
- Create: `deploy/production/scripts/cloud-guardrails.sh`
- Create: `deploy/production/tests/local-orchestrator.test.sh`
- Modify: `.gitignore`

**Interfaces:**

- Consumes: Tasks 2–4 的镜像、Compose 和服务器脚本；本地 Docker、Maven、npm、Aliyun CLI 与 `~/.ssh/id_ed25519`。
- Produces: `deploy/production/deploy.sh`，其第一个位置参数严格枚举为 `bootstrap`、`migrate`、`release`、`status`、`backup`、`rollback`；release 包不含状态数据，migration bundle 只由 `migrate` 生成。

- [ ] **Step 1：写失败的本地编排测试**

使用 fake `aliyun`、`ssh`、`scp`、`docker`，断言：

- 仅接受 `bootstrap|migrate|release|status|backup|rollback` 子命令；
- 默认目标严格等于已确认 instance/region/IP，主机指纹通过独立 known_hosts 文件固定；
- `migrate` 必须确认本项目本地后端已停止，数据库 dump 失败后不得继续打包/上传；
- migration archive 必须包含 DB dump、媒体 archive、原 master key、源 commit、两张 Flyway history、关键表计数和 SHA-256 manifest；
- `release` 不包含 DB、媒体或 master key；
- build 必须先测试/格式/typecheck，再构建镜像；
- 上传前后都校验 manifest；
- cloud guardrail 只新增 TCP 80/443、删除 TCP 3389、开启 Deletion Protection，保留 TCP 22，绝不开放 5432/8080；
- 任一步非零即停止，禁止 `|| true` 假通过。

- [ ] **Step 2：运行测试并确认失败**

Run:

```bash
bash deploy/production/tests/local-orchestrator.test.sh
```

Expected: FAIL，因为一键入口尚不存在。

- [ ] **Step 3：实现 `build-release.sh`**

执行门：

```bash
./mvnw test
./mvnw spotless:check
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
./mvnw -DskipTests -pl starter -am package
```

随后构建 `linux/amd64` App/Web/PostgreSQL 镜像，release id 使用 UTC timestamp + Git short SHA。每个镜像 `docker save` 到不可变 release staging 目录；manifest 记录源码 commit、工作区是否有改动、文件 hash、镜像 RepoDigest/ID、Agent V1 checksum 和构建工具版本。只在 manifest 完成后把 staging 目录 rename 为 release id。

工作区允许包含本次尚未 commit 的部署变更，但脚本必须把 `git diff` hash 写入 manifest，使制品可追溯；不能假称纯 commit 构建。

- [ ] **Step 4：实现首次迁移导出**

`export-initial-data.sh`：

1. 识别本项目后端 PID 并停止，确认 8080 不再由本项目占用；
2. 等待 PostgreSQL checkpoint；
3. 从现有 Docker PostgreSQL 16 容器执行 `pg_dump --format=custom --dbname=happy_agent` 的全库导出，不传 `--no-owner`，从而保留对象 owner；
4. 导出 source validation JSON：数据库版本、schema/table 数、Flyway rows/checksum、关键对象 count；
5. 归档 `deploy/.local/media`；
6. byte-copy `deploy/secrets/agent-master-key`；
7. 生成 SHA-256 manifest，目录与文件权限分别为 `0700`/`0600`；
8. 不自动重启源后端，不删除源数据库和媒体。

- [ ] **Step 5：实现云资源门禁**

`cloud-guardrails.sh` 先读取当前安全组/实例属性，再按差异执行阿里云 API：

- `AuthorizeSecurityGroup`：TCP 80/443，source `0.0.0.0/0`；
- `RevokeSecurityGroup`：现有 TCP 3389 全网规则；
- `ModifyInstanceAttribute`：`DeletionProtection=true`；
- 复查 TCP 22 仍存在，5432/8080 不存在；
- 输出规则 ID/端口和保护状态，不输出 CLI credential。

- [ ] **Step 6：实现顶层 `deploy.sh`**

```text
bootstrap -> cloud guardrails -> upload bootstrap bundle -> remote bootstrap -> HTTP Nginx
migrate   -> build exact release -> stop local writes -> export/upload -> empty restore -> HTTPS -> validate
release   -> build/upload -> remote backup/activate -> public smoke -> pull backup receipt
status    -> remote status
backup    -> remote backup -> SCP pull recovery package with 0600
rollback  -> explicit release id -> remote application-only rollback
```

所有 SSH 调用使用 `BatchMode=yes`、`StrictHostKeyChecking=yes`、显式 `UserKnownHostsFile` 和 `IdentitiesOnly=yes`。

- [ ] **Step 7：更新忽略规则并运行验证**

`.gitignore` 增加生产本地 artifacts/known_hosts/migration bundle 的精确目录规则，不使用可能吞掉模板的宽泛 glob。

Run:

```bash
bash deploy/production/tests/local-orchestrator.test.sh
find deploy/production -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
git diff --check
```

Expected: PASS，且 `git status --short` 不出现任何 migration dump、Secret 或镜像 tar。

---

### Task 6：本地完整验证与可丢弃迁移演练

**Files:**

- Create: `deploy/production/tests/migration-rehearsal.sh`
- Modify: `deploy/ALIYUN_DEPLOY.md`
- Modify: `progress.md`
- Modify: `findings.md`

**Interfaces:**

- Consumes: Task 5 顶层入口与当前本地 PostgreSQL/媒体/master key 的只读副本。
- Produces: 在独立临时 Compose project 中可重复运行的 `migration-rehearsal.sh`，以及与实际入口一致的 `deploy/ALIYUN_DEPLOY.md`。

- [ ] **Step 1：写迁移演练脚本**

演练使用 `mktemp -d` 创建全新的 Compose project 和 volume，绝不复用 `/opt/happy-agent` 或当前本地 PostgreSQL data。它完成：

1. 从源库创建临时 custom dump；
2. 启动空 PostgreSQL target，确认业务 schema 不存在；
3. 执行生产 `restore-initial-data.sh`；
4. 启动生产 App/Web 镜像；
5. 比较 Flyway history、关键表计数、媒体 checksum；
6. 验证两个数据库角色不能读取对方 schema；
7. 用原 master key 执行只验证成功/失败、不打印明文的 Provider 凭据解密路径；
8. 停启 PostgreSQL/App 后重复数据校验；
9. 模拟不健康 release，验证 `current` 未切换且旧 release 恢复；
10. 退出时只清理脚本创建并经过路径校验的临时目录/Compose project。

- [ ] **Step 2：先让演练暴露实现缺口，再修正最小问题**

Run:

```bash
bash deploy/production/tests/migration-rehearsal.sh
```

Expected: 首次运行若失败，只修改对应生产脚本/契约并补回归断言；不得通过放宽校验或 `|| true` 绕过。

- [ ] **Step 3：执行项目级完整验证**

Run:

```bash
./mvnw spotless:apply
./mvnw test
./mvnw -q -pl architecture-tests test
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
bash deploy/local-run.test.sh
bash deploy/production/tests/compose-contract.test.sh
bash deploy/production/tests/server-script-safety.test.sh
bash deploy/production/tests/local-orchestrator.test.sh
bash deploy/production/tests/migration-rehearsal.sh
git diff --check
```

Expected: 全部 PASS。若 Testcontainers/Docker 权限或网络阻断，申请对应运行授权后原命令重跑，并记录真实结果。

- [ ] **Step 4：更新部署文档**

`deploy/ALIYUN_DEPLOY.md` 改为当前唯一操作手册，包含：

- 已确认拓扑、入口与目录；
- 首次 `bootstrap` / `migrate` 命令；
- 后续 `release` 命令；
- `status`、`backup`、`rollback`、证书续期检查；
- 域名到位后的替换点；
- RPO/RTO、单盘风险、无蓝绿/短暂停机说明；
- 明确禁止恢复到非空目标和普通发布携带 Secret/数据。

- [ ] **Step 5：人工代码与安全复核**

Run:

```bash
git status --short
git diff --stat
git diff --check
rg -n "(BEGIN .*PRIVATE KEY|ALIYUN_ACCESS|AccessKeySecret|admin123|demo123)" \
  deploy/production starter/src/main/resources/application-prod.yml
```

Expected: 只存在预期代码/文档；无真实 Secret；不 commit。

---

### Task 7：执行 ECS bootstrap 与云安全变更

**Remote mutation checkpoint:** 仅在 Task 1–6 全部通过后执行。工具若再次要求权限，使用最小命令前缀申请；不得用宽泛 shell 授权。

**Interfaces:**

- Consumes: Task 5 的 `deploy.sh bootstrap`、Aliyun profile `ecs-audit`、已固定 SSH key/host fingerprint。
- Produces: 安全组 `sg-0jlb5v2njkb2jbzrvurr` 的最小公网端口、启用 Deletion Protection、完成 Docker/Swap/目录初始化且无业务数据的 ECS。

- [ ] **Step 1：刷新只读事实并对比设计**

Run:

```bash
aliyun ecs DescribeInstances --profile ecs-audit --region cn-wulanchabu \
  --InstanceIds '["i-0jlfb8o4hqpjekoudg4x"]' \
  --output cols=InstanceId,Status,OSName,PublicIpAddress.IpAddress,SecurityGroupIds.SecurityGroupId,DeletionProtection rows='Instances.Instance[]'
aliyun ecs DescribeSecurityGroupAttribute --profile ecs-audit --region cn-wulanchabu \
  --SecurityGroupId sg-0jlb5v2njkb2jbzrvurr \
  --output cols=IpProtocol,PortRange,SourceCidrIp,Policy rows='Permissions.Permission[]'
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=deploy/.local/production/known_hosts \
  root@39.101.65.254 \
  'uname -m; . /etc/os-release; printf "%s %s\n" "$ID" "$VERSION_ID"; df -h /; free -m; swapon --show; ss -lnt'
```

Expected: instance/IP/OS/磁盘/空端口/安全组与设计一致。任何漂移都停止，不猜测目标。

- [ ] **Step 2：运行云资源门禁**

Run:

```bash
deploy/production/deploy.sh bootstrap
```

Expected:

- 安全组 80/443 开放，3389 删除，22 保留，5432/8080 未开放；
- Deletion Protection 为 true；
- Docker/Compose 可用；
- Swap 为 2GB 且 `/etc/fstab` 仅一条；
- `/opt/happy-agent` 权限正确；
- HTTP challenge Nginx 可访问；
- 尚未写入本地 DB、媒体或 master key。

- [ ] **Step 3：重复运行 bootstrap 验证幂等**

Run:

```bash
deploy/production/deploy.sh bootstrap
```

Expected: 无重复安全组规则、无重复 fstab、Secret 不旋转、数据目录不清空。

---

### Task 8：首次停写迁移与可信 IP HTTPS 上线

**Interfaces:**

- Consumes: Task 7 的空 ECS、Task 5 的 `deploy.sh migrate`、当前本地源库/媒体/master key 与证书邮箱 `modest_yang@126.com`。
- Produces: `https://39.101.65.254`、目标 current release、首份 production backup、拉回本地且权限为 `0600` 的 recovery package。

- [ ] **Step 1：记录源端最终基线**

保存源码 commit/diff hash、PostgreSQL version、两张 Flyway history、关键对象 count、媒体 checksum、master key checksum。输出中只能出现 checksum，不能出现 Secret 内容。

- [ ] **Step 2：执行一次性 migrate**

Run:

```bash
deploy/production/deploy.sh migrate
```

Expected sequence:

1. Task 6 验证门再次通过；
2. 本地后端停止且保持停止；
3. migration bundle 创建、上传、两端 checksum 一致；
4. 目标空库断言通过后才 restore；
5. App 以 `prod` profile 启动，Flyway validate/no-op；
6. staging IP certificate 申请成功；
7. production IP certificate 申请成功，SAN 为 `39.101.65.254`；
8. HTTPS Nginx 启用；
9. 源/目标 Flyway、关键对象、媒体、Provider 解密校验一致；
10. 目标端创建第一份完整 backup，本地拉回权限 `0600` 的 recovery package。

任一步失败：不删除源端；若尚未切换到 HTTPS，则保持 HTTP challenge 或旧服务；若已切换 App，则 `activate-release.sh` 恢复旧 release。首次没有旧 release 时保持数据库/迁移包，停止 App/Nginx 并报告确切阶段，不自动清库重试。

- [ ] **Step 3：公网验收**

Run:

```bash
probe=happy-agent-acceptance-20260813
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=deploy/.local/production/known_hosts \
  root@39.101.65.254 \
  "mkdir -p /opt/happy-agent/data/acme-webroot/.well-known/acme-challenge && printf '%s' '${probe}' > /opt/happy-agent/data/acme-webroot/.well-known/acme-challenge/${probe}"
curl --fail --show-error "http://39.101.65.254/.well-known/acme-challenge/${probe}"
curl --head http://39.101.65.254/
curl --fail --show-error https://39.101.65.254/
curl --include https://39.101.65.254/api/app/bootstrap
openssl s_client -connect 39.101.65.254:443 -servername 39.101.65.254 </dev/null
```

Expected:

- challenge 200；其他 HTTP 308 到相同 IP HTTPS；
- HTTPS 静态页面 200；bootstrap 为预期 401/200；
- 证书链可信、SAN 是 IP、有效期符合 short-lived profile；
- 从公网连接 5432/8080 失败；
- 手机端与管理端登录 Cookie 含 `Secure; HttpOnly; SameSite=Lax`；
- SSE 路径不被 Nginx 缓冲。

- [ ] **Step 4：业务与数据人工验收**

使用已有账号验证：

- 手机端登录、首页、训练/饮食/身体记录和媒体；
- 管理端登录、Agent 组件/草稿/发布版本；
- Provider 凭据可被应用使用但不显示明文；
- 一次只读或低成本 AI 健康请求；
- current release 与 manifest 匹配。

不在验收中创建或删除大批生产数据。

---

### Task 9：重启、续期和后续发布验收

**Interfaces:**

- Consumes: Task 8 的健康 production release、backup receipt 和 systemd renew timer。
- Produces: 容器重启、ECS reboot、证书 timer、no-op release 的验收证据，以及最终 `progress.md`/`findings.md` 记录。

- [ ] **Step 1：验证容器持久性**

Run:

```bash
for service in app postgres nginx; do
  ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile=deploy/.local/production/known_hosts \
    root@39.101.65.254 \
    "cd /opt/happy-agent/current && docker compose -p happy-agent --env-file /opt/happy-agent/secrets/compose.env -f compose.yml restart ${service}"
  deploy/production/deploy.sh status
done
```

Expected: 三次状态均健康，数据 count、媒体、Secret、证书 checksum 均不变。

- [ ] **Step 2：验证 ECS reboot 持久性**

在保存 Task 8 backup receipt 后执行：

```bash
aliyun ecs RebootInstance --profile ecs-audit --region cn-wulanchabu \
  --InstanceId i-0jlfb8o4hqpjekoudg4x --ForceStop false
deploy/production/deploy.sh status
```

`status` 自带最多 10 分钟的 SSH/容器健康等待。Expected: Compose 服务自动启动，HTTPS 和全部持久数据恢复，Swap 仍启用。

- [ ] **Step 3：验证证书定时器**

Run:

```bash
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=deploy/.local/production/known_hosts \
  root@39.101.65.254 \
  'systemctl status happy-agent-cert-renew.timer --no-pager && systemctl start happy-agent-cert-renew.service && journalctl -u happy-agent-cert-renew.service --no-pager -n 100'
```

Expected: timer enabled/active；手动执行成功或明确返回“尚不需要续期”；证书剩余时间门正常，无 Nginx 中断。

- [ ] **Step 4：验证普通 release 不搬运数据**

用同一源码制品执行一次 no-op release：

```bash
deploy/production/deploy.sh release
```

Expected: 发布前 backup 创建；数据库/媒体/master key checksum 不变；健康后 current 更新；不健康模拟仅在本地 rehearsal，不在生产制造故障。

- [ ] **Step 5：最终证据归档**

更新 `progress.md` 与 `findings.md`：

- 本地测试命令和退出码；
- ECS 当前 release/image IDs；
- 安全组端口与 Deletion Protection；
- Flyway/关键对象/媒体一致性；
- 证书 SAN/expiry/timer；
- backup/recovery package 路径与 checksum；
- 未执行项：commit、push、CI、migration、快照、OSS、域名。

最终运行：

```bash
git status --short
git diff --check
deploy/production/deploy.sh status
```

Expected: 代码树无 Secret/制品泄漏；生产健康；所有未执行边界明确记录。

---

## 实施结束判定

只有同时满足以下条件才可报告成功：

- 本计划所有本地测试、构建、迁移演练通过；
- ECS 80/443 正常，5432/8080 不暴露，3389 已移除，Deletion Protection 已开启；
- `https://39.101.65.254` 使用可信 IP SAN 证书；
- 当前 DB、媒体和原 master key 一致迁移，Provider 凭据可解密；
- App/PostgreSQL/Nginx 和 ECS reboot 后持久；
- backup、rollback、证书 timer 均有实测证据；
- 未提交 Secret，未 commit/push，未改 CI/database migration，未开启收费资源。
