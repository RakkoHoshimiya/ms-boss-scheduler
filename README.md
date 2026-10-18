# GMSR 国际服冒险岛 · BOSS 组队排班系统

> **这是一个脱敏后的开源版本**：所有密码、密钥、服务器 IP、QQ 号与群友昵称都已替换为占位符/示例值。
> 部署前**必须**先读 [SECURITY.md](SECURITY.md) 配置自己的环境变量，否则无法正常登录。

把原 Excel 排班表（`gmsr排班表.xlsx`）做成了可多人协作的 Web 系统：
**Spring Boot 3 + JDK 25 + PostgreSQL 18** 后端，**React + TypeScript + Vite** 前端，
托管在 1Panel 的 OpenResty 上。

## 线上地址

| 协议 | 地址 | 说明 |
| --- | --- | --- |
| HTTP | http://<YOUR_SERVER_IP>:8080/ | 主入口（OpenResty 8080） |
| HTTPS | https://<YOUR_SERVER_IP>:4433/ | **必须用这个**（8080 会自动跳过来）；证书是私有 CA 签发的，需先按 /ca-install.html 安装信任 |

### 账号

| 账号类型 | 用户名 | 初始密码 | 能做什么 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `CHANGE_ME_ADMIN_PASSWORD` | 全部功能：编排车次、管理 BOSS/群友、重置群友密码 |
| 群友（14 个，自动创建） | **QQ 号**，例如 `100000001` | `CHANGE_ME_PLAYER_PASSWORD` | 查看全部排班；标记自己所在车次的「未完成 / 已完成」；改自己的密码 |
| 访客（未登录） | — | — | 只读查看全部排班 |

群友账号一律用 **QQ 号**登录，初始密码由管理员掌握并逐个告知本人；**本人首次登录会被强制要求设置自己的密码**。
忘记密码找管理员，在「系统设置」点「重置密码」——重置后密码恢复为系统初始密码，本人下次登录必须重新设置。

> 安全约定：**前端页面不显示任何账号规则或默认密码**，这些信息只在本文档（内部资料）与管理员手里。

群友账号已按 QQ 对照表重建并绑定到对应群友档案（一个群友一个账号）。
管理员也可以在「系统设置」里关闭某个账号的 boss排班 权限（变回只读）。

## 功能

- **左侧导航**（按此顺序）：**车次一览表** / **我的车次** / 我的信息（登录后可见）/ **boss排班**（有排班权限的账号）/ 群友角色一览表 / 系统设置（管理员），手机端自动收成顶部横滑导航。
  - **未登录：只显示「车次一览表」这一个菜单**，首页就是它（谁都能看全群排班）。
  - **登录后**：菜单按权限展开，首页变成「我的车次」（只显示自己要打的 BOSS 与车次）。
  - 权限映射：我的角色 → 登录即可；boss排班 → 所有 QQ 群友账号（管理员可单个关闭）；系统设置 → 仅管理员。（原「排班管理」已并入 boss排班，旧地址 `#/schedule` 会自动跳到 boss排班）
- **管理员不做「我的车次」**：管理员账号的菜单里没有「我的车次」（首页也直接落在「车次一览表」），
  因为管理员只负责**账号管理**和**帮忙登记群友的游戏角色（职业 / 战力 / 备注）**。
  - 管理员登记角色的入口：**群友角色一览表** → 找到对应群友 → 点「+ 添加角色」（没角色的群友也会占位显示）。
  - 群友本人的入口：**我的角色**（只能改自己名下的角色）。
- **车次一览表**（**未登录也能看**，是访客默认页）：按 BOSS 汇总成表格（车次 / 难度 / 成员 / 人数 / **总战力** / 状态 / 操作），适合查阅。
  - **车次序号按难度分组各自编号**（Normal 第1趟、第2趟…；Chaos 第1趟、第2趟…），因为排班页是按难度分开显示的；新增车次也会自动接在同难度的末尾。
  - 每张 BOSS 卡片标题只显示 **BOSS 头像 + 名称 +「分组 · 每车 N 人 · N 趟」**；难度不放在标题上，因为下面每一行都有该趟车的难度。
  - **「我参加」列**：自己上的车显示「是」（居中），没上的留空（不显示「否」，避免画蛇添足）；参与的行底色微微高亮。
  - 表格列：**难度 / 成员 / 我参加 / 人数 / 总战力 / 状态 / 操作**（不再单列「车次」，同难度内的序号对查阅没有意义）。
  - **操作列只做状态标记**：有排班权限的账号显示「✓ 已完成 / 改回未完成」，车上的群友本人显示「✓ 我打完了」。
  - **没有上人 / 整队 / 设置 / 复制 / 删除按钮** —— 这些编排功能全部集中在 **boss排班** 里，避免重复入口和误操作。
  - 顶部可切换周期（上周 / 本周 / 下周）、**两级筛选（分组 → 具体 BOSS）**、关键词搜索；有排班权限的账号还能点「🐉 boss排班」直达。
- **我的车次（登录后的首页）**：只列出本周期自己要打的每一趟车——BOSS 名、难度、第几趟、时间安排，并标注「我用哪个角色（多少 M）」上这趟车，可直接点「✓ 我打完了」；可切换 上周/本周/下周，也能在这里认领/切换绑定的群友昵称，避免把状态标到别人的车上。
- **难度（Difficulty）机制**：每个 BOSS 都存了一份**它实际存在的难度清单**，排班页的难度选项以它为准（不再「本周排过什么就显示什么」，避免出现游戏里根本没有的难度）。
  - 显示顺序固定为 **Easy → Normal → Hard → Chaos → Extreme**；每个难度标签上会显示这个难度有几趟车。
  - 难度代码对照（**别再混用**）：**`E` = Easy**、`N` = Normal、`H` = Hard、`C` = Chaos、**`EX` = Extreme**。
  - 新增 / 修改车次时，如果难度不在该 BOSS 的清单里，后端直接拒绝并提示可选难度。
  - 管理员可在 **boss排班 → 编辑 BOSS** 里勾选「这个 BOSS 实际存在的难度」，随时调整。
  - 难度依据：国服官方 v226《BOSS·血量》表 + 国际服实际。
    | BOSS | 可选难度 | 默认 |
    | --- | --- | --- |
    | 神选者塞伦 | Normal / Hard / Extreme | Hard |
    | 监视者卡尔洛斯 | Normal / Hard / Chaos / Extreme | Extreme |
    | 最初的对抗者 | Easy / Normal / Hard / Extreme | Normal |
    | 卡琳 | Easy / Normal / Hard / Extreme | Extreme |
    | 灿烂的凶星 | Normal / Hard / Chaos | Normal |
    | 林波 | Normal / Hard | Normal |
    | 斯乌 | Normal / Hard / Chaos / Extreme | Extreme |
    | 戴米安 | Normal / Hard | Hard |
    | 绿水灵王 | Normal / Hard / Chaos | Chaos |
    | 老黑能量车 | Hard（Extreme 暂无人打，清单里保留） | Hard |
    | 路西德 | Easy / Normal / Hard | Hard |
    | 威尔 | Easy / Normal / Hard | Hard |
    | 至暗魔晶 | Normal / Chaos | Chaos |
    | 觉醒希拉 | Normal / Hard | Hard |
    | 敦凯尔 | Normal / Hard | Hard |
- **boss排班**（**所有 QQ 群友账号可用**，管理员可单个关闭；已整合原「排班管理」）：**四层流程 BOSS → 难度 → 周期 → 排班**。
  - **第 1 层 · 选 BOSS**：外层卡片只有 **BOSS 图标 + 名称**（按 原初世界 / 神秘河 分组），点任意一张进入排班页。
  - **第 2 层 · 选难度**：按 **Easy / Normal / Hard / Chaos / Extreme** 切换，只显示该 BOSS 本周实际排过的难度（切换即过滤车次）。
  - **第 3 层 · 选周期**：上周 / 本周 / 下周 三个标签（一周 = 游戏 CD 周期，周四 ~ 下周三）。标签上的趟数是**当前 BOSS + 当前难度**在那一周的真实趟数（没排过就是 0 趟）——切换难度，三个周期的趟数会跟着变。
  - **第 4 层 · 排班**：
    - **一键复制**：本周←上周 / 下周←本周，**只复制选中的 BOSS + 选中的难度**；目标已有该难度车次时先确认再覆盖。
    - **拖拽调车**：席位（昵称 / 职业 / 战力）直接拖到另一趟车即保存；左下**待选角色池**拖进车次就是上车，席位拖回池子等于移除。同 BOSS 换车、跨 BOSS 调车都支持；满员或角色已在目标车上会被拒绝。
    - **角色池可按群友筛选**：右上角下拉选某个群友（只有显示名），池子只显示他的角色，方便快速拖拽上车；换 BOSS 时自动重置为「全部群友」。
    - **车次管理**：新增（自动带上当前难度）/ 复制 / 设置（难度·序号·时间·状态）/ 删除；BOSS 的编辑与删除也在本页标题栏。
    - 卡片底部固定两行：**统计行**（`x/y 人 · 总战力 nM`）+ **操作行**（只有 **设置** / **删除**）；同一行的车次卡片**强制等高**，所以底部统计与按钮永远水平对齐。
    - **完成情况在「设置」里改**（待打 / 已完成 / 已取消），卡片上不再单放「✓ 完成」按钮，也没有「复制」。
- **战力单位**：统一用 **M（Million，百万）**，例如  = 1.85 亿战力（原来页面写的「万」是错的，已全站改正）。
- **账号 ↔ 群友 强绑定**：**每个非管理员账号都必定有一条群友记录**，「群友角色一览表」里一定会出现他
  （就算还没登记游戏角色，也先占一个位置并显示「还没登记角色」，管理员可以直接点「+ 添加角色」给他加）。
  - 新建账号时自动创建同名群友记录并绑定；改账号显示名会同步群友名。
  - 查看：系统设置的账号列表 ↔ 群友角色一览表 应当时刻一一对应（管理员账号除外）。
- **拖拽有两种语义**：
  - **拖到另一个车次的空白处** → 调车（把人挪过去；目标车满则提示）
### 融合车（合并车次）

有些 BOSS 实际是「一队人连着打完」的，要打全打、要不打都不打，所以不应该各自单独排车。
当前配置了一组：**戴米安 → 绿水灵王 → 路西德 → 威尔 → 至暗魔晶 → 觉醒希拉 → 敦凯尔**（神秘河 7 连）。

- **数据层**：`boss.combo_key` 标记同组 BOSS（迁移 `boss-combo-v1`）。
  一辆融合车在每个成员 BOSS 上**各有一条 `boss_run` 记录**（这样按 BOSS 的统计、周期汇总都不需要改动）。
- **界面层**：BOSS 列表里这 7 个合并成**一个长条大框**，里面平铺全部图标与名称；
  难度固定 **Hard**（「选择难度」步骤自动隐藏）；
  排班时前端把同一 `runIndex` 的多条记录**聚合成一张卡片**，卡片顶部显示全部 BOSS 图标。
- **操作同步**：往融合车加人 / 移除 / 调车 / 换位，都会同步到全部 7 条记录，保证「要打全打」。
  容量 6；并且沿用「同一趟车只能有一个自己的角色」的校验。
- 定义在 `domain/BossCombo.java`，要调整成员或加第二组融合车改这里即可（改完记得换迁移 marker）。

> ⚠️ 已知修复：`RunService.create` 原本漏了 `@Transactional`，
> 导致「+ 新增车次」返回 500（`LazyInitializationException: WeekSchedule#... no session`）。已补上。

  - **拖到另一个角色身上** → **两人换位**（高亮显示「⇄ 换位」）
  - 为什么需要换位：同一个 BOSS 下不可能用两个角色同时打。例如 A 车上了我的阴阳师、B 车上了我的剑豪，
    两车都满员；这时把阴阳师拖到剑豪身上，正确语义是**对调**而不是「塞进 B 车」（必然触发容量限制）。
    换位后两边人数不变，因此不受容量限制。接口：`POST /api/runs/{runId}/members/{memberId}/swap`。
  - **硬性规则：同一趟车只能有一个自己的角色。**
    同一个 BOSS 你不可能用两个角色同时打，所以服务端对**所有会改动阵容的入口**统一校验：
    `addMember`（加人）、`moveMember`（调车）、`swapMembers`（换位）、`replaceMembers`（整队保存）。
    违反时返回 400 并给出明确提示，例如：
    `「群友D」已经在这趟车上了（光枪）；同一个 BOSS 不能用两个角色同时打，同一个人同一趟车只能上一个角色`。
    前端在拖拽时就会把这类落点标成红色 `✕ 同一人不能两角色` 并禁止放下；
    「车满兜底换位」也只会挑一个不会造成冲突的对手。
    注：`replaceMembers` 的校验放在删除旧席位**之前**，校验失败不会破坏原有数据。
  - **兜底**：若拖到已满车次的空白处，会自动与车上第一个角色换位，并提示「车已满，已与 X 换位」。
- **Boss 语音频道**（**登录后可用**）：网页内直接语音，不用装客户端；3 个固定频道（语音频道 1/2/3），
  支持多个 BOSS 同时在不同频道攻坚，每频道最多 20 人。
  - **架构**：LiveKit（WebRTC SFU，单文件 Go 二进制，systemd 托管，实测内存约 42MB）。
  - **账号**：复用现有登录体系 —— 后端用 LiveKit 的 key/secret 签一个**只能进指定房间**的 token（HS256，不引额外 SDK）。
  - **信号路径**：浏览器请求 `wss://<host>:4433/lk/rtc?access_token=...` → OpenResty 的 `location /lk/` **剥掉前缀**转发到 `127.0.0.1:7880/rtc`（复用现有证书）。
    ⚠️ 注意 nginx 的 `proxy_pass http://127.0.0.1:7880/;` **结尾的斜杠必须有** —— 靠它剥前缀；少一个斜杠就会变成 `/rtc/rtc` 而 404（客户端会自动在 wsUrl 后追加 `/rtc`）。
  - **媒体端口**：UDP 50000-50200（ICE）、TCP 7881（ICE/TCP 回落）、UDP 3478 + TCP 5349（内置 TURN 中继）。
  - **切页面语音不会断**：WebRTC 连接由全局 `VoiceProvider`（挂在路由之外）持有，
    页面只负责展示与触发。若把连接放在「Boss语音频道」页面里，切到 boss排班 时组件卸载、语音立即断开。
  - **侧栏常驻语音面板**：进入频道后，左侧边栏底部会显示当前频道、在线成员、谁在说话，
    以及「静音 / 闭麦 / 退出」，任何页面都能操作。
  - **首屏体积**：`livekit-client` 约 570KB，通过 `VoiceContextBase`（纯 Context 空壳，不含 livekit）
    + 懒加载 `VoiceProvider` 拆开，主包保持约 233KB；没进语音的人不下载语音包。
    注意：改这个文件结构时**不要**让任何主包组件直接 import `VoiceContext.tsx`，否则体积会翻三倍。
  - **声音播放**：WebRTC 的远端音轨**不会自己发声**，必须 `track.attach()` 挂成 `<audio>` 元素并播放。
    浏览器还会因「没有用户手势」拦截自动播放（表现为**能看到对方说话亮灯、但听不到声音**），
    此时页面会弹出一个闪烁的 **「🔔 点这里开启声音」** 按钮，点一下即恢复（`Room.startAudio()`）。
  - **在线情况实时可见**：频道卡片上直接显示「🔊 N 人在线」+ 成员名字，**不用进房间才知道有谁**；
    页面每 5 秒自动刷新。实现：后端用管理 token 调 LiveKit 的 `RoomService/ListRooms`（拿人数）
    与 `ListParticipants`（拿名字），结果缓存 3 秒，避免多人同刷打爆 LiveKit。
  - **注意**：未改初始密码的账号只能听不能说（服务端签发 `canPublish=false`），改完密码即可开麦。
- **群友角色一览表**：表格一行一位群友，列出 **QQ号 / 显示名称 / 职业数 / 角色（职业 + 战力）**，
  点角色即可编辑（管理员）。**整体按战力从高到低排序**：每位群友的角色按战力降序，群友之间按「自己最强角色的战力」降序。
- **我的信息**（登录后的个人页，原「我的角色」）：改自己的**显示名称** + 维护自己的游戏角色。
  - **显示名称**：群里、车次上显示的就是这个名字，改完立即生效；显示名与绑定的群友名**是同一个东西**，会一起改（服务端做重名校验，账号表和群友表都不会冲突）。管理员账号不支持改名。
  - **我的游戏角色**：职业 / 战力 / 主号 / 备注（备注会在车次上悬停角色时显示）。
- **系统设置**（仅管理员）：账号管理（用户名 / 显示名 / **账号管理** / **BOSS 排班** / 密码状态）、新增与删除账号、分配权限、重置密码，以及修改自己的密码。
  - **权限只有两种**：**管理员**（可管理账号）和 **群友**。boss排班 权限由单独的「BOSS 排班」开关控制，不再用「只读 / 编辑」这套角色区分（历史数据里的 EDITOR 一律按群友处理）。
  - **不再提供「绑定群友」** —— 群友登录后在「我的车次」页面自己认领绑定，避免管理员误绑到别人身上。
- **账号权限模型**（账号管理与排班权限分离）：
  | 能力 | 谁可以用 |
  | --- | --- |
  | 新增 / 删改账号、重置密码、分配权限（**系统设置**） | **仅管理员** |
  | **boss排班**（分配 BOSS 车次与阵容、拖拽调车、一键复制、增删 BOSS/车次） | **所有群友账号**（`canSchedule`，默认开启） |
  | 标记自己车的攻坚状态 | 车上成员本人 |
  | 查看全部车次（车次一览表 / 群友角色一览表） | 所有人（含未登录） |
  - 实现方式：账号上有一个 **`canSchedule`** 开关（数据库 `app_user.can_schedule`，默认 true），登录后换取 **`ROLE_SCHEDULE`** 权限（ADMIN 天然拥有）。
  - 后端接口按能力收紧：`/api/admin/**` 仅 ADMIN；`/api/bosses/**`、`/api/groups/**`、`/api/runs/**`、`/api/weeks/**` 的写操作要 `SCHEDULE`；匿名只能读。
  - 管理员可在 **系统设置 → 编辑账号** 里给某个账号关掉「boss排班 权限」变回只读。

当前数据：**14 位群友 / 30 个角色 / 2 个分组 / 15 个 BOSS / 30 趟车**（原 Excel 的 18 人经整理后：群友K并入群友L，
扯 / Fycie / 群友S 已移除；另有 5 个 BOSS 暂无车次但保留数据，方便以后发车）。

BOSS 名称一览（国服官方中文名 + 官方立绘图标）：

> 别名词表：在 BOSS 名里写这些词都会自动归到同一条 —— 灿烂的凶星 ← 凶星。
>
> **注意**：「巴德利斯（Baldrix / 瓦尔德里克斯 / 巴尔德里克斯，俗称「人马」）」是**另一个** BOSS，
> 与「灿烂的凶星」无关；当前团队还打不了它，所以排班里没有它。
> 至暗魔晶 ← 大眼魔晶 / 格鲁姆；监视者卡尔洛斯 ← 狗；卡琳 ← 卡；最初的对抗者 ← 猴；绿水灵王 ← 绿水灵；戴米安 ← 大米。

| 分组 | BOSS | 默认难度 | 每车人数 | 车次 |
| --- | --- | --- | --- | --- |
| 原初世界 | 神选者塞伦 | Hard | 6 | 6 |
| 原初世界 | 监视者卡尔洛斯 | Extreme | 6 | 8 |
| 原初世界 | 最初的对抗者 | Normal | **3** | 8 |
| 原初世界 | 卡琳 | Extreme | 6 | 6 |
| 原初世界 | 灿烂的凶星 | Normal | **3** | 8 |
| 原初世界 | 林波 | Normal | **3** | 6 |
| 神秘河 | 斯乌 | Extreme | 6 | 6 |
| 神秘河 | 戴米安 | Hard | 6 | 2 |
| 神秘河 | 绿水灵王 | Chaos | 6 | 6 |
| 神秘河 | 老黑能量车 | — | 6 | 4 |
| 神秘河 | 路西德 | Hard | 6 | 0 |
| 神秘河 | 威尔 | Hard | 6 | 0 |
| 神秘河 | 至暗魔晶（巨大怪兽至暗魔晶） | Chaos | 6 | 0 |
| 神秘河 | 觉醒希拉 | Hard | 6 | 0 |
| 神秘河 | 敦凯尔 | Hard | 6 | 0 |

## 版本控制（重要）

工作区根目录有 git 仓库，**每次改动前先提交一次**，出问题可以立刻回滚。

```sh
cd ..                            # 回到工作区根目录
./git-helper.sh status           # 看改了哪些文件
./git-helper.sh save "改了什么"    # 提交（自动拦截密钥等敏感文件）
./git-helper.sh log              # 看历史
./git-helper.sh undo             # 丢弃未提交的改动 ← 改坏了先执行这个
./git-helper.sh rollback <提交号>  # 回退某次提交（保留历史，可再改回来）
```

**为什么要有这个**：项目早期没有版本控制，「语音音量调节」连续改坏 5 轮却无法回滚，
只能靠手工比对还原代码，风险很高。现在有基线了：

| 提交 | 说明 |
| --- | --- |
| `c3e2fde` | 版本控制基线（含融合车、按月周期、语音标识、手机适配）|

**不会入库的内容**（见根目录 `.gitignore`）：
- `.deploy/` 下的 SSH 私钥与明文密码（只放行 `ssh.sh` / `scp.sh` 两个脚本）
- `application.yml`（含数据库 / Redis 密码）—— 线上用 `/opt/gmsr/gmsr.env` 覆盖
- `node_modules` / `dist` / `target` / `.tools`（依赖与构建产物）

## 目录结构

```
gmsr/
├── backend/                 Spring Boot 3.5 + JDK 25 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/gmsr/scheduler/
│       │   ├── domain/       实体（AppUser/BossGroup/Boss/BossRun/Player/GameCharacter/RunMember）
│       │   ├── repository/   Spring Data JPA
│       │   ├── service/      业务逻辑 + 种子数据导入
│       │   ├── security/     JWT 鉴权、默认管理员初始化
│       │   ├── dto/          出入参 record
│       │   └── web/          REST 控制器与全局异常处理
│       └── resources/
│           ├── application.yml
│           ├── seed/seed-data.json     从 Excel 整理的初始数据
│           └── schema-reference.sql    表结构参考
├── frontend/                React 18 + TS + Vite
│   └── src/{api,components,pages,styles}/
└── deploy/                  部署脚本与配置
    ├── deploy.sh            一键重新部署（backend / frontend / all）
    ├── init-server.sh       服务器初始化（首次部署用）
    ├── gmsr-backend.service systemd 单元
    ├── gmsr.conf            OpenResty 站点
    └── gmsr.env.example     后端环境变量模板
```

## 服务器部署现状（<YOUR_SERVER_IP>）

| 组件 | 位置 |
| --- | --- |
| 后端 jar | `/opt/gmsr/app/gmsr-scheduler.jar` |
| 后端环境变量 | `/opt/gmsr/gmsr.env`（600，属主 gmsr） |
| 后端日志 | journald（），历史文件在  |
| systemd 服务 | `gmsr-backend.service`（已 enable，开机自启，崩溃自动重启） |
| 后端监听 | `127.0.0.1:18080`（只对本机开放，由 OpenResty 反代） |
| 前端静态文件 | `/opt/1panel/www/gmsr`（容器内 `/www/gmsr`） |
| OpenResty 站点配置 | `/opt/1panel/www/conf.d/gmsr.conf` |
| 构建目录 | `/root/gmsr-build/backend`（Maven 编译用） |
| PostgreSQL | Docker 容器 `1Panel-postgresql-2MLg`，库 `gmsr`，账号 `gmsr_app` |
| JDK / Maven | `/usr/lib/jvm/java-25-openjdk-amd64`、`/root/apache-maven-3.9.16` |

> 部署时把 1Panel 默认站点里的 `ssl_reject_handshake on` 注释掉了
> （备份在 `00.default.conf.bak-gmsr`），否则用 IP 直接访问 4433 的 HTTPS 会被拒绝握手。

## 常用运维命令

```bash
# 服务状态 / 重启 / 看日志
systemctl status gmsr-backend
systemctl restart gmsr-backend
journalctl -u gmsr-backend -f

# 改数据库连接或密钥后重启
vi /opt/gmsr/gmsr.env && systemctl restart gmsr-backend

# OpenResty 配置检查与重载
docker exec 1Panel-openresty-xjMh nginx -t
docker exec 1Panel-openresty-xjMh nginx -s reload

# 连数据库
docker exec -it 1Panel-postgresql-2MLg psql -U user_JectHy -d gmsr
```

## 重新部署

本地改动代码后（需要能免密 SSH 到服务器）：

```bash
cd gmsr/deploy
./deploy.sh backend     # 只更新后端：同步源码 → 服务器 Maven 打包 → 替换 jar → 重启
./deploy.sh frontend    # 只更新前端：本地 npm build → 上传 dist → reload OpenResty
./deploy.sh all         # 两个都更
```

## 本地开发

```bash
# 后端（需要 JDK 25 + Maven；数据库指向服务器的 54233 端口即可）
cd gmsr/backend
GMSR_DB_URL='jdbc:postgresql://<YOUR_SERVER_IP>:54233/gmsr?currentSchema=gmsr' \
GMSR_DB_USER=gmsr_app GMSR_DB_PASSWORD=CHANGE_ME_DB_PASSWORD \
mvn spring-boot:run

# 前端（vite 已把 /api 代理到 127.0.0.1:18080）
cd gmsr/frontend
npm install && npm run dev     # http://127.0.0.1:5173

# 构建产物冒烟测试（jsdom 加载真实 bundle，校验渲染 + 接口 + 无报错）
npm run build && node smoke-test.mjs http://<YOUR_SERVER_IP>:8080
```

## API 一览

所有接口以 `/api` 开头，写操作需要 `Authorization: Bearer <token>`。
权限：GET 全部开放；POST/PUT/DELETE 需要 ADMIN 或 EDITOR；`/api/admin/**` 仅 ADMIN。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回 token |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/password` | 修改自己的密码 |
| PUT | `/api/auth/bind-player` | 绑定/解绑自己的群友（登录即可用） |
| GET | `/api/health` | 健康检查 |
| GET/POST | `/api/players` | 群友列表 / 新增 |
| PUT/DELETE | `/api/players/{id}` | 修改 / 删除群友 |
| GET | `/api/players/{id}/runs?week=` | 某群友的车次安排（缺省本周） |
| GET | `/api/weeks/triples` | 上周 / 本周 / 下周 |
| GET | `/api/weeks` | 全部周期 + 当前周期 |
| GET | `/api/weeks/{weekKey}/runs` | 某周期的车次 |
| POST | `/api/weeks/copy` | 一键复制整周排班（fromWeekKey / toWeekKey / overwrite） |
| GET | `/api/voice/rooms` | 语音频道列表（含是否已开通、信令地址） |
| POST | `/api/voice/rooms/{roomId}/token` | 换取进房 token（复用登录态，只能进指定房间） |
| GET | `/api/weeks/run-counts` | 上周/本周/下周 各 BOSS 的趟数（weekKey → bossId → 趟数） |
| POST | `/api/weeks/copy-boss` | 一键复制**单个 BOSS + 指定难度**的排班（fromWeekKey / toWeekKey / bossId / tier / overwrite） |
| GET/POST | `/api/characters` | 角色列表 / 新增（管理员） |
| GET/POST | `/api/me/characters` | 我的角色：查看 / 新增（本人，需登录） |
| PUT/DELETE | `/api/me/characters/{id}` | 我的角色：修改 / 删除（仅限自己的角色） |
| PUT/DELETE | `/api/characters/{id}` | 修改 / 删除角色 |
| GET/POST | `/api/boss-groups` | 分组列表 / 新增 |
| PUT/DELETE | `/api/boss-groups/{id}` | 修改 / 删除分组 |
| GET/POST | `/api/bosses` | BOSS 列表 / 新增 |
| PUT/DELETE | `/api/bosses/{id}` | 修改 / 删除 BOSS |
| GET | `/api/bosses/{id}/runs` | 某 BOSS 的车次 |
| GET/POST | `/api/runs` | 车次列表 / 新增 |
| GET/PUT/DELETE | `/api/runs/{id}` | 车次详情 / 修改 / 删除 |
| PUT | `/api/runs/{id}/status` | 标记攻坚状态（车上成员或管理员/编辑） |
| GET | `/api/runs/{id}/my-members` | 当前账号在这趟车上的角色 |
| POST | `/api/runs/{id}/copy` | 复制车次 |
| POST | `/api/runs/{id}/members` | 添加一个席位 |
| PUT | `/api/runs/{id}/members` | 整队保存（整体替换成员） |
| PUT/DELETE | `/api/runs/{id}/members/{memberId}` | 修改 / 移除席位 |
| POST | `/api/runs/{id}/members/{memberId}/move` | 拖拽调车：把席位挪到 `targetRunId` 指定的车次 |
| GET/POST | `/api/admin/users` | 账号列表 / 新增（仅 ADMIN） |
| PUT/DELETE | `/api/admin/users/{id}` | 修改 / 删除账号（仅 ADMIN） |
| POST | `/api/admin/users/{id}/reset-password` | 把群友密码重置为 CHANGE_ME_PLAYER_PASSWORD 并要求其重改（仅 ADMIN） |

## 数据模型说明

- **群友（player）** 是人，带 **QQ 号**（登录账号）与**显示名称**；**角色（character）** 是某个群友的一个「职业 + 战力」组合，一个人可以挂多个角色。
- **BOSS（boss）** 属于某个**分组（boss_group）**，带默认难度和**每车人数上限（capacity）**。
  **三人本：最初的对抗者、灿烂的凶星、林波**（capacity=3），其余 BOSS 均为 6 人；人数上限在 boss排班 的「编辑 BOSS」里可改。
- **车次（boss_run）** 是某个 BOSS 的一次组队，有独立难度（默认继承 BOSS）、序号、时间、状态。
- **席位（run_member）** 把某个角色放到某趟车上，同一角色同一趟车只能出现一次（数据库唯一约束），
  上车时会校验不能超过 BOSS 的人数上限。
- **账号（app_user）** 可选绑定一个群友（`player_id`）；绑定后这个账号就能标记自己所在车次的攻坚状态。
- 车次状态：`PLANNED`（未完成）/ `DONE`（已完成）/ `CANCELED`（已取消），车上任意成员都能来回切换，谁先打完谁点。
- 时间安排 `scheduled` 是自由文本（例如「周五 21:00」），当前版本按单周排班表使用；
  如果需要「按周/按日期」多套排班，后续可以加一张排班周期表把 `boss_run` 挂上去。

## 待办 / 可扩展方向

- 多周排班（每周一份名单，可复制上一周）。
- 席位拖拽排序（目前是「上人 / 整队 / 移除」按钮式操作）。
- 标记完成时记录「谁标的、什么时候标的」（现在只存状态）。
- 按战力校验某个 BOSS 是否达标（Excel 里只记录了战力，没有登记各 BOSS 的门槛）。
- 导出当前排班为 Excel / 图片分享。

## 安全设计（对外正式环境）

| 措施 | 说明 |
| --- | --- |
| 密码存储 | BCrypt 加盐哈希（cost 10），接口与页面从不返回密码或哈希 |
| 登录防爆破 | 单账号连续失败 8 次锁定 15 分钟；单来源 IP 30 次锁定 15 分钟（阈值可用环境变量调整）；失败提示统一为「用户名或密码错误」，不暴露账号是否存在 |
| 首次登录强制改密 | 账号带 `must_change_password` 标记，前端弹窗拦截不可关闭；**数据库触发器**保证「只清标记不改密码」会被拒绝 |
| 密码重置 | 管理员重置后接口只返回提示，**不回显密码**；重置同时把标记置回，本人下次登录必须再改 |
| 权限 | 读接口匿名可访问；编排类写操作仅 ADMIN / EDITOR；群友只能标记自己所在车次的状态；账号管理仅 ADMIN |
| 登录态 | JWT 无状态，默认 168 小时过期，签名密钥通过 `GMSR_JWT_SECRET` 注入 |
| 界面 | 按 1920×1080 优化：基底字号 15px、卡片内边距 22px、栅格间隔 18px、侧栏 252px；1366 及以下窄屏自动收紧 |
| 响应头 | OpenResty 统一加 CSP、X-Frame-Options、X-Content-Type-Options、Referrer-Policy、Permissions-Policy |
| 网络暴露 | 后端只监听 `127.0.0.1:18080`，外部只能经 OpenResty 的 8080/4433 访问 |
| 页面信息 | 前端不出现默认密码、初始密码等字样（已扫描构建产物确认） |

### 上线前的建议动作

1. 登录 `admin` 后立即改掉管理员密码（初始密码仍为 `CHANGE_ME_ADMIN_PASSWORD`）。
2. 把 `/opt/gmsr/gmsr.env` 里的 `GMSR_JWT_SECRET` 换成新的随机串并重启服务。
3. 群友初始密码逐个私聊告知，不要发在群里；提醒他们登录后立即设置自己的密码。
4. `/api/players` 目前是所有登录/未登录用户都能读到 QQ 号（前端对非管理员做了脱敏显示）。
   若要彻底不对外暴露，可以把该接口收进鉴权或在服务端直接脱敏——需要的话告我。

### 相关环境变量

```bash
GMSR_PLAYER_PASSWORD=CHANGE_ME_PLAYER_PASSWORD      # 群友初始密码（重置后的密码），仅后端使用
GMSR_JWT_SECRET=...                # JWT 签名密钥
GMSR_LOGIN_MAX_ATTEMPTS=8          # 单账号失败锁定阈值
GMSR_LOGIN_MAX_ATTEMPTS_PER_IP=30  # 单 IP 失败锁定阈值
GMSR_LOGIN_LOCK_MINUTES=15         # 锁定时长（分钟）
```
