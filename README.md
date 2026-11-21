# GMSR BOSS 组队排班系统

冒险岛（GMSR）固定队排班与席位编排工具。核心是「一趟车上谁能上、上什么角色、还差几个人」，
以及融合车（多个 BOSS 必须凑同一批人打）的一致性约束。

- **后端**：Spring Boot 3.5.16 / Java 25 / PostgreSQL / Flyway
- **前端**：React 18 + TypeScript + Vite（构建产物是纯静态文件）
- **鉴权**：SA-Token + Redis（服务端会话，可随时吊销）
- **语音**（可选）：LiveKit

## 使用指南

| 你想做什么 | 从哪里开始 |
| --- | --- |
| 查看排班 | 打开「车次一览表」。未登录也能看车次、BOSS 和重置时间。切回页面时会自动更新，编辑或拖拽期间会等操作结束再同步。 |
| 登记角色、找自己的车次 | 登录后在「我的信息」登记角色，在「我的车次」查看个人行程。首次登录或管理员重置密码后需先改密。 |
| 管理 BOSS 和席位 | 管理员可在「系统设置」开通排班权限。有排班权限的用户可创建车次、编排阵容、跨趟调车和换位。复制车次沿用难度、时间和备注，席位从空阵容开始。 |
| 查找或编写攻略 | 登录后打开「活动攻略与TIPS」，可搜索、置顶、编写图文攻略。编辑自动保存为私有草稿，删除的攻略和草稿可在 7 天内从回收站恢复。 |
| 加入语音 | 部署者启用 LiveKit 后，登录并打开「Boss语音频道」，选择频道并允许浏览器使用麦克风。 |

### 活动攻略与 TIPS

- 在一个编辑区写文字、粘贴图片或在光标位置插图，也可把文件放在文章底部。每篇最多 12 个附件，单个文件最多 20 MB。支持 PNG、JPEG、GIF、WebP、PDF、TXT 和 ZIP。图片可先看预览，再按需加载原图，两者均需登录。
- 窗口关闭或页面刷新后，可从「我的草稿」继续编辑。编辑中的改动和已上传附件会随草稿保存。多人编辑同一篇攻略时，保存会检查版本，避免覆盖其他人的更新。
- 站点的登录用户可以发布、编辑、置顶、删除和恢复共享攻略。草稿和草稿中新上传的附件仅本人可访问，发布后其他登录用户可查看。

---

## 一、快速部署（单机）

以下示例面向使用 systemd 的 Debian/Ubuntu，命令以 `root` 执行。Docker 运行数据库和 Redis，systemd 运行后端，Nginx 或 OpenResty 提供同源 HTTPS。

### 0. 需要先准备的东西

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **25** | `pom.xml` 里 `java.version=25`，低版本编译不过 |
| Maven | 3.9+ | 仅构建时需要，运行只用 jar |
| PostgreSQL | **16** | 示例和 CI 使用 16，已有实例填写实际连接参数 |
| Redis | 6+ | SA-Token 会话存储，**必须有** |
| Node.js | 22+ | 构建前端和运行验证脚本，CI 使用 22 |
| Nginx 或 OpenResty | — | 提前准备站点域名及浏览器信任的 TLS 证书 |

若新装时选用 PostgreSQL 18 官方镜像，数据卷应挂载到 `/var/lib/postgresql`。现有数据库的大版本升级需按 PostgreSQL 的迁移流程执行。[PostgreSQL 官方镜像说明](https://hub.docker.com/_/postgres#pgdata)

### 1. 起数据库与 Redis

```bash
docker run -d --name gmsr-pg \
  --restart unless-stopped \
  -e POSTGRES_PASSWORD='换成你的强密码' \
  -p 127.0.0.1:5432:5432 \
  -v /opt/gmsr/pgdata:/var/lib/postgresql/data \
  postgres:16-alpine

docker run -d --name gmsr-redis \
  --restart unless-stopped \
  -p 127.0.0.1:6379:6379 \
  -v /opt/gmsr/redisdata:/data \
  redis:7-alpine redis-server --appendonly yes --requirepass '换成你的Redis密码'

docker exec gmsr-pg pg_isready -h 127.0.0.1 -U postgres
```

### 2. 建库建账号

```bash
docker exec -i gmsr-pg psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
CREATE ROLE gmsr_app LOGIN PASSWORD '换成你的库密码';
CREATE DATABASE gmsr OWNER gmsr_app ENCODING 'UTF8' TEMPLATE template0;
SQL

docker exec -i gmsr-pg psql -v ON_ERROR_STOP=1 -U postgres -d gmsr <<'SQL'
CREATE SCHEMA IF NOT EXISTS gmsr AUTHORIZATION gmsr_app;
ALTER ROLE gmsr_app SET search_path = gmsr, public;
SQL
```

> `search_path` 这一句**不能省**。应用依赖它定位 `gmsr` schema 下的表，
> 否则触发器里的函数会找不到表（表现为 `relation "boss_run" does not exist`）。

### 3. 构建后端

```bash
git clone https://github.com/RakkoHoshimiya/ms-boss-scheduler.git
cd ms-boss-scheduler
java -version
mvn -B -f backend/pom.xml package -DskipTests
# 产物位于 backend/target/gmsr-scheduler.jar
```

### 4. 写配置

```bash
useradd --system --home /opt/gmsr --shell /usr/sbin/nologin gmsr
install -d -m 0755 /opt/gmsr/app
install -d -o gmsr -g gmsr -m 0750 /opt/gmsr/logs /opt/gmsr/data
install -d -o gmsr -g gmsr -m 0700 /opt/gmsr/data/note-attachments
install -m 0644 backend/target/gmsr-scheduler.jar /opt/gmsr/app/
install -o gmsr -g gmsr -m 0600 deploy/gmsr.env.example /opt/gmsr/gmsr.env
```

编辑 `/opt/gmsr/gmsr.env`，按以下示例填入实际连接参数和密码。管理员、群友初始密码分别设置，语音可保持关闭。

```ini
GMSR_DB_URL=jdbc:postgresql://127.0.0.1:5432/gmsr?currentSchema=gmsr
GMSR_DB_USER=gmsr_app
GMSR_DB_PASSWORD=你的库密码

GMSR_REDIS_HOST=127.0.0.1
GMSR_REDIS_PORT=6379
GMSR_REDIS_PASSWORD=你的Redis密码
GMSR_REDIS_DB=1

GMSR_ADMIN_USER=admin
GMSR_ADMIN_PASSWORD=你的管理员密码
GMSR_PLAYER_PASSWORD=群友初始密码
GMSR_NOTES_DIRECTORY=/opt/gmsr/data/note-attachments
GMSR_BOSS_ICON_DIR=/var/www/gmsr/boss-icons
GMSR_LOG_DIR=/opt/gmsr/logs

# 可选：语音
GMSR_VOICE_ENABLED=false
```

```bash
chmod 600 /opt/gmsr/gmsr.env
chown gmsr:gmsr /opt/gmsr/gmsr.env
```

### 5. 构建前端并启动后端

```bash
(cd frontend && npm ci --no-audit --no-fund && npm run build)
install -d -m 0755 /var/www/gmsr
cp -r frontend/dist/. /var/www/gmsr/

install -m 0644 deploy/gmsr-backend.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now gmsr-backend
curl -fsS http://127.0.0.1:18080/api/health
```

服务单元默认从 `/usr/bin/java` 启动，请先确认该路径指向 JDK 25。后端监听 `127.0.0.1:18080`。首次启动会执行 Flyway 迁移，等健康接口返回 `"status":"UP"` 后再检查前端。

### 6. 配置 HTTPS 站点

将以下配置加入 Nginx，并替换域名和证书路径。前端和 `/api/` 使用同一个站点，附件上传需要允许至少 21 MB 的请求。

```nginx
server {
    listen 443 ssl;
    server_name your.domain.com;

    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;

    root /var/www/gmsr;
    index index.html;
    client_max_body_size 21m;

    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    # 前端是 SPA，未知路径一律回落到 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反代
    location /api/ {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
nginx -t && nginx -s reload
curl -fsS https://your.domain.com/api/health
```

使用 1Panel/OpenResty 时可参考 [站点模板](deploy/gmsr.conf) 和 [安全响应头](deploy/security-headers.inc)。将静态根目录、证书、监听端口和 include 路径改成你的配置。代理在容器内时，确认容器能访问后端和 LiveKit 上游，例如在 Linux 上使用 host 网络。若改用子目录部署，浏览器仍从同源的 `/api/` 请求后端。

### 7. 首次登录

用 `GMSR_ADMIN_USER` / `GMSR_ADMIN_PASSWORD` 登录。
首次登录后先改密，再到「系统设置」创建或绑定群友账号并按需授予排班权限。打开「车次一览表」确认能读到数据。

### 8. 启用语音（可选）

部署 LiveKit，设置 `GMSR_VOICE_ENABLED=true`，填写 LiveKit 的 `GMSR_VOICE_API_KEY`、`GMSR_VOICE_API_SECRET` 和浏览器能访问的 `GMSR_VOICE_WS_URL`，然后重启后端。使用仓库的 [LiveKit 代理模板](deploy/livekit-rtc.inc) 时，WSS 地址填 `wss://your.domain.com/lk`（非默认 HTTPS 端口需写端口）。防火墙和云安全组按你的 LiveKit 配置放行 ICE 和 TURN 所需端口。使用 HTTPS 登录后，在「Boss语音频道」验证加入、麦克风和声音播放。[LiveKit 端口说明](https://docs.livekit.io/transport/self-hosting/ports-firewall/)

---

## 二、数据库迁移

**不要手写建表语句。** 表结构全部由 Flyway 管理，启动时自动执行 `backend/src/main/resources/db/migration/` 下的 `V*.sql`。

- 空库：首次启动自动建全套结构
- 已有数据的库：迁移会就地执行，无需手动干预
- 新增结构变更使用下一个版本号，新文件名形如 `V12__xxx.sql`。保留已发布的迁移文件原样。

> 已发布的迁移文件被改过会被 Flyway 拒绝启动（`checksum mismatch`）。
> 线上库里 `flyway_schema_history` 记着每个文件的校验和，改一个字节就对不上。

迁移里包含几个**数据库级不变量**（触发器），这是系统正确性的关键：

| 约束 | 作用 |
| --- | --- |
| 席位容量 | 车上人数不能超过 BOSS 容量 |
| 同人单角色 | 同一群友在同一趟车上只能占一个席位 |
| 单座唯一 | 同一角色在同一 BOSS 同一难度下只能排一趟 |
| 首次改密 | `must_change_password` 为真时限制可用接口 |
| 融合车一致性 | 同一趟的成员 BOSS 必须整组存在 |

---

## 三、配置项

全部通过环境变量注入，代码里的默认值仅供本地开发。

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `GMSR_DB_URL` | 是 | — | JDBC 连接串。单机示例使用端口 5432 并指定 `currentSchema=gmsr` |
| `GMSR_DB_USER` | 是 | `gmsr_app` | 数据库账号 |
| `GMSR_DB_PASSWORD` | 是 | — | 数据库密码 |
| `GMSR_DB_SCHEMA` | 否 | `gmsr` | schema 名 |
| `GMSR_REDIS_HOST` | 是 | `127.0.0.1` | Redis 地址 |
| `GMSR_REDIS_PORT` | 否 | `6379` | |
| `GMSR_REDIS_PASSWORD` | 是 | 空 | **Redis 不可用会导致全员掉线** |
| `GMSR_REDIS_DB` | 否 | `1` | 与其它业务隔离 |
| `GMSR_ADMIN_USER` | 否 | `admin` | 初始管理员账号 |
| `GMSR_ADMIN_PASSWORD` | 是 | — | 初始管理员密码 |
| `GMSR_PLAYER_PASSWORD` | 是 | — | 群友初始及重置密码 |
| `GMSR_CORS` | 否 | — | 仅本地 vite 调试需要 |
| `GMSR_LOGIN_MAX_ATTEMPTS` | 否 | `8` | 同账号失败锁定阈值 |
| `GMSR_LOGIN_MAX_ATTEMPTS_PER_IP` | 否 | `30` | 同来源 IP 失败锁定阈值 |
| `GMSR_LOGIN_LOCK_MINUTES` | 否 | `15` | 锁定时长 |
| `GMSR_BOSS_ICON_DIR` | 否 | — | 仅启动自检用，指向你的静态站点图标目录 |
| `GMSR_NOTES_DIRECTORY` | 否 | `./data/note-attachments` | 攻略附件和图片的持久目录，必须在静态站点目录之外 |
| `GMSR_LOG_DIR` | 否 | `logs` | 日志持久目录，由服务用户写入 |
| `GMSR_VOICE_ENABLED` | 否 | `false` | 语音功能开关 |
| `GMSR_VOICE_API_KEY` | 启用语音时 | — | LiveKit key |
| `GMSR_VOICE_API_SECRET` | 启用语音时 | — | LiveKit secret |
| `GMSR_VOICE_WS_URL` | 启用语音时 | — | 客户端可访问的 WSS 地址，例如复用仓库代理模板时填 `wss://your.domain.com/lk` |

相对路径以服务工作目录为准。使用仓库的 systemd 单元时，附件默认写入 `/opt/gmsr/data/note-attachments`，日志默认写入 `/opt/gmsr/logs`。

---

## 四、权限模型

只有两种角色，**排班权限是独立开关**：

| 角色 | 说明 |
| --- | --- |
| `ADMIN` | 管理员：管理账号、重置密码，同时具备排班权限 |
| `VIEWER` | 群友：能登录、看自己的车次、登记自己的角色，可单独授予排班权限 |

`app_user.can_schedule = true` 的账号拥有排班权（改 BOSS、排车位、删车次）。

**匿名访问范围**（未登录能做什么）：

- ✅ 车次一览表（`GET /api/runs`、`/api/runs/{id}`）
- ✅ BOSS 列表（`GET /api/bosses`、`/api/boss-groups`）
- ✅ 重置时间信息（`GET /api/status-reset`）
- 群友名单、角色列表、个人行程、攻略与附件、语音频道都需要登录

权限规则以**默认拒绝**收尾：没被显式列出的接口一律 403。新增接口必须显式归类。

---

## 五、主要 API

统一前缀 `/api`，登录态用请求头 `gmsr-token`。

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/login` | 登录，返回 access + refresh |
| POST | `/auth/refresh` | 无感续期（refresh 一次性消费） |
| POST | `/auth/logout` | 登出 |
| GET | `/auth/me` | 当前账号信息 |
| POST | `/auth/password` | 修改自己的密码 |
| PUT | `/auth/display-name` | 修改自己的显示名 |

### 排班

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/runs` | 全部车次（匿名可读） |
| POST | `/runs` | 新建车次 |
| PUT | `/runs/{id}` | 修改车次 |
| DELETE | `/runs/{id}` | 删除车次（融合车整组删） |
| POST | `/runs/{id}/copy` | 复制一趟空车次（融合车整组复制） |
| POST | `/runs/{id}/resequence` | 重排趟次（融合车整组重排） |
| POST | `/runs/{id}/members` | 上人 |
| PUT | `/runs/{id}/members/{memberId}` | 改席位 |
| DELETE | `/runs/{id}/members/{memberId}` | 撤人 |
| POST | `/runs/{runId}/members/{memberId}/move` | 跨趟移动 |
| POST | `/runs/{runId}/members/{memberId}/swap` | 两人换位 |
| PUT | `/runs/{id}/status` | 标记完成状态 |

### BOSS 与群友

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/bosses`、`/bosses/{id}` | BOSS 管理 |
| GET/POST/PUT/DELETE | `/boss-groups`、`/boss-groups/{id}` | 分组管理 |
| GET/POST/PUT/DELETE | `/players`、`/players/{id}` | 群友管理 |
| GET/POST/PUT/DELETE | `/characters`、`/characters/{id}` | 角色管理 |
| GET | `/players/{playerId}/runs` | 某人参加的车次 |

### 管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/admin/users` | 账号管理（仅 ADMIN） |
| POST | `/admin/users/{id}/reset-password` | 重置密码 |
| GET | `/health` | 健康检查 |

### 攻略与草稿

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST | `/notes` | 查看共享攻略列表或发布攻略 |
| GET / PUT / DELETE | `/notes/{id}` | 查看、更新或移入回收站 |
| GET | `/notes/drafts` | 查看自己的草稿 |
| GET | `/notes/trash` | 查看共享攻略回收站 |
| POST | `/notes/{id}/restore` | 从回收站恢复攻略 |

---

## 六、融合车（特殊机制）

GMSR 里有几个 BOSS 必须**同一批人连续打**，系统把这组 BOSS 标为同一个 `combo_key`。

规则：

- 一趟融合车 = 成员 BOSS 各有一条同 `combo_key`、同 `tier`、同 `run_index` 的车次
- **复制、重排、删除都以「趟」为单位**，作用于全部成员
- 融合车成员**不允许单独删除**，否则其余成员会变成对不上的孤儿数据
- 前端会把成员合并成一张卡展示，用合成 id；调后端时必须用**主 BOSS 的真实车次 id**

---

## 七、状态自动重置

车次只重置「已完成」标记，原有排班和席位继续保留。以下时间均为 `Asia/Shanghai`。

- 普通 BOSS 每周四 08:00 重置。
- BOSS 表单勾选「按月刷新」后，每月 1 日 08:00 重置，例如「老黑能量车」。

服务若在重置时刻停机，启动后会补跑。可在「车次一览表」或 `GET /api/status-reset` 查看两种周期的重置时间。

---

## 八、备份与恢复

升级或换机前，备份 PostgreSQL、`GMSR_NOTES_DIRECTORY` 和实际部署配置。暂停后端写入后生成同批次数据库和附件备份，恢复时用同一批次配对。配置备份包含密码或密钥，请限制访问权限。

仓库的 `deploy/backup.sh` 和 `deploy/restore.sh` 以 1Panel 目录布局为默认值。使用其他布局前，配置实际的 `PG_CONTAINER`、数据库参数和备份脚本中的站点配置路径。备份脚本生成同一时间戳的数据库和附件归档，保留期默认 14 天。建议先在独立库做一次恢复演练，并检查攻略中的图片和附件。[备份和恢复流程](deploy/RESTORE.md)

---

## 九、本地开发

```bash
# 后端（先设置指向本地 PostgreSQL 和 Redis 的 GMSR_* 环境变量）
cd backend && mvn spring-boot:run

# 另开终端，从仓库根目录启动前端
cd frontend && npm ci && npm run dev
# Vite 默认监听 5173，并把 /api 转发到 127.0.0.1:18080
```

测试：

```bash
cd backend
mvn test                                            # 默认测试
GMSR_INTEGRATION_DB=1 GMSR_INTEGRATION_REDIS=1 mvn verify  # 启用 PostgreSQL 和 Redis 集成测试
```

前端校验脚本：

```bash
cd frontend
npm run build
npm run verify:notes
npm run verify:sync
npm run verify:auth-session
npm run verify:voice-lifecycle
```

---

## 十、故障排查

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `checksum mismatch` | 找回已发布的原迁移文件。新的结构变更放到下一版迁移 |
| 启动报 `relation "xxx" does not exist` | 数据库账号缺 `search_path = gmsr, public` |
| 启动报 `ddl-auto: validate` 失败 | 实体与库结构不一致，检查迁移是否全部执行 |
| 所有人突然变成未登录 | Redis 挂了。检查 `GMSR_REDIS_*` 与 Redis 进程 |
| 前端 404 / 白屏 | Nginx 缺 `try_files ... /index.html`（SPA 回落） |
| 登录后接口全 403 | 账号 `must_change_password` 为真，先改密码 |
| 封面图裂开 | 图标文件缺失，跑 `npm run check-icons` |
| 附件上传返回 413 | 检查代理的请求体限制和单文件 20 MB 上限 |
| 语音无法连接 | 检查 HTTPS、WSS、麦克风权限以及 LiveKit 配置对应的防火墙端口 |

日志（两处都有，优先看文件）：

| 位置 | 内容 | 保留 |
| --- | --- | --- |
| `/opt/gmsr/logs/gmsr.log` | 全量 INFO 及以上 | 14 天 / 最多 500 MB，按天+50 MB 滚动 |
| `/opt/gmsr/logs/gmsr-error.log` | 仅 WARN 及以上，排查故障先看这个 | 30 天 / 最多 200 MB |
| `journalctl -u gmsr-backend -f` | 控制台输出，与上面 gmsr.log 内容一致 | 受 journald 限额约束 |

```bash
tail -f /opt/gmsr/logs/gmsr.log          # 实时
tail -n 200 /opt/gmsr/logs/gmsr-error.log # 只看告警与错误
```

应用写入滚动日志文件，系统日志也会收到控制台输出。账号初始化和数据迁移日志对指定账号字段应用了脱敏。对外分享日志前，检查其中的主机名、账号和连接参数。

---

## 十一、目录结构

```
backend/
  src/main/java/com/gmsr/scheduler/
    domain/      实体（JPA）
    repository/  数据访问
    service/     业务逻辑（ComboGuard 是融合车唯一入口）
    web/         控制器
    security/    SA-Token 鉴权
    voice/       LiveKit 集成
  src/main/resources/
    application.yml          配置（全部走环境变量）
    db/migration/V*.sql      Flyway 迁移，表结构的唯一来源
    seed/seed-data.json      初始 BOSS 与阵容数据
frontend/
  src/pages/        页面
  src/components/   组件
  src/features/     跨页共享逻辑
  public/boss-icons/ BOSS 图标
deploy/
  gmsr.env.example  环境变量模板
  RESTORE.md        备份恢复流程
  *.sh              部署与备份脚本
```

---

## 十二、许可

内部使用。未附带开源许可证。
