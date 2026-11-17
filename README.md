# 高效Todo 服务端

独立的 Python 服务提供账户、增量同步、团队、消息和评论 API，使用 SQLite 保存数据。Python 3.10+ 即可运行，依赖 Python 标准库。

## 启动

在仓库根目录执行。

```sh
python -m server --bind 127.0.0.1 --port 8765 --database data/gxtodo.sqlite3
```

`GET /health` 返回 `{"status":"ok"}`。外部设备通过 HTTPS 反向代理访问服务。数据库路径可用 `--database` 指定。SQLite 数据备份应使用 backup API，以包含正在运行实例的事务状态。

## 邮件找回密码

设置 `GXTODO_PUBLIC_ORIGIN`（HTTPS 地址）、`GXTODO_SMTP_HOST`、`GXTODO_SMTP_PORT`、`GXTODO_SMTP_FROM`。需要 SMTP 认证时再配置 `GXTODO_SMTP_USER` 和 `GXTODO_SMTP_PASSWORD`。默认 465 使用 TLS，其他端口使用 STARTTLS。重置链接有效 20 分钟，使用后已登录会话失效。

## 接口与源码

| 模块 | 作用 |
| --- | --- |
| `server/__main__.py` | HTTP 路由、v7 接口及重置密码页面 |
| `server/accounts.py` | 注册、登录、会话、资料与密码 |
| `server/sync.py` | 增量游标、重复投递确认和删除记录 |
| `server/collaboration.py` | 团队权限、申请审批、消息和评论 |
| `server/storage.py` | SQLite 表及事务 |
| `server/protocol.py` | 同步表字段和请求校验 |

API 前缀为 `/api/v7`，接受 JSON 或表单，支持以 `_method` 表示逻辑 GET 或 PUT。成功响应中的 `status` 为字符串 `"0"`。反向代理访问日志应仅记录路径，因为旧客户端的部分 GET 查询参数包含会话字段。

## 测试

```sh
python -m unittest server.test_api -v
```

测试使用临时数据库，覆盖并发重试、账号隔离、跨会话删除同步、团队审批、成员权限、私人便签过滤、评论及密码重置。

Flutter App 独立维护于 [gxtoudo-app](https://github.com/hysgit/gxtoudo-app)。App 的 `GXTODO_SERVER` 编译参数或账户设置用于指定本服务地址。
