# 高效Todo Java 服务端

Java 17 / Spring Boot API 使用 PostgreSQL 保存账户、任务、便签文字、同步记录及附件索引，MinIO 私有桶保存图片和录音。App 单独维护于 [gxtoudo-app](https://github.com/hysgit/gxtoudo-app)。

## 本地或 Docker 启动

需要 JDK 17+、Maven 3.9+、Go 1.24+ 和 Docker。MinIO 官方已改为源码发行，本项目从官方固定修订构建，不依赖第三方重打包镜像。

```powershell
mvn -B -ntp -DskipTests package
./deploy/build-minio.ps1
Copy-Item .env.example .env
```

在 `.env` 为 PostgreSQL 和 MinIO 设置不同的随机密码，再运行：

```sh
docker compose up -d --build
curl http://127.0.0.1:18765/health
```

PostgreSQL 和 MinIO 没有对外发布端口；API 通过配置的 HTTP 地址访问。配置过 MinIO 后，`/health` 同时检查数据库和对象存储。旧 Docker 18.09 可使用下面的 DB2 发布脚本。

## 发布到 DB2 和迁移现有数据

```powershell
mvn -B -ntp -DskipTests package
./deploy/build-minio.ps1
./deploy-db2.ps1
```

脚本在 DB2 使用独立网络 `gxtudo-storage`、PostgreSQL 16 镜像和源码构建的 MinIO。密码首次随机生成到 `/home/hy/deploy/gxtudo-server/storage.env`，权限为 600，后续发布复用。数据库卷为 `gxtudo-postgres-data`，文件卷为 `gxtudo-minio-data`。

首次迁移旧 SQLite 实例时，脚本先启动新 API 检查数据库和 MinIO，再停止旧写入，使用 SQLite backup API 创建一致性快照；备份路径为 `/home/hy/deploy/gxtudo-server/backups/<版本>/gxtodo.sqlite3`。迁移器逐表核对行数和内容 SHA-256，并恢复数据库自增序列。账户、密码哈希、会话令牌哈希、任务 ID、同步游标和删除记录均保留。目标表必须为空，否则拒绝覆盖。

旧容器保留为 `gxtudo-server-rollback-<版本>`，旧卷 `gxtudo-server-data` 保留。发布中途失败会重新启动旧 API，备份和 PostgreSQL 数据保留。迁移成功后再回滚 SQLite 会丢失切换后新增的数据，需先停止新 API 并导出增量，不能直接把旧库当作最新数据。

可手动向**空的目标库**迁移：先配置 `GXTODO_DATABASE_URL`、`GXTODO_DATABASE_USER`、`GXTODO_DATABASE_PASSWORD`、`GXTODO_MIGRATE_SQLITE`（SQLite 备份绝对路径），再执行：

```sh
java -jar target/gxtodo-server.jar --spring.main.web-application-type=none
```

成功后程序退出。启动正常 API 前清除 `GXTODO_MIGRATE_SQLITE`。

## 配置

| 环境变量 | 用途 |
| --- | --- |
| `GXTODO_DATABASE_URL` | PostgreSQL JDBC URL |
| `GXTODO_DATABASE_USER` / `GXTODO_DATABASE_PASSWORD` | 数据库凭据 |
| `GXTODO_MINIO_ENDPOINT` | MinIO 内网 S3 地址 |
| `GXTODO_MINIO_ACCESS_KEY` / `GXTODO_MINIO_SECRET_KEY` | 私有桶凭据 |
| `GXTODO_MINIO_BUCKET` | 桶名，默认 `gxtodo-media` |
| `GXTODO_PORT` / `GXTODO_BIND` | API 监听地址，默认 `0.0.0.0:8765` |
| `GXTODO_PUBLIC_ORIGIN`、`GXTODO_SMTP_*` | 原有邮件找回密码配置 |

写事务以 PostgreSQL 事务级 advisory lock 排序，保留原 SQLite 写入顺序，避免同步游标跨过尚未提交的修订号。读取使用独立连接；连接池上限 12，锁等待 15 秒、单条 SQL 30 秒。MinIO 上传和下载不持有数据库事务。

## 图片与语音接口

所有接口使用已有的 `user_id`、`login_id`、`token` 会话鉴权，不接受匿名对象地址。

- `POST /api/v7/media/upload`：multipart 字段 `team_id`、`note_id`、`kind=image|audio`、`file` 和会话字段。可选字段 `client_id` 和 `sha256` 用于本地队列断线重试，重复上传返回同一对象；去重记录保存在独立的 `media_clients` 表。返回 `data` 中的 `id/name/kind/content_type/size/sha256`。校验文件签名，单文件 100 MB；保存原始文件流，不重新编码。
- `POST /api/v7/media/read`：JSON 中提交附件 `id` 和会话字段。返回原始文件流，仅附件所有者、且仍有空间权限时可读取；便签删除后拒绝读取。
- 便签表 49 使用 `title` 保存独立标题，`conversation_version=1` 表示新版会话。表 50 每条记录是一条独立的文字／图片／语音消息，字段包括 `note_id`、`kind`、`content`、`entry_at`、`media_id`、`client_media_id`、`content_type`、`size`、`sha256`、`duration_ms`。表 49 与 50 都按账号隔离；媒体消息验证附件归属，且独立同步，避免同时新增内容时整篇覆盖。旧表 49 的 `attachments` 字符串仍兼容，保留旧版 30 个附件限制。

支持 JPEG、PNG、GIF、WebP、BMP、TIFF、HEIC、AVIF 图片和 M4A、MP3、WAV、OGG 音频。预览能力由手机系统决定，文件本身按原始字节保存。照片的原始元数据随原图保留。录音默认使用 AAC/M4A。

代理需设置 `client_max_body_size 101m`，上传读取超时至少 180 秒。访问日志仅记录 URI 路径，避免旧客户端查询参数中的会话字段进入日志。

## 备份

同时备份 PostgreSQL、MinIO 数据卷和 `storage.env`。只备份数据库不能恢复图片和录音。发布脚本在每次 PostgreSQL 更新前生成 `pg_dump -Fc`；MinIO 卷需单独快照或冷备份。移除便签或附件引用后，对象仍保留在私有存储中，不自动清理，以便备份恢复；删除便签后接口拒绝读取。

## 测试

集成测试仅连接专用测试实例，默认 PostgreSQL 为 `127.0.0.1:15439/gxtodo_test`，MinIO 为 `127.0.0.1:19019`，测试账号 `gxtodo_test` / `gxtodo_test_local_only`。不得把该账号或端口公开到公网。

```sh
docker compose -f compose.test.yaml up -d --build --wait
mvn test
```

覆盖并发同步重试、账号隔离、删除墓碑、团队审批、私人便签过滤、评论、密码重置、原图/录音字节一致性、附件归属和旧客户端兼容。测试会在测试数据库中创建账号。

MinIO 源码：[RELEASE.2025-10-15T17-29-55Z](https://github.com/minio/minio/tree/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a)，原项目许可证为 AGPLv3；构建脚本保留版本和源修订标识。

App 7.4 的“保存”仅写入本机，后台队列稍后调用这些接口。服务端响应只包含逻辑文件标识，不返回手机本地路径。原图和录音在本机与 MinIO 都保留副本。

## 记账同步（App 7.5+）

表 51 保存账单：`name/date/type/amount/channel/party/remark/location/latitude/longitude`。类型为 `expense/income/transfer`，金额是十进制字符串，最多 30 位整数、18 位小数，以 BigDecimal 校验并原样保存精度。日期是用户本地日历的 YYYY-MM-DD，不做时区换算。

表 52 保存账单附件，结构与便签媒体消息相同，`note_id` 指向账单 UID；表 53 保存动态途径/账户选项，`kind=channel|party`。三张表均按账号隔离，团队其他成员不可读取。客户端首次支持记账时会重拉一次历史同步游标，补回旧客户端跳过的数据。

上传账单附件时传 `parent_table=51`，便签默认 49。新表 `media_parents` 记录附件所属类型，校验、重试、下载和删除检查均使用该类型；同 UID 的便签和账单不能互相盗用附件。普通文件使用 `kind=file`，按二进制原文存储，下载强制 `application/octet-stream` 和 Content-Disposition attachment，不以内联网页形式返回。

月汇总由客户端做精确十进制累计；内转不计入收入和支出。GPS 由手机在用户授权后获取，服务端只保存该账单提交的坐标与地点，不做后台跟踪。

## 任务远程推送

服务端每 10 秒检查已同步的任务提醒，过滤已完成、已删除和仅闹钟的任务。已到点的提醒最多在 5 分钟内补发，接收设备必须已登录并注册通知。

- iOS 使用 APNs 推送密钥。配置 `GXTODO_APNS_KEY_FILE`、`GXTODO_APNS_KEY_ID` 和 `GXTODO_APNS_TEAM_ID`。Apple Developer 的 App ID 需启用 Push Notifications。
- Android 使用 Firebase Cloud Messaging HTTP v1。配置 `GXTODO_FCM_ACCOUNT_FILE` 为允许发送 FCM 消息的服务账号 JSON，项目须与客户端一致。
- db2 将 `/home/hy/deploy/gxtudo-server/push-secrets` 只读挂载到容器的 `/run/gxtodo-push`。文件仅允许运行服务的用户读取，在 `storage.env` 填写容器内路径。部署预检会禁用发送任务。
