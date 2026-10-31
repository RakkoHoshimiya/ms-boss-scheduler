/**
 * 更新记录（更新日志）。
 *
 * 这里只写**用户能感知的变化**，一条一句话；技术细节见仓库里的 CODE_REVIEW.md。
 * 新增记录时放在数组最前面（最新在上），并保持 `type` 分类准确。
 *
 * 分组按**日期**排列（同一天的改动合并成一组），从新到旧。
 * 日期依据 git 提交历史与文件时间戳，不是随手写的。
 */

export type ChangeType = 'feature' | 'fix' | 'improve' | 'security';

export interface ChangeEntry {
  /** 分类 */
  type: ChangeType;
  /** 一句话说明 */
  text: string;
}

export interface ChangeGroup {
  /** 日期（MM/DD）或阶段标识 */
  date: string;
  /** 分组小标题 */
  title: string;
  entries: ChangeEntry[];
}

export const CHANGE_TYPE_LABEL: Record<ChangeType, string> = {
  feature: '新功能',
  fix: '修复',
  improve: '优化',
  security: '安全',
};

export const CHANGE_LOG: ChangeGroup[] = [
  {
    date: '10/31',
    title: '并发与令牌边界加固（第二轮评审）',
    entries: [
      { type: 'security', text: '刷新与改密撤销通过「会话版本」协调：refresh token 里带签发时的版本号，刷新时锁账号行比对，版本不符直接拒绝——杜绝「刷新先消费、撤销后执行、刷新又签出新会话」' },
      { type: 'security', text: '签发 refresh token 改为 Redis Lua 原子写入（token 与账号索引一起写）；Redis 不可用时明确失败并返回 503，不再返回「看似成功但没存下」的会话' },
      { type: 'fix', text: '移动 / 换位在加锁后**重新读取席位**并确认仍在预期车次，过期时返回冲突让前端刷新，避免用旧的源车次做校验' },
      { type: 'fix', text: '每周重置的多实例互斥改用 PostgreSQL 事务级咨询锁（随事务自动释放），不再依赖固定值 + TTL 的 Redis 锁' },
      { type: 'fix', text: '仓库补上 deploy/livekit-rtc.inc，避免按仓库配置新建环境时 Nginx 因 include 缺失而启动失败' },
    ],
  },
  {
    date: '10/31',
    title: '服务端安全与并发加固',
    entries: [
      { type: 'security', text: '明文 HTTP（8080）不再接受任何 API 请求（含登录），返回 403；页面仍会 301 跳到 HTTPS，根证书下载与安装说明保留 HTTP 入口' },
      { type: 'security', text: '「首次登录必须改密」改为**服务端强制**：待改密账号只能查自己、改密码、登出，其余接口一律 403（以前只靠前端弹窗，可以被绕开）' },
      { type: 'security', text: 'refresh token 改为原子消费（Redis GETDEL），并发刷新只有一次能成功，重放立即失效' },
      { type: 'security', text: '管理员在「编辑账号」里改密码后，会同时撤销该账号已有会话；旧 access / refresh token 立即失效' },
      { type: 'fix', text: '上人 / 挪人 / 换位 / 整队保存改为对车次加行锁，避免并发时超员或同一群友占两个席位' },
      { type: 'fix', text: '复制车次改为单事务，中途失败不会留下「半截车次」' },
      { type: 'fix', text: '每周重置改为显式事务 + 分布式锁，避免「改了状态没写标记」导致重复重置，也为多实例部署留好互斥' },
    ],
  },
  {
    date: '10/30',
    title: '完成状态每周自动重置',
    entries: [
      { type: 'feature', text: '车次的「已完成」标记改为**每周四 08:00 自动重置为未完成**（排班本身仍是固定一份，只有打没打完这层状态每周归零）' },
      { type: 'improve', text: '如果周四 08:00 时服务没在运行，下次启动会自动补一次重置，不会漏' },
      { type: 'improve', text: '「车次一览表」顶部标明：完成状态每周四 08:00 自动重置' },
    ],
  },
  {
    date: '10/22',
    title: '取消按周排班，改为固定排班',
    entries: [
      { type: 'improve', text: '取消「上周 / 本周 / 下周」机制，改为固定排班：人员几乎不变，排班不再按周分组，打开就是一份长期有效的安排' },
      { type: 'improve', text: 'BOSS 排班由四步简化为三步：BOSS → 难度 → 排班（去掉了「选择周期」这一步）' },
      { type: 'improve', text: '移除「一键复制上周排班」和周期切换器；「车次一览表」「我的车次」顶部的周期下拉也一并去掉' },
      { type: 'fix', text: '打开 BOSS 排班时自动选中「真的排过车」的难度（原来固定选清单里第一个，导致常见难度显示 0 趟）' },
      { type: 'fix', text: '修复不同难度的同序号车次被渲染成同一张卡的显示错误' },
      { type: 'fix', text: '修复手机上点了「电脑版」后再也切不回手机版的问题：切换按钮原本长在手机顶栏里，而电脑版会隐藏顶栏；现在电脑版左下角常驻一个「📱 手机版」按钮' },
      { type: 'fix', text: '「阴阳师」和「Kanna」是同一个职业，却被当成两个角色重复登记（群友G同时有 Kanna 160M 与阴阳师 160M）。已合并去重，并让系统把两者视为同一职业：同一群友不能再登记两个同职业角色' },
      { type: 'fix', text: '修正「群友角色一览表」把「无权查看 QQ」显示成「未登记」的问题（还会错误显示「N 位还没登记 QQ 号」）；现在分三种情况显示：完整 QQ / 仅管理员可见 / 未登记，且每个人都能看到自己的 QQ 号' },
      { type: 'improve', text: '「编辑 BOSS」的难度勾选现在只允许选择游戏里真实存在的难度：如「监视者卡尔洛斯」没有 Hard，该项会置灰并标注「游戏无此难度」，服务端也会拒绝存入' },
      { type: 'fix', text: '修复在 BOSS 详情页点「编辑」没反应、要退回 BOSS 列表才弹出编辑框的问题' },
      { type: 'improve', text: '编辑排班时「待选角色池」改为按战力从高到低排序（同战力按群友昵称、职业稳定排列），拖车上人时高战力角色排在前面' },
      { type: 'fix', text: '修复复制融合车时报「BOSS 不存在：-12」：融合车在界面上用的是合成 id，复制时误把它发给了后端；现在改用真实的主 BOSS id' },
      { type: 'fix', text: '更正「监视者卡尔洛斯」的难度清单：它没有 Hard，只有 Easy / Normal / Chaos / Extreme（代码默认值与 README 一并修正）' },
    ],
  },
  {
    date: '10/18',
    title: '三人本容量与 BOSS 名称修正',
    entries: [
      { type: 'fix', text: '修正三人本 BOSS 的容量：「最初的对抗者 / 灿烂的凶星 / 林波」最多只能 3 人，原来「最初的对抗者」被误设为 6 人（界面显示 6 个位置、能塞进第 4 个人）' },
      { type: 'fix', text: '种子数据同步修正：新部署的这三个 BOSS 也直接是 3 人容量' },
      { type: 'fix', text: '纠正 BOSS 名称混淆：「灿烂的凶星」不再被当作「人马」—— 人马其实是另一个 BOSS 巴德利斯（我们暂时还打不了它）' },
    ],
  },
  {
    date: '10/17',
    title: '数据修复与文档',
    entries: [
      { type: 'fix', text: '修复「系统设置里有账号、群友角色一览表却查不到他」：未绑定群友的账号现在启动时自动补建群友并绑定（没角色的也会显示「还没登记角色」占位）' },
      { type: 'improve', text: '更新记录改为按日期排列，同一天的改动合并展示' },
      { type: 'fix', text: '修复排班里「把一个角色拖到另一个角色身上」换位完全没反应的问题（拖到空位调车一直是正常的）' },
      { type: 'improve', text: '「我的车次」排序优化：没打的排前面、打完了的沉到后面，并加一条「以下 N 趟已完成」分隔线' },
      { type: 'feature', text: '「我的车次」新增按角色、按 BOSS 的快速筛选下拉框（可叠加，只列本周真的排到的）' },
    ],
  },
  {
    date: '10/16',
    title: '语音音量与状态标识',
    entries: [
      { type: 'feature', text: '语音新增音量调节：每个队友可单独调 0~150%（声音小的队友能放大），自己麦克风可调 0~200%，都只影响自己的听感' },
      { type: 'improve', text: '音量调节改用 LiveKit 内置的 WebAudio 增益链路并主动解锁 AudioContext（这是成熟语音软件 Discord / KOOK 的通用做法，也是之前「调了没效果」的根因）' },
      { type: 'improve', text: '麦克风按钮改为显示当前状态「开麦中 / 闭麦中」，静音按钮改为「收音中 / 已静音」，点击即切换（原来显示的是动作，看不出当前状态）' },
      { type: 'improve', text: '语音成员的闭麦 / 静音标识改用 SVG：闭麦=麦克风带斜杠、静音=耳机带斜杠（原来两个都是喇叭形状，分不清）' },
      { type: 'fix', text: '修复麦克风增益初始化失败会导致「进频道后要手动点开麦」的问题：失败时自动退回普通音轨，保证麦克风一定能开' },
      { type: 'improve', text: '去掉侧栏菜单里每项下方的小字说明，并精简底部为「名称 + 退出」' },
      { type: 'improve', text: '新增版本控制与回滚脚本，改坏了可以一条命令还原' },
      { type: 'improve', text: '新增自动化验证：增益逻辑 34 项、音频渲染测量 10 项、手机版 8 项、WebRTC 检测 4 场景' },
    ],
  },
  {
    date: '10/15',
    title: '融合车与复制修复',
    entries: [
      { type: 'fix', text: '修复复制融合车时漏掉其余 6 个成员 BOSS 的问题（原来只复制主 BOSS，导致 7 个 BOSS 趟数与阵容不一致）' },
      { type: 'fix', text: '修复「我的车次」把一趟融合车显示成 7 张卡、要连点 7 次完成的问题' },
      { type: 'improve', text: '融合车合并规则抽成公共模块，boss排班 / 车次一览表 / 我的车次 三处口径统一，不再各自实现' },
    ],
  },
  {
    date: '10/14',
    title: '手机版与音质',
    entries: [
      { type: 'feature', text: '手机版适配：按请求头识别设备，手机上改用点选方式排班（手机不支持拖拽），可用顶栏「电脑版」按钮手动切换' },
      { type: 'improve', text: '手机版车次一览表改为一趟车一张卡片竖排展示，筛选栏吸顶固定（桌面版保持表格不变）' },
      { type: 'improve', text: '手机上进入语音前先做浏览器能力检测：内置浏览器屏蔽 WebRTC / 没装证书 / 浏览器太旧，都会给出明确原因与解决办法' },
      { type: 'improve', text: '语音采集开启回声消除 / 降噪 / 自动增益，多人同时说话时音质更稳' },
    ],
  },
  {
    date: '10/13',
    title: '鉴权、融合车与界面骨架',
    entries: [
      { type: 'security', text: '登录鉴权改用 SA-Token 框架，会话统一存 Redis' },
      { type: 'feature', text: '新增双 token 无感刷新：access token 2 小时自动续期，7 天没用系统才要求重新登录' },
      { type: 'security', text: '改密码 / 重置密码 / 删除账号时，强制该账号所有设备重新登录' },
      { type: 'security', text: '修复匿名可读取全部群友 QQ（登录账号）的漏洞，现在只有管理员可见' },
      { type: 'security', text: '移除自助绑定群友的接口（原来任何登录用户都能认领别人的群友身份）' },
      { type: 'fix', text: '去掉多余的「选择你的群友昵称」绑定下拉框：账号本就按 QQ 自动绑定，让用户再选一次既没必要、选错还会标记到别人车上' },
      { type: 'feature', text: '新增融合车：戴米安 → 绿水灵王 → 路西德 → 威尔 → 至暗魔晶 → 觉醒希拉 → 敦凯尔 合并成一个车次，要打全打' },
      { type: 'improve', text: 'BOSS 列表里融合车显示为一个长条大框，平铺全部 BOSS 图标与名称' },
      { type: 'improve', text: '融合车难度固定 Hard，不再需要选难度' },
      { type: 'fix', text: '修复融合车出现「第 8 趟 / 第 9 趟」幻影车次的问题' },
      { type: 'fix', text: '修复融合车各 BOSS 阵容不同步、调车/换位/移除只生效一个 BOSS 的问题' },
      { type: 'fix', text: '修复融合车卡片互相重叠、趟数显示成 21 趟的问题' },
      { type: 'improve', text: '车次一览表里融合车也合并成一个条目展示' },
      { type: 'security', text: '同一趟车只能有一个自己的角色（防止同一 BOSS 用两个角色同时打）' },
      { type: 'improve', text: '左侧菜单导航 + 卡片式页面布局' },
      { type: 'improve', text: 'BOSS 排班改为四层流程：BOSS → 难度 → 周期 → 排班' },
      { type: 'improve', text: '「排班管理」并入「boss排班」，旧地址自动跳转' },
      { type: 'improve', text: '每个 BOSS 配置了官方图标与专属难度清单' },
      { type: 'improve', text: '战力单位统一为 M（Million）' },
      { type: 'improve', text: '周期标签显示当前 BOSS + 难度的真实趟数' },
      { type: 'improve', text: '鼠标悬停角色可看到备注与主号标记' },
      { type: 'feature', text: '新增更新记录页面：把每次改动整理成一句话，方便回看' },
      { type: 'feature', text: '新增数据完整性校验脚本，发版前可一键检查排班数据是否自相矛盾' },
      { type: 'improve', text: '启动时自动检查 Redis 与默认密码，有问题直接在日志里告警' },
      { type: 'fix', text: '修复「新增车次」报 500 的问题' },
      { type: 'fix', text: '修复按月 BOSS 新建车次报「排班周期格式不正确」的问题' },
      { type: 'feature', text: '老黑能量车改为按月刷新：排班周期改成 上月 / 本月 / 下月（它一个月才刷一次，不是周四 CD）' },
    ],
  },
  {
    date: '10/12',
    title: '语音频道与排班交互',
    entries: [
      { type: 'feature', text: '新增「Boss语音频道」菜单：网页内直接语音，不用装客户端' },
      { type: 'feature', text: '支持 3 个频道同时开打，各自独立互不干扰' },
      { type: 'feature', text: '频道卡片直接显示在线人数与成员名字，不用进房间才知道谁在' },
      { type: 'feature', text: '进入频道后左侧边栏常驻语音面板，切到任何页面语音都不会断' },
      { type: 'feature', text: '控制拆成三个按钮：静音（不听队友）、闭麦（队友听不到你）、退出' },
      { type: 'improve', text: '浏览器拦截自动播放时，页面会显示「点这里开启声音」提示按钮' },
      { type: 'improve', text: '语音代码按需加载，没进过语音的人不用下载 570KB 的库' },
      { type: 'feature', text: '排班页支持拖拽调车：把角色拖到另一趟车即可移动，拖到别人身上即换位' },
      { type: 'improve', text: '融合车拖拽会同步全部 7 个 BOSS 的阵容，不会只动一个' },
      { type: 'fix', text: '修复拖拽后阵容不同步、需要手动刷新页面的问题' },
      { type: 'improve', text: '删除车次/角色时会提示影响范围，避免误删整趟车' },
      { type: 'feature', text: '新增「一键复制上周排班」：新周期可以从上周直接套用' },
      { type: 'improve', text: '管理员可以给账号设置「可排班」权限，只有授权的人能改车次' },
      { type: 'security', text: '群友账号只能改自己的角色，看不到别人的 QQ' },
      { type: 'improve', text: '新增「我的信息」页：群友自助维护显示名称与角色（职业 + 战力）' },
    ],
  },
  {
    date: '10/11',
    title: '系统搭建',
    entries: [
      { type: 'feature', text: '从「gmsr排班表.xlsx」导入全部初始数据（BOSS、群友、角色、车次、阵容）' },
      { type: 'feature', text: '后端 Java SpringBoot 3 + PostgreSQL，前端 React，由 1Panel OpenResty 托管' },
      { type: 'feature', text: '周期按「周四 ~ 下周三」划分，支持上周 / 本周 / 下周' },
      { type: 'feature', text: '车次一览表、我的车次、群友角色一览表等页面' },
      { type: 'feature', text: '账号密码登录，群友账号可自助修改密码' },
      { type: 'security', text: '站点强制 HTTPS，并提供 CA 证书安装与检测页（自签证书浏览器需先信任）' },
    ],
  },
];
