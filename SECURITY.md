# 配置与敏感信息说明

本文档说明本项目**哪些配置含敏感信息、如何设置**，以及开源版本做了哪些脱敏处理。

> **快速上手**：如果你只是想跑起来，照「快速开始」一节做即可；
> 如果是部署到公网，请务必读完「生产环境必须配置的项」。

---

## 一、开源版本做了什么脱敏

本仓库是脱敏后的版本。以下内容**已从代码与文档中移除**，替换为占位符：

| 项目 | 原内容 | 现在 |
| --- | --- | --- |
| 服务器 IP | 真实公网 IP | `<YOUR_SERVER_IP>` |
| 管理员密码 | 真实密码 | `CHANGE_ME_ADMIN_PASSWORD` |
| 群友初始密码 | 真实密码 | `CHANGE_ME_PLAYER_PASSWORD` |
| 数据库密码 | 真实密码 | `CHANGE_ME_DB_PASSWORD` |
| JWT 签名密钥 | 真实密钥 | `CHANGE_ME_JWT_SECRET` |
| 群友 QQ 号 | 14 个真实 QQ | `100000001` 起的假号 |
| 群友昵称 | 真实游戏昵称 | `群友1`、`群友2`… |
| LiveKit 密钥 | 真实 api key/secret | 不提供（需自己生成） |

**因此**：直接 `git clone` 下来的代码**跑得起来，但用的是占位密码** ——
你必须先按下面的说明配置真实值，否则启动后无法登录。

---

## 二、配置项一览

后端所有配置都支持**环境变量覆盖**，代码里的默认值只是本地开发的兜底。
线上部署推荐把变量集中写在一个 env 文件里（本项目的约定是 `/opt/gmsr/gmsr.env`，权限 `600`），
由 systemd 的 `EnvironmentFile=` 注入。

### 2.1 数据库

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `GMSR_DB_URL` | JDBC 连接串，`currentSchema` 指定专用 schema | `jdbc:postgresql://127.0.0.1:5432/gmsr?currentSchema=gmsr` |
| `GMSR_DB_USER` | 数据库用户名 | `gmsr_app` |
| `GMSR_DB_PASSWORD` | 数据库密码 | `CHANGE_ME_DB_PASSWORD` |

> 建议给应用单独建一个账号，只授权 `gmsr` schema，不要用 postgres 超级用户。

### 2.2 登录与会话

| 变量 | 说明 |
| --- | --- |
| `GMSR_JWT_SECRET` | 签名密钥。**生产必须换随机值**，生成方式见下 |
| `GMSR_ADMIN_USER` | 首次启动自动创建的管理员用户名（默认 `admin`） |
| `GMSR_ADMIN_PASSWORD` | 管理员初始密码。**生产必须换**，且首次登录后建议再改一次 |
| `GMSR_PLAYER_PASSWORD` | 群友账号初始密码（也是管理员「重置密码」后的密码）。本人首次登录会被强制改密 |
| `GMSR_LOGIN_MAX_ATTEMPTS` | 连续登录失败多少次后锁定（默认 8） |
| `GMSR_LOGIN_LOCK_MINUTES` | 锁定时长（默认 15 分钟） |

生成随机密钥：

```sh
openssl rand -base64 48
# 或
head -c 48 /dev/urandom | base64
```

### 2.3 Redis（会话存储）

| 变量 | 说明 |
| --- | --- |
| `GMSR_REDIS_HOST` | Redis 地址，默认 `127.0.0.1` |
| `GMSR_REDIS_PORT` | 端口，默认 `6379` |
| `GMSR_REDIS_PASSWORD` | 密码，无密码留空 |
| `GMSR_REDIS_DB` | 库号，默认 `1`（与其它业务隔离） |

> Redis 只存登录会话。Redis 挂了不影响排班数据，但所有人会掉线重新登录。

### 2.4 语音频道（LiveKit，可选）

| 变量 | 说明 |
| --- | --- |
| `GMSR_VOICE_ENABLED` | 是否启用语音，默认 `false` |
| `GMSR_VOICE_API_KEY` | LiveKit 的 api key |
| `GMSR_VOICE_API_SECRET` | LiveKit 的 api secret |
| `GMSR_VOICE_WS_URL` | 前端连接地址，如 `wss://<YOUR_SERVER_IP>:4433/rtc` |

不启用语音时这项留空即可，其余功能不受影响。

### 2.5 跨域

| 变量 | 说明 |
| --- | --- |
| `GMSR_CORS` | 允许的来源，逗号分隔。**同源部署时不需要配置**，仅本地 vite 调试需要 |

---

## 三、生产环境必须配置的项

漏掉这些会导致**安全问题**或**跑不起来**：

| 变量 | 不配的后果 | 必须性 |
| --- | --- | --- |
| `GMSR_DB_PASSWORD` | 连不上数据库 | **必须** |
| `GMSR_JWT_SECRET` | 用默认占位密钥签名，令牌可被伪造 | **必须换** |
| `GMSR_ADMIN_PASSWORD` | 管理员密码是占位值，且启动日志会告警 | **必须换** |
| `GMSR_PLAYER_PASSWORD` | 群友初始密码是占位值 | **必须换** |
| `GMSR_VOICE_API_SECRET` | 语音频道不可用 | 启用语音时必须 |

程序启动时会自检并在日志里告警：
- Redis 连不上
- 管理员密码仍是默认占位值

---

## 四、快速开始（本地）

```sh
# 1) 准备数据库（PostgreSQL 14+）
createdb gmsr
psql -d gmsr -c 'CREATE SCHEMA IF NOT EXISTS gmsr;'

# 2) 配置环境变量（复制示例后按需修改）
cp deploy/gmsr.env.example deploy/gmsr.env
# 编辑 deploy/gmsr.env，至少填 GMSR_DB_PASSWORD / GMSR_JWT_SECRET / GMSR_ADMIN_PASSWORD

# 3) 起后端（表结构由 JPA 自动创建，初始数据由种子导入器写入）
cd backend
set -a && . ../deploy/gmsr.env && set +a
mvn spring-boot:run

# 4) 起前端
cd ../frontend
npm install
npm run dev        # 默认 http://127.0.0.1:5173，接口走 vite 代理
```

默认端口：后端 `18080`（只监听 127.0.0.1），前端开发服务器 `5173`。

---

## 五、绝对不要提交的文件

本仓库的 `.gitignore` 已排除以下内容，**提交前请确认它们没被加进来**：

```
# 运行配置（含真实密码）
deploy/gmsr.env
*.env
.env

# 证书与私钥
*.pem
*.key
*.crt
*.p12
*.jks

# SSH 私钥沿用目录
.deploy/
id_rsa*
id_ed25519*

# 构建产物
node_modules/
dist/
target/
```

如果误提交了密码，**改密码比删提交更重要** —— 因为 git 历史会被缓存/镜像/fork，
即使 force push 也无法保证彻底删除。

---

## 六、上线前的安全检查清单

- [ ] `GMSR_JWT_SECRET` 已换成随机值（不是示例值、不是仓库里的默认值）
- [ ] `GMSR_ADMIN_PASSWORD` 已换，且登录后改过一次
- [ ] `GMSR_PLAYER_PASSWORD` 已换（群友首次登录会强制改自己的密码）
- [ ] 数据库账号只用最小权限（仅 `gmsr` schema）
- [ ] `deploy/gmsr.env` 权限是 `600`，属主是运行服务的用户
- [ ] 站点走 HTTPS（自签证书需让用户先安装 CA，见 `/ca-install.html`）
- [ ] Redis 未暴露到公网
- [ ] LiveKit 的 `api key/secret` 未写进任何前端代码
- [ ] 仓库里 `git grep -i "password\|secret\|token"` 检查一遍没有真实值
- [ ] 若用自签证书与私网 IP，确认没有把私钥提交进仓库

---

## 七、安全设计要点（代码层面）

| 项 | 实现 |
| --- | --- |
| 密码存储 | BCrypt 加盐哈希（cost 10），接口与页面永不返回密码或哈希 |
| 令牌 | SA-Token，access token 2 小时（UUID），refresh token 7 天滑动续期，存 Redis |
| 令牌存放 | 前端 localStorage，请求头 `gmsr-token` |
| 改密/重置/删号 | 强制该账号所有设备重新登录 |
| 暴力破解 | 单账号 8 次失败锁定 15 分钟；单 IP 30 次 |
| 权限 | `ADMIN`（全部）/ `VIEWER`（只读）+ 可选的 `canSchedule`（可编排车次） |
| 数据越权 | 群友只能改自己的角色；QQ 号只有管理员能看到 |
| 未登录 | 只能看「车次一览表」（只读） |
| 传输 | 强制 HTTPS，HTTP 自动 301 跳转 |
