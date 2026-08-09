# 阿里云一键部署

`deploy/aliyun-deploy.sh` 在干净的 Anolis OS / CentOS / Ubuntu 服务器上以 root 身份一次性完成：

1. 安装 Bash / curl / wget / Java 17 / nginx / PostgreSQL 16 客户端（包含 libpq-dev 用于 Backend）
2. 创建 `pgvector` 扩展（优先用 `postgresql16-pgvector` 包，没有则源码编译）
3. 初始化 PostgreSQL 数据目录、`happy_agent` 库、`fitness` / `agent` schema、`fitness_app` / `agent_app` 角色
4. 生成 `deploy/secrets/` 下的所有密码与 master key（`openssl rand -hex 24`）
5. `./mvnw -DskipTests -pl starter -am package` 构建后端
6. 写 `happy-agent.service`（systemd）并启动 backend，journalctl 报错时立刻失败
7. `npm ci && npm run build` 构建前端静态资源
8. 写 nginx 配置（`/etc/nginx/conf.d/happy-agent.conf`），SPA fallback + `/api` 反向代理
9. 烟雾测试，确认 admin 与 app 端点可达

## 使用

```bash
# 以 root 登录阿里云 ECS
sudo -i

# 克隆代码到 /opt/happy-agent
git clone <repo> /opt/happy-agent
cd /opt/happy-agent

# 第一次部署
bash deploy/aliyun-deploy.sh

# 后续 deploy 升级（idempotent）
bash deploy/aliyun-deploy.sh
```

## 环境变量（可覆盖）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PROJECT_DIR` | `/opt/happy-agent` | 项目根目录 |
| `APP_USER` | `happy` | 运行 Spring Boot 的系统用户 |
| `APP_PORT` | `8080` | 后端端口 |
| `PUBLIC_HOST` | `0.0.0.0` | 暴露的公网/内网地址 |
| `POSTGRES_VERSION` | `16` | PostgreSQL 大版本 |
| `JAVA_VERSION` | `17` | OpenJDK 大版本 |
| `NGINX_PORT` | `80` | nginx 监听端口 |
| `PG_SHARED_BUFFERS` | `256MB` | shared_buffers 调优值 |
| `PG_WORK_MEM` | `16MB` | work_mem 调优值 |

## 部署后

- 前端：`http://<your-host>/`（admin 在 `/admin`）
- 后端：`http://127.0.0.1:8080/api/...`
- 数据库：`127.0.0.1:5432/happy_agent`（已启用 `vector` + `pg_trgm`）

```bash
# 查看服务
systemctl status happy-agent
journalctl -u happy-agent -f

# 重启
systemctl restart happy-agent

# 滚动 master key
mv /opt/happy-agent/deploy/secrets/agent-master-key{,.old}
openssl rand -hex 32 > /opt/happy-agent/deploy/secrets/agent-master-key
systemctl restart happy-agent
```

## 关于阿里云 ECS

- 该脚本**只适用 Linux**，**不**在 macOS 上运行。
- 阿里云 ECS 默认禁用外网 8080 / 5432 入站（安全组），只在 Nginx 80 端口对外。
- 想加 HTTPS：在 nginx 站点里加 `listen 443 ssl;` + `certbot --nginx` 即可。
- RAG 知识库默认使用配置的 LLM Provider 的 Embedding 接口（`text-embedding-v3`），运行时自动调 `POST /v1/embeddings`，**不**依赖额外组件。
- 流式 LLM 走 SSE，前端 `EventSource` 接收，所以 nginx 配置里 `proxy_buffering off;`。
