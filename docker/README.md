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

默认由 Nginx 暴露 HTTPS，`j2agent` 应用端口仅在 Compose 网络内使用。首次启动前生成本地自签证书：

```bash
./gen-self-signed-cert.sh
```

证书默认生成到 `${J2AGENT_VOLUMES_PATH}/volumes/nginx/certs/j2agent.crt` 与 `${J2AGENT_VOLUMES_PATH}/volumes/nginx/certs/j2agent.key`，由 Nginx 容器只读加载，格式兼容 nginx 常用 PEM 证书。也可以传入域名/IP 生成 SAN：

```bash
./gen-self-signed-cert.sh localhost 127.0.0.1 j2agent.example.com
```

然后编辑 `.env`：

```properties
J2AGENT_NGINX_PORT=30112
```

再按默认命令启动：

```bash
docker compose up -d --build
```

访问 `https://localhost:${J2AGENT_NGINX_PORT}`。Nginx 从 `${J2AGENT_VOLUMES_PATH}/volumes/nginx/certs` 只读加载证书和私钥，并反向代理到容器内的 `j2agent:${J2AGENT_PORT}`。自签证书会触发浏览器安全提示，生产环境请替换为可信 CA 签发的证书。

Nginx 每次启动前都会将 `docker/nginx` 中的默认模板与启动脚本覆盖复制到 `${J2AGENT_VOLUMES_PATH}/volumes/nginx/{templates,http-templates,docker-entrypoint.d}`，然后从该卷读取配置。服务器上的 Nginx 配置修改会在下次启动时被部署包默认配置覆盖。

证书续期或替换完成后，无需重启应用，执行：

```bash
./reload-nginx-cert.sh
```

脚本会先校验 Nginx 配置，再优雅 reload；已有连接不受中断，新建 TLS 连接将使用新证书。

HTTP 端口始终由 Nginx 监听；通过 `.env` 的开关决定直接 HTTP 访问或重定向到 HTTPS：

```properties
J2AGENT_HTTP_REDIRECT_PORT=30110
J2AGENT_ENFORCE_HTTPS=true
```

执行 `docker compose up -d` 后，HTTP 请求将返回 308 到 HTTPS。默认值 `J2AGENT_ENFORCE_HTTPS=false`，HTTP 请求直接反代到应用。

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
