# 阿里云 ECS 生产部署手册

当前生产契约是 IP HTTPS：公网入口 `https://39.101.65.254`，ECS 位于
`cn-wulanchabu`，实例 `i-0jlfb8o4hqpjekoudg4x`，安全组
`sg-0jlb5v2njkb2jbzrvurr`。Nginx 独占公网 80/443；App 8080 与 PostgreSQL
5432 只存在于 Compose 网络。ECS 只加载可信构建机生成的 Linux amd64 镜像，不安装
Maven、Node 或 JDK。

生产根目录为 `/opt/happy-agent`：release 位于 `releases/`，`current` 指向当前
release；数据库、媒体和 Agent master key 组成 `state/generations/<id>`，由
`state/current` 原子选择；证书、备份和日志分别位于 `certificates/`、`backups/` 和
`logs/`。

## 可信构建机命令

所有命令从仓库根目录执行。首次准备主机与云门禁：

```bash
SOURCE_STATE_ROOT=/Users/modest/IdeaProjects/happy-agent-platform \
  deploy/production/deploy.sh bootstrap
```

首次停写迁移（唯一允许携带数据库、媒体和原 master key 的入口）：

```bash
SOURCE_STATE_ROOT=/Users/modest/IdeaProjects/happy-agent-platform \
  deploy/production/deploy.sh migrate
```

后续普通发布、状态、备份与显式应用回滚：

```bash
deploy/production/deploy.sh release
deploy/production/deploy.sh status
deploy/production/deploy.sh backup
deploy/production/deploy.sh rollback 20260813T120000Z-abcdef0
```

`rollback` 只切换应用 release，不恢复数据库或媒体。首次 restore 必须面对 roles-only
空目标；目标包含任何额外角色、schema、对象或 ACL 时立即停止。普通 `release` 禁止包含
数据库 dump、媒体、master key 或其他 Secret。

## 证书续期

当前 IP 证书由固定 Certbot 5.7.0 digest、HTTP-01 webroot 和 short-lived profile
维护。主机上检查定时器和手动触发一次续期：

```bash
ssh root@39.101.65.254 \
  'systemctl status happy-agent-cert-renew.timer --no-pager'
ssh root@39.101.65.254 \
  'systemctl start happy-agent-cert-renew.service'
ssh root@39.101.65.254 \
  'journalctl -u happy-agent-cert-renew.service --no-pager -n 100'
```

实际运维 SSH 必须使用已固定的 identity、`BatchMode=yes`、
`IdentitiesOnly=yes`、`StrictHostKeyChecking=yes` 和项目专用 known_hosts；以上命令只展示
远端动作，日常优先使用 `deploy.sh` 封装入口。

## 域名到位后的替换点

`modest.vip` 尚在审批，本轮仍保持 IP HTTPS，不创建 DNS 记录、不申请域名证书。审批完成后
计划使用：

- `fitness.modest.vip`：健身端；
- `agent.modest.vip`：管理端。

届时需在单独变更中更新 DNS、安全固定来源、Nginx `server_name`/路由、公开 origin、证书
申请与 SAN 校验、Cookie/跨域策略及公网验收；不得在当前 IP 证书脚本中直接猜测替换。

## 恢复目标与运行限制

- RPO 取决于最近一次成功完整备份；每次 release 前自动备份，重要变更前应额外执行
  `deploy.sh backup` 并拉回 recovery package。
- RTO 取决于镜像加载、数据库 generation 切换及数据量；本方案不是热备或自动灾备，恢复需
  人工确认备份与 manifest。
- 当前 ECS 为单盘、单 PostgreSQL、单 App/Nginx 实例，存在单盘和单机故障风险。
- 首次迁移需要停写，release/rollback 也有短暂停机；当前没有蓝绿流量切换或零停机保证。
- 不得恢复到非空目标，不得用普通 release 搬运状态或 Secret，不得在日志或命令行输出
  credential/master key 明文。
