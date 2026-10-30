# 代码审查与发布检查（v1.0.0）

审查范围：后端 56 个 Java 文件（5784 行）+ 前端 25 个 TS/TSX 文件（5178 行）。

## 一、已修复

### 1. 新增车次接口 500（严重 · 正确性）
- **位置**：`service/RunService.java` `create()`
- **现象**：点「+ 新增车次」返回 500，报
  `LazyInitializationException: Could not initialize proxy [WeekSchedule#...] - no session`
- **原因**：方法缺少 `@Transactional`，内部调用 `get()` 时 Hibernate 会话已关闭，访问 `week.getLabel()` 触发懒加载。
- **修复**：补上 `@Transactional`。

### 2. 数据错误：同一车次出现同一玩家的两个角色（严重 · 数据）
- **位置**：`runId=81`（卡琳第1趟 E 难度，本周）
- **现象**：群友G（playerId=6）同时占了 Kanna 与剑豪两个席位。
- **原因**：该记录创建于「同一趟车只能有一个自己的角色」校验上线**之前**。
- **修复**：移除重复的 `群友G/剑豪`（保留 Kanna），车次回到 5/6 人。

### 3. JWT 签名算法不统一（中等 · 可维护性）
- **位置**：`security/JwtService.java`
- **现象**：登录 token 实际签发为 `HS384`（由密钥长度隐式决定），而语音 token 显式用 `HS256`。
- **修复**：显式指定 `Jwts.SIG.HS256`，全系统统一。

### 4. 缺少默认密钥保护（中等 · 安全）
- **位置**：新增 `security/SecurityStartupCheck.java`
- **风险**：`application.yml` 里为方便本地开发留了兜底的 JWT 密钥与默认密码。生产部署若忘记注入环境变量，会**静默**使用源码里公开的默认值，任何人都能据此伪造管理员 token，而且不会有任何报错。
- **修复**：启动时自检 —— 生产 profile 下使用默认 JWT 密钥**直接启动失败**；默认管理员密码则告警。

## 二、待处理（需要管理员决策）

### 数据：同一角色在同一 BOSS 同难度下被排了两趟
这两条属于历史数据，可能是无意的，但删除会影响排班，因此交由管理员确认：

| # | 位置 | 重复角色 | 涉及车次 |
| --- | --- | --- | --- |
| 1 | 上周（2026-10-01）神选者塞伦 **H** | 群友N/剑豪 | 第1趟 runId=1（3人）、第2趟 runId=2（2人） |
| 2 | 本周（2026-10-08）监视者卡尔洛斯 **N** | 群友B/剑豪 | 第1趟 runId=67（2人）、第2趟 runId=68（5人） |

> 注：同一角色出现在同一 BOSS 的**不同难度**（例如 群友B/剑豪 在卡尔洛斯 N 与 EX）是合理的，工具已能区分，不再报错。

## 三、新增的回归工具

### `frontend/scripts-check-integrity.mjs`
发版前 / 定期巡检用的数据完整性校验，检查 5 类规则：

1. `memberCount` 字段与实际成员数一致、且不超容量
2. 同一 BOSS + 同周期 + 同难度下 `runIndex` 不重复
3. 同一车次内不出现重复角色、不出现同一玩家的两个角色
4. 同一角色不在同周期同一 BOSS 的同难度多趟车上
5. 融合车：同一趟在所有成员 BOSS 上都有记录且阵容完全一致

用法：

```bash
GMSR_TOKEN=<管理员JWT> node scripts-check-integrity.mjs
# 或
npm run check:integrity
```

退出码：`0` 全部通过 / `1` 发现问题 / `2` 用法错误，可直接接入发布流程。

## 四、已验证无问题的方面

- 前端无 `any`、无非空断言 `!.`、无 TODO/FIXME/调试输出
- 后端无 `System.out.print`、无遗留调试代码
- 生产环境 JWT 密钥、数据库密码、语音密钥均为自定义值（非源码默认）
- CORS 使用显式白名单（非通配符）且未配置时为空，配合 `allowCredentials(true)` 安全

---

## 五、三方专项审查的主要发现（完整清单）

以下按「是否已修」分类，未修的按严重程度排序，供后续排期。

### 已修（本轮）

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | 「+ 新增车次」500（缺 `@Transactional`） | `service/RunService.java` |
| 2 | 卡琳第1趟同一玩家两角色（历史数据） | 数据修正（移除重复席位） |
| 3 | JWT 算法隐式为 HS384、与语音 token 不一致 | `security/JwtService.java` |
| 4 | 缺默认密钥保护（生产误用源码默认值可被伪造 token） | 新增 `security/SecurityStartupCheck.java` |
| 5 | **匿名泄露 QQ（=登录用户名）→ 可批量接管未改密账号** | `service/PlayerService.java` |
| 6 | `GET /api/**` 顺序导致 3 条鉴权规则失效（语音接口匿名可读） | `security/SecurityConfig.java` |
| 7 | 分组权限路径写错（`/api/groups` 实为 `/api/boss-groups`） | `security/SecurityConfig.java` |
| 8 | **融合车操作全部失效**：误以为同一 `memberId` 对应 7 条车次 | `pages/BossSchedulePage.tsx` |
| 9 | 融合车发给后端的 bossId 是合成负数 → 404 | 同上（改用 `primaryBossId`） |
| 10 | 融合车删除车次只删主 BOSS → 剩 6 条变「历史遗留」 | 同上 |
| 11 | 融合车 siblings 聚合未限难度 → 会误并入别的难度车次 | 同上 |
| 12 | 融合车拖拽换位无高亮（漏了 `combo-member`） | 同上 |
| 13 | token 过期只报「请求失败 401」，界面卡在已登录态 | `api/client.ts` + `AuthContext.tsx` |

### 未修 · 严重（建议优先）

| # | 问题 | 位置 | 后果 |
| --- | --- | --- | --- |
| A | 删群友/删角色不清 `character`／`run_member` | `service/PlayerService.java` | 上过车就删不掉，恒 500（前端确认框还承诺会级联） |
| B | `deleteGroup` 不删该组 BOSS 就删分组 | `service/BossService.java` | 外键必失败，`DELETE /api/boss-groups/{id}` 恒 500 |
| C | `PlayerService.update` 漏 `validateQq` | `service/PlayerService.java` | 可写入非法/重复 QQ → 唯一约束 500 |
| D | 只读事务里写库（`listByWeek` → `ensureWeek` INSERT） | `service/RunService.java`／`WeekService.java` | 只读事务下静默丢弃或直接报错 |
| E | `resequence` 固定用「本周」，忽略车次实际周期 | `web/RunController.java` | 对上周车次重排会改错数据 |
| F | 未登录 GET 可写库（任意 weekKey 造周期行） | `WeekService.ensureWeek` | 脏数据 + 表膨胀（可被反复触发） |
| G | `mustChangePassword` 服务端不强制 | `service/AuthService.java` | 拿到初始口令即可长期使用全部 API |
| H | `bind-player` 无归属校验、`app_user.player_id` 无唯一约束 | `web/PlayerBindingController.java` | 可认领任意群友 → 改其角色/状态 |
| I | 启动迁移无 marker，每次重启覆盖手工修改 | `service/DataMigrator.java` | 管理员改的 QQ/昵称/容量重启即回滚；新建同名群友会被删 |
| J | 容量/序号/唯一性只靠「先查后写」 | `service/RunService.java` 等 | 并发下可超员、车次重号 |
| K | 兜底异常把 4xx 吞成 500，无 `DataIntegrityViolation` 映射 | `web/GlobalExceptionHandler.java` | 参数错误变 500，可刷日志 |
| L | 管理员密码仍是 `CHANGE_ME_ADMIN_PASSWORD`（5 个群友账号也未改密） | 生产配置 | 配合已修的情报泄露曾可直接接管 |

### 未修 · 中等

- `JwtAuthFilter` 完全信任 token 声明，TTL 168h：撤权限/禁用/删号后旧 token 最长 7 天有效
- 所有群友默认 `canSchedule=true`，而 SCHEDULE 可删 BOSS／删分组／整周覆盖
- `RunController` 的 `updateMember/removeMember/moveMember/swapMembers` 忽略路径里的 `{id}`（记录级鉴权缺失）
- 关键字段无 `@Size`，越长会写库才报错
- 长事务占用连接池（BCrypt 在事务内、整周复制单事务）
- 多处「全表加载再内存过滤」的 N+1
- HTML5 拖拽在触屏不可用（移动端无法排班）；BOSS 卡片无可键盘访问性
- `Toast` 定时器未清理，连续提示会互相打断
- 融合车合并逻辑在 3 个页面各写一份，已开始漂移（建议抽 `features/combo.ts`）
- 请求无竞态保护：快速切换 BOSS／周期时旧响应会覆盖新响应

## 六、运维待办

1. **尽快修改管理员密码**（当前仍是 `CHANGE_ME_ADMIN_PASSWORD`），并让 5 个未改密的群友登录改密
2. 建议轮换 `GMSR_JWT_SECRET`（轮换后所有人需重新登录）
3. `README.md` 里两处不实描述需修正：① 「群友只能标记自己所在车次的状态」（实际 canSchedule 可删 BOSS/整周覆盖）② 「数据库触发器保证首次改密」（并无触发器）

---

## 七、鉴权改造：SA-Token + 双 token 无感刷新

### 为什么改

原实现是手写 JWT，有三个结构性问题：
1. **完全信任 token 里的权限声明** —— 撤销排班权限、禁用账号、甚至删号后，对方手里的 token 在 7 天有效期内仍然畅通无阻。
2. **单 token、无刷新机制** —— 为了让群友不用频繁登录，只能把有效期设成 7 天，攻击窗口很大。
3. **自定义鉴权代码需要自己维护** —— 算法、密钥强度、解析边界都靠自己保证。

### 现在的方案

| 项 | 值 |
| --- | --- |
| 框架 | [SA-Token](https://sa-token.com/) 1.44.0（社区版） |
| 会话存储 | Redis（1Panel 实例，`db 1` 与面板自身数据隔离） |
| access token | 2 小时（`sa-token.timeout: 7200`），请求头 `gmsr-token` |
| refresh token | **7 天滑动**（自作实现，存 Redis，随机串而非 JWT） |
| 7 天无操作 | refresh token 自然过期 → 必须重新输入账号密码 |

**为什么 refresh token 自己实现**：SA-Token 官方的双 token（refreshToken）能力属于付费 Pro 版；
社区版用 `active-timeout` 做滑动过期，但那样 token 值不变、无法真正轮换。
自己实现只需约 150 行，且能获得**一次性使用（防重放）+ 可吊销**两个好处。

### 无感刷新的时序

```
请求 → access token 有效？ ──是──→ 正常返回
                          └─否─→ 调用 /api/auth/refresh（带 refresh token）
                                   ├─成功 → 换发新 access + 新 refresh（旧的立即作废）→ 自动重试原请求
                                   └─失败（7 天没用了）→ 清空本地 token → 回到未登录态
```

并发 401 只发起一次刷新（`refreshing` Promise 复用），避免 refresh token 被轮换多次导致后续请求全挂。

### 附带修复的安全问题

- **权限立即生效**：`SaTokenAuthFilter` 每次请求都查库取当前角色与 `canSchedule`；账号被禁用/删除则当场 `logout`。
- **改密 / 重置密码 / 删号 → 强制所有设备重新登录**（`kickOut`：清 SA-Token 会话 + 吊销全部 refresh token）。这是旧 JWT 方案做不到的。
- **refresh token 一次性使用**：重放旧 token 返回 400（已实测）。
- **启动自检**：Redis 连不上时在启动日志里明确报错，而不是等用户反馈「一直提示请先登录」。

### 实测结果（7/7 通过）

| 验证项 | 结果 |
| --- | --- |
| 登录返回双 token | access 36 字符（UUID）/ refresh 64 字符 |
| access 有效期 | 7200 秒 = 2 小时 |
| `gmsr-token` 头访问受保护接口 | 200 |
| 旧 `Authorization: Bearer` | 401（确认已彻底切换到 SA-Token） |
| 刷新 | access 更换 ✓ + refresh 轮换 ✓ |
| 旧 refresh token 重放 | 400 拒绝（防重放生效） |
| 登出后 refresh token | 失效 |
| 冒烟测试 | 9/9 PASS（请求均带 `gmsr-token`） |

### 后续可做

- access token 目前 2 小时；如果希望更安全可降到 30 分钟（refresh 机制已能保证无感，用户不会察觉）。
- `RefreshTokenService` 的单账号 token 上限（5 个）用 Set 实现，踢最旧的那个是「任取一个」，不是严格 LRU；功能上无影响。
