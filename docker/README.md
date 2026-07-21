# docker/

本目录为 **Docker Compose 可执行配置**（`docker-compose.yml`、`.env`、`j2agent/Dockerfile`、`package_offline.sh`）。

完整部署说明、数据卷说明、前端更新 SOP 见：

**[j2agent-docs/基础设施/docker部署/README.md](../../j2agent-docs/基础设施/docker部署/README.md)**

首次使用：

```bash
cp .env.example .env
# 编辑 .env 后
docker compose up -d --build
```

默认启动只暴露 HTTP。如需启用 HTTPS，先生成本地自签证书：

```bash
./gen-self-signed-cert.sh
```

证书默认生成到 `${J2AGENT_VOLUMES_PATH}/volumes/j2agent/certs/j2agent.crt` 与 `${J2AGENT_VOLUMES_PATH}/volumes/j2agent/certs/j2agent.key`，容器内读取 `/opt/j2agent/volume/certs`，格式兼容 nginx 常用 PEM 证书。也可以传入域名/IP 生成 SAN：

```bash
./gen-self-signed-cert.sh localhost 127.0.0.1 j2agent.example.com
```

然后编辑 `.env`：

```properties
J2AGENT_HTTPS_ENABLED=true
```

如果需要保留 HTTP 入口并自动跳转到 HTTPS，可额外设置 HTTP 重定向端口：

```properties
J2AGENT_HTTP_REDIRECT_PORT=30110
```

再按默认命令启动：

```bash
docker compose up -d --build
```

HTTPS 模式不改变端口，只把 `J2AGENT_PORT` 上的访问协议从 HTTP 切换为 HTTPS；应用容器会直接启用 Spring Boot HTTPS，不引入反向代理。配置 `J2AGENT_HTTP_REDIRECT_PORT` 后，访问 `http://localhost:30110/...` 会重定向到 `https://localhost:30111/...`。自签证书会触发浏览器安全提示，生产环境请替换 `${J2AGENT_VOLUMES_PATH}/volumes/j2agent/certs` 下的证书和私钥。

构建 `j2agent` 镜像时，Dockerfile 会**优先**使用 `j2agent/*.tar.gz`（与本目录 `Dockerfile` 同级），否则回退到 `j2agent-starter/target/*.tar.gz`。离线部署建议将 Maven 产出的 `j2agent-*.tar.gz` 放到 `j2agent/` 再构建，详见 [构建与启动 §1.1](../../j2agent-docs/基础设施/docker部署/构建与启动.md#11-镜像构建时如何找到-targz)。

RustFS 容器内默认监听 `9000/9001`。本项目宿主机默认端口在 RustFS 默认端口前面加 `1`，即：

```properties
RUSTFS_API_PORT=19000
RUSTFS_CONSOLE_PORT=19001
```

如果启动 RustFS 报错端口已占用：

1) 通常是你同时启动过其他栈也在占用对应端口
2) 修改 `docker/.env` 里的端口，换成不冲突的值，例如：
   - `RUSTFS_API_PORT=19000`
   - `RUSTFS_CONSOLE_PORT=19001`
3) 仅重启 RustFS：`docker compose up -d rustfs`

默认部署已从 MinIO 切换到 RustFS，不迁移旧 MinIO 数据；确认不再需要后，可自行清理旧的 `${J2AGENT_VOLUMES_PATH}/volumes/minio` 目录。
