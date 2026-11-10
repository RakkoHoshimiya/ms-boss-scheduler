# GMSR BOSS 组队排班系统

冒险岛（GMSR）固定队排班与席位编排工具。核心是「一趟车上谁能上、上什么角色、还差几个人」，
以及融合车（多个 BOSS 必须凑同一批人打）的一致性约束。

- **后端**：Spring Boot 3.5.16 / Java 25 / PostgreSQL 18 / Flyway
- **前端**：React 18 + TypeScript + Vite（构建产物是纯静态文件）
- **鉴权**：SA-Token + Redis（服务端会话，可随时吊销）
- **语音**（可选）：LiveKit

---

## 一、快速部署（单机）

以下步骤在一台干净的 Linux（Debian/Ubuntu 系）上从零跑通。假设用 `root` 操作。

### 0. 需要先准备的东西

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **25** | `pom.xml` 里 `java.version=25`，低版本编译不过 |
| Maven | 3.9+ | 仅构建时需要，运行只用 jar |
| PostgreSQL | **16 / 17 / 18** | 用 Docker 起最省事 |
| Redis | 6+ | SA-Token 会话存储，**必须有** |
| Node.js | 20+ | 仅构建前端时需要 |

> Flyway 官方支持到 PostgreSQL 17。跑 18 会有 `Flyway upgrade recommended` 警告，**不影响运行**。

### 1. 起数据库与 Redis

```bash
docker run -d --name gmsr-pg \
  -e POSTGRES_PASSWORD='换成你的强密码' \
  -p 127.0.0.1:5432:5432 \
  -v /opt/gmsr/pgdata:/var/lib/postgresql/data \
  postgres:18-alpine

docker run -d --name gmsr-redis \
  -p 127.0.0.1:6379:6379 \
  redis:7-alpine redis-server --requirepass '换成你的Redis密码'
```

### 2. 建库建账号

```bash
docker exec -i gmsr-pg psql -U postgres -d postgres <<'SQL'
CREATE ROLE gmsr_app LOGIN PASSWORD '换成你的库密码';
CREATE DATABASE gmsr OWNER gmsr_app ENCODING 'UTF8' TEMPLATE template0;
SQL

docker exec -i gmsr-pg psql -U postgres -d gmsr <<'SQL'
CREATE SCHEMA IF NOT EXISTS gmsr AUTHORIZATION gmsr_app;
ALTER ROLE gmsr_app SET search_path = gmsr, public;
SQL
```

> `search_path` 这一句**不能省**。应用依赖它定位 `gmsr` schema 下的表，
> 否则触发器里的函数会找不到表（表现为 `relation "boss_run" does not exist`）。

### 3. 构建后端

```bash
git clone https://github.com/RakkoHoshimiya/ms-boss-scheduler.git
cd ms-boss-scheduler/backend
mvn -B package -DskipTests
# 产物：target/gmsr-scheduler.jar
```

### 4. 写配置

```bash
useradd --system --home /opt/gmsr --shell /usr/sbin/nologin gmsr
mkdir -p /opt/gmsr/app /opt/gmsr/logs
cp target/gmsr-scheduler.jar /opt/gmsr/app/
chown -R gmsr:gmsr /opt/gmsr
```

创建 `/opt/gmsr/gmsr.env`（权限 `600`，属主 `gmsr`）：

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

# 可选：语音
GMSR_VOICE_ENABLED=false
```

```bash
chmod 600 /opt/gmsr/gmsr.env
chown gmsr:gmsr /opt/gmsr/gmsr.env
```

### 5. 起后端服务

```bash
cat >/etc/systemd/system/gmsr-backend.service <<'UNIT'
[Unit]
Description=GMSR Scheduler Backend
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=gmsr
Group=gmsr
WorkingDirectory=/opt/gmsr
EnvironmentFile=/opt/gmsr/gmsr.env
ExecStart=/usr/bin/java -Xms64m -Xmx384m -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar /opt/gmsr/app/gmsr-scheduler.jar
Restart=always
RestartSec=5
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now gmsr-backend
curl -s http://127.0.0.1:18080/api/health
```

后端**只监听 127.0.0.1:18080**，不对外暴露。

### 6. 构建并部署前端

```bash
cd ../frontend
npm ci --no-audit --no-fund
npm run build          # 产物在 dist/
```

把 `dist/` 整个目录交给任意静态服务器即可。用 Nginx：

```nginx
server {
    listen 443 ssl;
    server_name your.domain.com;

    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;

    root /var/www/gmsr;
    index index.html;

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
rm -rf /var/www/gmsr/* && cp -r dist/. /var/www/gmsr/
nginx -t && nginx -s reload
```

> 构建产物用的是**相对路径**（`vite.config.ts` 里 `base: './'`），
> 所以放在根路径 `/` 或子目录 `/gmsr/` 下都能直接用，不需要改配置。

### 7. 首次登录

用 `GMSR_ADMIN_USER` / `GMSR_ADMIN_PASSWORD` 登录。
**首次登录会强制改密**（服务端强制，不是前端弹窗），改完才能用其它功能。

---

## 二、数据库迁移

**不要手写建表语句。** 表结构全部由 Flyway 管理，启动时自动执行 `backend/src/main/resources/db/migration/` 下的 `V*.sql`。

- 空库：首次启动自动建全套结构
- 已有数据的库：迁移会就地执行，无需手动干预
- 新增结构变更：**只能加新的 `V7__xxx.sql`**，不要改动已发布的迁移文件

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
| `GMSR_DB_URL` | 是 | `jdbc:postgresql://127.0.0.1:54233/gmsr` | JDBC 连接串 |
| `GMSR_DB_USER` | 是 | `gmsr_app` | 数据库账号 |
| `GMSR_DB_PASSWORD` | 是 | — | 数据库密码 |
| `GMSR_DB_SCHEMA` | 否 | `gmsr` | schema 名 |
| `GMSR_REDIS_HOST` | 是 | `127.0.0.1` | Redis 地址 |
| `GMSR_REDIS_PORT` | 否 | `6379` | |
| `GMSR_REDIS_PASSWORD` | 是 | 空 | **Redis 不可用会导致全员掉线** |
| `GMSR_REDIS_DB` | 否 | `1` | 与其它业务隔离 |
| `GMSR_ADMIN_USER` | 否 | `admin` | 初始管理员账号 |
| `GMSR_ADMIN_PASSWORD` | 是 | — | 初始管理员密码 |
| `GMSR_PLAYER_PASSWORD` | 否 | `gmsr123456` | 群友初始密码 |
| `GMSR_CORS` | 否 | — | 仅本地 vite 调试需要 |
| `GMSR_LOGIN_MAX_ATTEMPTS` | 否 | `8` | 同账号失败锁定阈值 |
| `GMSR_LOGIN_LOCK_MINUTES` | 否 | `15` | 锁定时长 |
| `GMSR_BOSS_ICON_DIR` | 否 | `/opt/1panel/www/gmsr/boss-icons` | 仅启动自检用，指向你的静态站点图标目录 |
| `GMSR_VOICE_ENABLED` | 否 | `false` | 语音功能开关 |
| `GMSR_VOICE_API_KEY` | 否 | — | LiveKit key |
| `GMSR_VOICE_API_SECRET` | 否 | — | LiveKit secret |
| `GMSR_VOICE_WS_URL` | 否 | — | 形如 `wss://域名/rtc` |

> **生产环境务必覆盖** `GMSR_ADMIN_PASSWORD` 和 `GMSR_PLAYER_PASSWORD`。
> 用默认值时启动日志会告警，但不会阻止启动。

---

## 四、权限模型

只有两种角色，**排班权限是独立开关**：

| 角色 | 说明 |
| --- | --- |
| `ADMIN` | 管理员：管理账号、重置密码，同时具备排班权限 |
| `VIEWER` | 群友：能登录、看自己的车次、登记自己的角色 |

`app_user.can_schedule = true` 的账号拥有排班权（改 BOSS、排车位、删车次）。

**匿名访问范围**（未登录能做什么）：

- ✅ 车次一览表（`GET /api/runs`、`/api/runs/{id}`）
- ✅ BOSS 列表（`GET /api/bosses`、`/api/boss-groups`）
- ✅ 重置时间信息（`GET /api/status-reset`）
- ❌ 群友名单、角色列表、按人查行程、语音频道 —— **全部要求登录**

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
| POST | `/runs/{id}/copy` | 复制一趟（含阵容，融合车整组复制） |
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

车次的「已完成」标记**每周四 08:00 自动归零**（排班本身不变，只是打没打完这层状态）。

服务若在该时刻没运行，下次启动会补执行一次，不会漏。

---

## 八、备份与恢复

`deploy/backup.sh`、`deploy/restore.sh` 是作者线上环境在用的脚本，里面写死了
1Panel 面板创建的 PostgreSQL 容器名与 `/opt/gmsr` 目录结构。**换环境使用前必须先改这两处**，
否则脚本会找不到容器。

通用做法：

```bash
# 备份
docker exec -i gmsr-pg pg_dump -U gmsr_app -d gmsr | gzip > gmsr-$(date +%F).sql.gz

# 恢复（先停服务！）
systemctl stop gmsr-backend
gunzip -c gmsr-2026-11-09.sql.gz | docker exec -i gmsr-pg psql -U gmsr_app -d gmsr
systemctl start gmsr-backend
```

详细流程见 [`deploy/RESTORE.md`](deploy/RESTORE.md)。

---

## 九、本地开发

```bash
# 后端（需要本地 PG + Redis，或改 application.yml 指向容器）
cd backend && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
# vite 默认 5173，通过 GMSR_CORS 允许跨域
```

测试：

```bash
cd backend
mvn test                      # 只跑纯单元测试
GMSR_INTEGRATION_DB=1 mvn test   # 连同需要真实数据库的集成测试
```

前端校验脚本：

```bash
cd frontend
npm run check-icons      # BOSS 图标文件是否齐全
npm run check:integrity  # 数据一致性巡检
npm run smoke            # 冒烟测试
```

---

## 十、故障排查

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `checksum mismatch` | 改动过已发布的迁移文件。恢复原文件，或新增 `V7` |
| 启动报 `relation "xxx" does not exist` | 数据库账号缺 `search_path = gmsr, public` |
| 启动报 `ddl-auto: validate` 失败 | 实体与库结构不一致，检查迁移是否全部执行 |
| 所有人突然变成未登录 | Redis 挂了。检查 `GMSR_REDIS_*` 与 Redis 进程 |
| 前端 404 / 白屏 | Nginx 缺 `try_files ... /index.html`（SPA 回落） |
| 登录后接口全 403 | 账号 `must_change_password` 为真，先改密码 |
| 封面图裂开 | 图标文件缺失，跑 `npm run check-icons` |
| 语音无法连接 | 需放行 UDP 50000-50200、TCP 7881、UDP/TCP 3478、TCP 5349 |

日志：`journalctl -u gmsr-backend -f`

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
  *.sh              线上在用的部署/备份脚本
```

---

## 十二、许可

内部使用。未附带开源许可证。


### 活动攻略与TIPS：编辑及附件

登录后可从「活动便签」进入。所有登录用户均可发布、编辑、删除及置顶；列表按置顶、最近更新时间排序，支持搜索及详情查看。正文使用单一连续编辑区，图片直接显示在文字中，支持光标插图、粘贴图片及直接删除图片；保留换行，不执行粘贴的 HTML。发布和编辑时均可上传文件，选择插入正文或作为底部附件；详情中仅尾附文件显示在底部，正文图片按编辑位置展示。

附件先使用服务器本地存储，不依赖 NAS 在线。设置 `GMSR_NOTES_DIRECTORY` 为持久目录（生产建议 `/opt/gmsr/data/note-attachments`），并确保运行服务的 `gmsr` 用户可读写。默认是工作目录下的 `data/note-attachments`，不要放入静态网站目录。附件读取同样要求登录，不提供公开文件链接。

存储键规则：`YYYY/MM/DD/attachment-{UUID}.{小写扩展名}`（旧附件的 `note-{便签ID}-{UUID}` 路径保持兼容），日期使用上海时区；原始文件名只作为下载名称保存在数据库。单附件 20 MB，每张便签最多 12 个附件。支持 PNG、JPG、GIF 图片预览及 WebP、PDF、TXT、ZIP 下载。代理上传限制需至少 21 MB（仓库的 OpenResty 配置已同步）。数据库结构由 V7、V8 迁移创建。新文件先作为本人可读的待发布附件上传，保存时通过事务绑定便签；取消时清理新文件，超过 24 小时未发布的文件由定时任务回收。编辑中移除旧附件只在保存后生效。

**备份时需另外备份附件目录，并与数据库一同恢复**；现有数据库/配置备份脚本不会包含附件文件。更换附件目录时应先复制原文件。NAS RustFS 暂未连接或配置；后续接入需确认服务器到 NAS 的访问地址和专用存储凭据。

便签回归检查：先在 `frontend` 下执行 `npm run build`，再执行 `npm run verify:notes`，覆盖发布前上传、光标插图、尾附、失败保留及取消编辑。
