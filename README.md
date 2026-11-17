# 高效Todo 服务端

独立的 Java 17 服务提供账户、增量同步、团队、消息和评论 API，使用 SQLite 保存数据。可直接运行 Spring Boot 应用，也可用 Docker 部署。

## 启动

开发运行需要 JDK 17 和 Maven 3.9。

```sh
mvn spring-boot:run
```

`GET /health` 返回 `{"status":"ok"}`。默认端口为 8765。数据库路径使用 `GXTODO_DATABASE` 环境变量指定。SQLite 数据备份应使用 backup API，以包含正在运行实例的事务状态。

## Docker 部署

```sh
mvn -B -ntp -DskipTests package
docker build -t gxtodo-server:1.0.0 .
docker volume create gxtodo-data
docker run -d --name gxtodo-server --restart unless-stopped --stop-timeout 30 \
  -p 0.0.0.0:18765:8765 -v gxtodo-data:/data \
  -e GXTODO_DATABASE=/data/gxtodo.sqlite3 \
  -e GXTODO_PORT=8765 -e GXTODO_BIND=0.0.0.0 \
  gxtodo-server:1.0.0
```

在本机执行 `docker build` 前，先用 `mvn -B -ntp -DskipTests package` 构建 JAR。对外供 iOS 和 Android App 使用时，应将反向代理配置为 HTTPS 域名，并转发到容器服务端口。容器默认以非 root 用户运行。数据库存放在 Docker 卷 `gxtodo-data`，更新镜像时保留该卷。旧版 Docker 没有 `docker compose` 命令，可以直接用上面的 `docker run`。

## 发布到 DB2

从 Windows PowerShell 的仓库根目录运行。脚本按当前 Git 提交生成版本标识，上传 JAR 和 Dockerfile，在 DB2 构建镜像，创建持久化数据卷并启动容器。服务监听 DB2 的 `18765` 端口。

```powershell
mvn -B -ntp -DskipTests package
.\deploy-db2.ps1
```

部署后可通过 `ssh db2 docker ps` 查看容器，用 `ssh db2 docker logs --tail 100 gxtudo-server` 查看日志。健康检查地址为 `http://10.10.10.38:18765/health`。此地址供家庭网络访问。移动端公开访问需要配置域名及 HTTPS 反向代理。

## 邮件找回密码

设置 `GXTODO_PUBLIC_ORIGIN`（HTTPS 地址）、`GXTODO_SMTP_HOST`、`GXTODO_SMTP_PORT`、`GXTODO_SMTP_FROM`。需要 SMTP 认证时再配置 `GXTODO_SMTP_USER` 和 `GXTODO_SMTP_PASSWORD`。默认 465 使用 TLS，其他端口使用 STARTTLS。重置链接有效 20 分钟，使用后已登录会话失效。

## 接口与源码

| 模块 | 作用 |
| --- | --- |
| `HttpController` · `ApiService` | HTTP 路由、v7 接口和请求校验 |
| `Accounts` | 注册、登录、会话、资料和密码 |
| `SyncService` | 增量游标、重复投递确认和删除记录 |
| `Collaboration` | 团队权限、申请审批、消息和评论 |
| `Database` | SQLite 连接和原子事务 |
| `Protocol` | 表字段、参数校验和 JSON 序列化 |

API 前缀为 `/api/v7`，接受 JSON 或表单，支持以 `_method` 表示逻辑 GET 或 PUT。成功响应中的 `status` 为字符串 `"0"`。反向代理访问日志应仅记录路径，因为旧客户端的部分 GET 查询参数包含会话字段。

## 测试

```sh
mvn verify
```

测试使用临时数据库，覆盖并发重试、账号隔离、跨会话删除同步、团队审批、成员权限、私人便签过滤、评论及密码重置。

Flutter App 独立维护于 [gxtoudo-app](https://github.com/hysgit/gxtoudo-app)。App 的 `GXTODO_SERVER` 编译参数或账户设置用于指定本服务地址。
