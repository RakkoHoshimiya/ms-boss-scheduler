package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppSetting;
import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossCombo;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.BossTierCatalog;
import com.gmsr.scheduler.domain.JobCatalog;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
import com.gmsr.scheduler.repository.AppSettingRepository;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import java.time.LocalDate;
import com.gmsr.scheduler.repository.RunMemberRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性数据整理（幂等，可反复执行）：
 * 1. 按 GMSR 实际规则修正 BOSS 组队人数上限（猴 / 凶星 / 林波 = 3 人，其余 6 人）
 * 2. 修正从 Excel 解析时串行的 BOSS 名（H敦凯尔）
 * 3. 合并「群友O」到「群友Y」
 * 4. 删除已退出群友（扯 / 群友Z / 群友W）及其账号，并清掉他们占用的席位
 * 5. 按 QQ 对照表写入 QQ 号与显示名称
 * 6. 登录账号统一改成 QQ 号，初始密码 CHANGE_ME_PLAYER_PASSWORD，并强制首次登录修改密码
 */
@Component
@Order(2)
public class DataMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrator.class);

    /**
     * 一次性历史迁移数据：QQ 号 -> 群友昵称。
     *
     * 开源版本里是**示例值**（真实 QQ 与昵称已移除）。
     * 迁移执行一次即被 marker 标记；换环境时把下面几行换成自己的数据。
     */
    private static final Map<String, String> QQ_TO_NAME = new LinkedHashMap<>();

    /** 旧昵称 -> 新昵称（群友改名后仍能定位）。开源版留空即可。 */
    private static final Map<String, String> RENAMES = new LinkedHashMap<>();

    static {
        // 示例数据
        QQ_TO_NAME.put("100000001", "群友1");
        QQ_TO_NAME.put("100000002", "群友2");
        QQ_TO_NAME.put("100000003", "群友3");
    }

    /** 难度前缀 -> 难度枚举（H=Hard / N=Normal / C=Chaos / E=Extreme / EX=Extreme） */
    private static final Map<String, String> PREFIX_TIERS = new LinkedHashMap<>();

    static {
        PREFIX_TIERS.put("EX", "EX");
        PREFIX_TIERS.put("H", "H");
        PREFIX_TIERS.put("N", "N");
        PREFIX_TIERS.put("C", "C");
        PREFIX_TIERS.put("E", "E");
        // 中文难度前缀（防止有人按中文写名字）
        PREFIX_TIERS.put("困难", "H");
        PREFIX_TIERS.put("普通", "N");
        PREFIX_TIERS.put("混沌", "C");
        PREFIX_TIERS.put("极限", "E");
        PREFIX_TIERS.put("简单", "E");
    }
    /**
     * BOSS 中文名与头像映射（依据冒险岛国服官方名称，图标取自 mxd.dvg.cn 官方立绘）。
     * 键为「去难度前缀后的名字」，值是最终要显示的中文名 + 图标文件名。
     */
    private static final Map<String, BossMeta> BOSS_META = new LinkedHashMap<>();

    static {
        // 原初之力
        BOSS_META.put("赛伦", new BossMeta("神选者塞伦", "seren"));
        BOSS_META.put("狗", new BossMeta("监视者卡尔洛斯", "kalos"));
        BOSS_META.put("卡洛斯", new BossMeta("监视者卡尔洛斯", "kalos"));
        BOSS_META.put("卡", new BossMeta("卡琳", "kaling"));
        BOSS_META.put("卡琳", new BossMeta("卡琳", "kaling"));
        BOSS_META.put("猴", new BossMeta("最初的对抗者", "adversary"));
        BOSS_META.put("最初的对抗者", new BossMeta("最初的对抗者", "adversary"));
        // 灿烂的凶星：黑海·迷惑神殿的新 BOSS（280 级，最多 3 人）
        // 注意：不要把它和「巴德利斯（Baldrix / 人马）」混为一谈 —— 那是另一个 BOSS，
        // 当前团队还打不了，排班里没有它，所以这里也没有它的别名映射。
        BOSS_META.put("凶星", new BossMeta("灿烂的凶星", "evilstar"));
        BOSS_META.put("灿烂的凶星", new BossMeta("灿烂的凶星", "evilstar"));
        BOSS_META.put("林波", new BossMeta("林波", "limbo"));
        // 神秘河
        BOSS_META.put("斯乌", new BossMeta("斯乌", "lotus"));
        BOSS_META.put("大米", new BossMeta("戴米安", "damien"));
        BOSS_META.put("戴米安", new BossMeta("戴米安", "damien"));
        BOSS_META.put("绿水灵", new BossMeta("绿水灵王", "slimeking"));
        BOSS_META.put("绿水灵王", new BossMeta("绿水灵王", "slimeking"));
        BOSS_META.put("路西德", new BossMeta("路西德", "lucid"));
        BOSS_META.put("威尔", new BossMeta("威尔", "will"));
        BOSS_META.put("大眼", new BossMeta("格鲁姆", "gloom"));
        BOSS_META.put("格鲁姆", new BossMeta("至暗魔晶", "gloom"));
        BOSS_META.put("至暗魔晶", new BossMeta("至暗魔晶", "gloom"));
        BOSS_META.put("大眼魔晶", new BossMeta("至暗魔晶", "gloom"));
        BOSS_META.put("老女人", new BossMeta("觉醒希拉", "hilla"));
        BOSS_META.put("觉醒希拉", new BossMeta("觉醒希拉", "hilla"));
        BOSS_META.put("敦凯尔", new BossMeta("敦凯尔", "darknell"));
        // 特殊
        BOSS_META.put("老黑能量车", new BossMeta("老黑能量车", "blackmage"));
    }

    /** BOSS 展示名与图标。 */
    private record BossMeta(String displayName, String iconKey) {
    }

    /**
     * 每个 BOSS 实际存在的难度（依据国服官方 v226《BOSS·血量》表）。
     * 顺序固定为 Easy / Normal / Hard / Chaos / Extreme。
     * 逗号后面的第一个是默认难度。
     */
    /**
     * 每个 BOSS 的难度清单，直接取自 {@link BossTierCatalog}。
     *
     * 这里以前自己写了一份，结果和数据库对不上（卡尔洛斯代码里有 Hard、库里没有），
     * 现在统一以 BossTierCatalog 为唯一来源。
     */
    private static final Map<String, String> BOSS_TIERS = buildBossTiers();

    private static Map<String, String> buildBossTiers() {
        Map<String, String> result = new LinkedHashMap<>();
        BossTierCatalog.all().forEach((name, tiers) -> result.put(name, String.join(",", tiers)));
        return result;
    }

    /** 默认难度（每个 BOSS 的 tiers 里的第一个可用中文名登记的难度）。 */
    private static final Map<String, String> BOSS_DEFAULT_TIER = Map.ofEntries(
            Map.entry("神选者塞伦", "H"),
            Map.entry("监视者卡尔洛斯", "EX"),
            Map.entry("最初的对抗者", "N"),
            Map.entry("卡琳", "EX"),
            Map.entry("灿烂的凶星", "N"),
            Map.entry("林波", "N"),
            Map.entry("斯乌", "EX"),
            Map.entry("戴米安", "H"),
            Map.entry("绿水灵王", "C"),
            Map.entry("老黑能量车", "EX"),
            Map.entry("路西德", "H"),
            Map.entry("威尔", "H"),
            Map.entry("至暗魔晶", "C"),
            Map.entry("觉醒希拉", "H"),
            Map.entry("敦凯尔", "H"));

    /** 需要恢复的空 BOSS（暂无车次，但保留数据以便以后发车）。name / iconKey / tier */
    private static final List<String[]> RESTORE_BOSSES = List.of(
            new String[] {"路西德", "lucid", "H"},
            new String[] {"威尔", "will", "H"},
            new String[] {"至暗魔晶", "gloom", "C"},
            new String[] {"觉醒希拉", "hilla", "H"},
            new String[] {"敦凯尔", "darknell", "H"});

    /** 按游戏规则需要限制为 3 人的 BOSS */
    private static final List<String> THREE_MAN_BOSSES = List.of("猴", "凶星", "灿烂的凶星", "林波");
    /** 已退出群友，直接删除 */
    private static final List<String> REMOVED_PLAYERS = List.of("群友K", "群友Z", "群友W");
    private static final String ACCOUNT_MARKER = "qq-accounts-v3";
    private static final String BOSS_ICON_MARKER = "boss-icons-v2";
    private static final String BOSS_NAME_MARKER = "boss-names-v2";
    private static final String BOSS_TIER_MARKER = "boss-tiers-v1";
    private static final String BOSS_COMBO_MARKER = "boss-combo-v1";
    private static final String BOSS_CAPACITY_3PLAYER_MARKER = "boss-capacity-3player-v1";
    private static final String BOSS_ICON_KEY_EVILSTAR_MARKER = "boss-icon-evilstar-v1";
    private static final String SCHEDULE_MARKER = "schedule-permission-v1";
    private static final String GROUP_RENAME_MARKER = "group-rename-v1";
    private static final String SYNC_PLAYER_MARKER = "sync-player-account-v1";
    private static final String MERGE_FROM = "群友O";
    private static final String MERGE_TO = "群友Y";

    private final BossGroupRepository groupRepository;
    private final BossRepository bossRepository;
    private final BossRunRepository runRepository;
    private final PlayerRepository playerRepository;
    private final GameCharacterRepository characterRepository;
    private final RunMemberRepository memberRepository;
    private final AppUserRepository userRepository;
    private final AppSettingRepository settingRepository;
    private final PasswordEncoder passwordEncoder;
    private final String defaultPassword;

    public DataMigrator(
            BossGroupRepository groupRepository,
            BossRepository bossRepository,
            BossRunRepository runRepository,
            PlayerRepository playerRepository,
            GameCharacterRepository characterRepository,
            RunMemberRepository memberRepository,
            AppUserRepository userRepository,
            AppSettingRepository settingRepository,
            PasswordEncoder passwordEncoder,
            @Value("${gmsr.player.default-password:CHANGE_ME_PLAYER_PASSWORD}") String defaultPassword) {
        this.groupRepository = groupRepository;
        this.bossRepository = bossRepository;
        this.runRepository = runRepository;
        this.playerRepository = playerRepository;
        this.characterRepository = characterRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.settingRepository = settingRepository;
        this.passwordEncoder = passwordEncoder;
        this.defaultPassword = defaultPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        cleanBossNames();
        assignBossIcons();
        dedupeBosses();
        fixBossCapacity();
        fixBossNames();
        mergePlayers();
        removePlayers();
        applyQqAndNames();
        syncPlayersWithAccounts();
        renameGroups();
        grantSchedulePermission();
        migrateBossTiers();
        migrateBossCombos();
        fixThreePlayerBossCapacity();
        renameEvilStarIconKey();
        normalizeCharacterJobs();
        restoreBosses();
        checkBossIconFiles();
        if (!markerExists(BOSS_NAME_MARKER)) {
            syncBossNamesAndIcons();
            saveMarker(BOSS_NAME_MARKER, "done");
        }
        if (!markerExists(BOSS_ICON_MARKER)) {
            syncBossIcons();
            saveMarker(BOSS_ICON_MARKER, "done");
        }
        if (!markerExists(ACCOUNT_MARKER)) {
            rebuildAccounts();
            saveMarker(ACCOUNT_MARKER, "done");
        }
        // 每次启动都补绑（幂等）：把还没绑定群友的账号补上，
        // 否则这类账号在「群友角色一览表」里完全看不到（连空行都没有）。
        bindOrphanAccounts();
    }

    /**
     * 给还没绑定群友的账号补一条群友记录并绑定。
     *
     * 背景：ensurePlayerFor 只在「新建账号」时执行，之前建的账号不会被补绑。
     * 结果就是：系统设置里能看到这个账号，但「群友角色一览表」里找不到他
     * （一览表是按群友列出的，没有群友记录就不显示）。
     *
     * 这里在每次启动时兜底：账号没有 player 就建一条同名群友（QQ 用账号名）并绑定。
     * 幂等 —— 已绑定的账号不受影响。
     */
    private void bindOrphanAccounts() {
        List<com.gmsr.scheduler.domain.AppUser> all = userRepository.findAll();
        int bound = 0;
        for (com.gmsr.scheduler.domain.AppUser user : all) {
            if (user.getPlayer() != null) {
                continue;
            }
            // 管理员不参与排班，不需要群友记录
            if (user.getRole() == com.gmsr.scheduler.domain.AppUser.Role.ADMIN) {
                continue;
            }
            String nickname = user.getDisplayName() == null || user.getDisplayName().isBlank()
                    ? user.getUsername()
                    : user.getDisplayName().trim();
            com.gmsr.scheduler.domain.Player player = playerRepository.findByNickname(nickname)
                    .orElseGet(() -> {
                        com.gmsr.scheduler.domain.Player created = new com.gmsr.scheduler.domain.Player();
                        created.setNickname(nickname);
                        created.setQq(user.getUsername());
                        created.setActive(true);
                        created.setSortOrder(nextPlayerSortOrder());
                        return playerRepository.save(created);
                    });
            user.setPlayer(player);
            userRepository.save(user);
            bound++;
            log.info("账号 {} 未绑定群友，已自动补绑到「{}」", user.getUsername(), player.getNickname());
        }
        if (bound > 0) {
            log.info("补绑账号完成：{} 个", bound);
        }
    }

    /** 取下一个群友排序号。 */
    private int nextPlayerSortOrder() {
        return playerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .mapToInt(com.gmsr.scheduler.domain.Player::getSortOrder)
                .max()
                .orElse(0) + 1;
    }

    /** 0.1 去掉 BOSS 名称里的难度前缀，并把难度写进 tier 字段（名称只保留 BOSS 本身）。 */
    private void cleanBossNames() {
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            String original = boss.getName();
            String baseName = resolveBaseName(original);
            String tier = resolveTier(original, boss.getTier().name());
            if (baseName == null || baseName.equals(original)) {
                continue;
            }
            log.info("BOSS 名称规范化：{} -> {}，难度 {}", original, baseName, tier);
            boss.setName(baseName);
            if (!"NONE".equals(tier)) {
                boss.setTier(BossService.parseTier(tier));
            }
            bossRepository.save(boss);
        }
    }

    /** 去掉难度前缀；前缀不在已知列表里就返回 null（不动它）。 */
    private static String resolveBaseName(String name) {
        for (String prefix : PREFIX_TIERS.keySet()) {
            if (name.length() > prefix.length() && name.startsWith(prefix)) {
                return name.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String resolveTier(String name, String currentTier) {
        for (Map.Entry<String, String> entry : PREFIX_TIERS.entrySet()) {
            if (name.length() > entry.getKey().length() && name.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return currentTier;
    }

    /**
     * 0.0e 账号 ↔ 群友 对齐：
     * 每个非管理员账号都必须有一条群友记录，「群友角色一览表」里才不会缺人
     * （就算他还没登记游戏角色，也要先占一个位置，管理员可以直接给他加角色）。
     */
    private void syncPlayersWithAccounts() {
        if (markerExists(SYNC_PLAYER_MARKER)) {
            return;
        }
        int created = 0;
        for (AppUser user : userRepository.findAll()) {
            if (user.getRole() == AppUser.Role.ADMIN || user.getPlayer() != null) {
                continue;
            }
            Player player = playerRepository.findByNickname(user.getDisplayName()).orElse(null);
            if (player == null) {
                player = new Player();
                player.setNickname(user.getDisplayName());
                player.setQq(user.getUsername());
                player.setActive(true);
                player.setSortOrder(playerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                        .mapToInt(Player::getSortOrder).max().orElse(0) + 1);
                playerRepository.save(player);
                created++;
            }
            user.setPlayer(player);
            userRepository.save(user);
            log.info("账号 {} 已补群友记录「{}」", user.getUsername(), player.getNickname());
        }
        playerRepository.flush();
        userRepository.flush();
        saveMarker(SYNC_PLAYER_MARKER, "done");
        log.info("账号与群友对齐完成：新建 {} 条群友记录", created);
    }

    /** 0.0d 分组改名：原初 → 原初世界。 */
    private void renameGroups() {
        if (markerExists(GROUP_RENAME_MARKER)) {
            return;
        }
        groupRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(group -> "原初".equals(group.getName()))
                .findFirst()
                .ifPresent(group -> {
                    group.setName("原初世界");
                    groupRepository.save(group);
                    log.info("分组改名：原初 -> 原初世界");
                });
        saveMarker(GROUP_RENAME_MARKER, "done");
    }

    /**
     * 0.0c 给存量账号补上 boss排班 权限。
     * 账号管理（改密码、重置密码、绑定群友）仍然只有 ADMIN 能进。
     */
    private void grantSchedulePermission() {
        if (markerExists(SCHEDULE_MARKER)) {
            return;
        }
        int granted = 0;
        for (AppUser user : userRepository.findAll()) {
            if (user.getRole() != AppUser.Role.ADMIN && !user.isCanSchedule()) {
                user.setCanSchedule(true);
                userRepository.save(user);
                granted++;
            }
        }
        userRepository.flush();
        saveMarker(SCHEDULE_MARKER, "done");
        log.info("boss排班 权限已开放给 {} 个账号（账号管理仍仅 ADMIN）", granted);
    }

    /**
     * 0.0b 给每个 BOSS 写入它实际存在的难度，并把落在非法难度上的车次纠回来。
     * 以前难度选项是「本周排过什么就显示什么」，导致出现游戏里根本没有的难度。
     */
    private void migrateBossTiers() {
        if (markerExists(BOSS_TIER_MARKER)) {
            return;
        }
        int fixedBoss = 0;
        int fixedRun = 0;
        for (Map.Entry<String, String> entry : BOSS_TIERS.entrySet()) {
            Boss boss = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                    .filter(item -> item.getName().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (boss == null) {
                continue;
            }
            String tiers = entry.getValue();
            BossTier defaultTier = BossTier.valueOf(BOSS_DEFAULT_TIER.getOrDefault(entry.getKey(), "N"));
            if (!tiers.equals(boss.getTiers())) {
                boss.setTiers(tiers);
                fixedBoss++;
            }
            boss.setTier(defaultTier);
            bossRepository.save(boss);

            // 落在非法难度上的车次，纠到该 BOSS 的默认难度
            List<String> allowed = List.of(tiers.split(","));
            for (BossRun run : runRepository.findByBossIdOrderByRunIndexAsc(boss.getId())) {
                if (run.getTier() != defaultTier && !allowed.contains(run.getTier().name())) {
                    run.setTier(defaultTier);
                    runRepository.save(run);
                    fixedRun++;
                }
            }
        }
        runRepository.flush();
        saveMarker(BOSS_TIER_MARKER, "done");
        log.info("BOSS 难度规范化完成：修正 {} 个 BOSS 的难度清单、{} 趟车次的难度", fixedBoss, fixedRun);
    }

    /**
     * 融合车迁移：把「要打全打」的那组 BOSS 标记成同一个融合车，
     * 并把它们的难度统一成该融合车要求的难度（这里固定 H）。
     */
    private void migrateBossCombos() {
        if (markerExists(BOSS_COMBO_MARKER)) {
            return;
        }
        List<Boss> all = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc();
        int marked = 0;
        int tierFixed = 0;
        for (BossCombo.Combo combo : BossCombo.ALL) {
            for (String bossName : combo.bossNames()) {
                Boss boss = all.stream()
                        .filter(item -> item.getName().equals(bossName))
                        .findFirst()
                        .orElse(null);
                if (boss == null) {
                    continue;
                }
                if (!combo.key().equals(boss.getComboKey())) {
                    boss.setComboKey(combo.key());
                    marked++;
                }
                // 融合车按固定难度打：把该难度补进难度清单，并设为默认
                List<String> tiers = new ArrayList<>(java.util.Arrays.asList(
                        boss.getTiers() == null ? new String[0] : boss.getTiers().split(",")));
                if (!tiers.contains(combo.tier())) {
                    tiers.add(combo.tier());
                    tiers.removeIf(String::isBlank);
                    boss.setTiers(String.join(",", tiers));
                    tierFixed++;
                }
                boss.setTier(BossTier.valueOf(combo.tier()));
                bossRepository.save(boss);
            }
        }
        bossRepository.flush();
        saveMarker(BOSS_COMBO_MARKER, "done");
        log.info("融合车迁移完成：标记 {} 个 BOSS，补齐 {} 个 BOSS 的难度清单", marked, tierFixed);
    }

    /**
     * 把历史数据里的职业别名改写成标准名。
     *
     * 例如「阴阳师」→「Kanna」（同一个职业）。
     * 幂等：只有确实不同才写库，所以每次启动跑一次也没有副作用。
     */
    private void normalizeCharacterJobs() {
        int fixed = 0;
        for (GameCharacter character : characterRepository.findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc()) {
            String canonical = JobCatalog.canonical(character.getJob());
            if (!canonical.equals(character.getJob())) {
                log.info("职业名归一化：{} {} -> {}", character.getPlayer().getNickname(), character.getJob(), canonical);
                character.setJob(canonical);
                characterRepository.save(character);
                fixed++;
            }
        }
        if (fixed > 0) {
            characterRepository.flush();
            log.info("职业名归一化完成：修正 {} 个角色", fixed);
        }
    }

    /**
     * 「灿烂的凶星」的图标 key 从 baldrix 改为 evilstar。
     *
     * 原因：早期误以为它和 Baldrix（巴德利斯 / 人马）是同一个 BOSS，把图标命名成了 baldrix。
     * 实际是两个不同的 BOSS —— 图片本身是对的（金色少女形象），只是文件名误导。
     * 这里把库里已有的 icon_key 一起改掉，保证图标仍能正常显示。
     */
    private void renameEvilStarIconKey() {
        if (markerExists(BOSS_ICON_KEY_EVILSTAR_MARKER)) {
            return;
        }
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            if ("灿烂的凶星".equals(boss.getName()) && "baldrix".equals(boss.getIconKey())) {
                boss.setIconKey("evilstar");
                bossRepository.save(boss);
                log.info("修正图标 key：灿烂的凶星 baldrix -> evilstar");
            }
        }
        bossRepository.flush();
        saveMarker(BOSS_ICON_KEY_EVILSTAR_MARKER, "done");
    }

    /**
     * 修正「三人本」BOSS 的容量。
     *
     * GMSR 里有三个 BOSS 最多只能 3 人打：
     *   最初的对抗者 / 灿烂的凶星 / 林波
     * 早期种子数据把它们都写成了 6 人，导致界面显示 6 个位置、允许塞进第 4 个人。
     *
     * 幂等：每次启动都对齐一次，避免有人从旧库恢复后再次出错。
     */
    private void fixThreePlayerBossCapacity() {
        List<String> threePlayer = List.of("最初的对抗者", "灿烂的凶星", "林波");
        int fixed = 0;
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            if (!threePlayer.contains(boss.getName())) {
                continue;
            }
            if (boss.getCapacity() != 3) {
                log.info("修正三人本容量：{} {} 人 → 3 人", boss.getName(), boss.getCapacity());
                boss.setCapacity(3);
                bossRepository.save(boss);
                fixed++;
            }
        }
        if (fixed > 0) {
            bossRepository.flush();
        }
        if (!markerExists(BOSS_CAPACITY_3PLAYER_MARKER)) {
            saveMarker(BOSS_CAPACITY_3PLAYER_MARKER, "done");
        }
    }

    /** 0.0a 启动自检：每个 BOSS 的图标文件必须存在，否则页面会显示裂图。 */
    private void checkBossIconFiles() {
        java.io.File dir = new java.io.File("/opt/1panel/www/gmsr/boss-icons");
        List<String> missing = new ArrayList<>();
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            String icon = boss.getIconKey();
            if (icon == null || icon.isBlank()) {
                missing.add(boss.getName() + "(未配置图标)");
                continue;
            }
            java.io.File file = new java.io.File(dir, icon + ".png");
            if (!file.isFile() || file.length() < 2000) {
                missing.add(boss.getName() + " -> " + icon + ".png");
            }
        }
        if (missing.isEmpty()) {
            log.info("BOSS 图标自检通过（{} 个 BOSS 图标文件齐全）", bossRepository.count());
        } else {
            log.warn("BOSS 图标自检发现问题，页面可能显示裂图：{}", String.join("、", missing));
        }
    }

    /** 0.0 把暂时没有车次的 BOSS 补回来，避免以后发车时缺数据。 */
    private void restoreBosses() {
        BossGroup group = groupRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(item -> "神秘河".equals(item.getName()))
                .findFirst()
                .orElse(null);
        if (group == null) {
            log.warn("找不到「神秘河」分组，跳过 BOSS 恢复");
            return;
        }
        for (String[] spec : RESTORE_BOSSES) {
            String name = spec[0];
            String icon = spec[1];
            String tier = spec[2];
            Boss boss = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                    .filter(item -> item.getName().equals(name))
                    .findFirst()
                    .orElse(null);
            if (boss == null) {
                int sortOrder = (int) bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                        .filter(item -> item.getGroup().getId().equals(group.getId()))
                        .count() + 1;
                boss = new Boss();
                boss.setGroup(group);
                boss.setName(name);
                boss.setTier(BossService.parseTier(tier));
                boss.setCapacity(6);
                boss.setSortOrder(sortOrder);
                boss.setEnabled(true);
                boss.setIconKey(icon);
                bossRepository.save(boss);
                log.info("补回 BOSS：{}（{}，每车 6 人，暂无车次）", name, tier);
            } else if (!icon.equals(boss.getIconKey())) {
                boss.setIconKey(icon);
                bossRepository.save(boss);
                log.info("BOSS 图标补齐：{} -> {}", name, icon);
            }
        }
    }

    /** 0.1b 把 BOSS 名称统一成国服中文名，并配上官方立绘图标。 */
    private void syncBossNamesAndIcons() {
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            BossMeta meta = BOSS_META.get(boss.getName());
            if (meta == null) {
                continue;
            }
            boolean changed = false;
            if (!meta.displayName().equals(boss.getName())) {
                log.info("BOSS 中文名规范化：{} -> {}", boss.getName(), meta.displayName());
                boss.setName(meta.displayName());
                changed = true;
            }
            if (!meta.iconKey().equals(boss.getIconKey())) {
                boss.setIconKey(meta.iconKey());
                changed = true;
                log.info("BOSS 图标：{} -> {}", meta.displayName(), meta.iconKey());
            }
            if (changed) {
                bossRepository.save(boss);
            }
        }
    }

    /** 0.2b 图标补齐（已合并过 BOSS 的情况）。 */
    private void syncBossIcons() {
        syncBossNamesAndIcons();
    }

    /** 0.2 给每个 BOSS 配头像图标（首次导入时用）。 */
    private void assignBossIcons() {
        syncBossNamesAndIcons();
    }

    /**
     * 0.3 同一个 BOSS 的不同难度原本被当成多条记录（例如「N狗」与「C狗」其实是卡洛斯的 Normal / Chaos），
     * 这里按 BOSS 名合并，把车次挪到合并后的那条上。
     */
    private void dedupeBosses() {
        Map<String, Long> mergedCount = new LinkedHashMap<>();
        for (Boss dupe : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            Boss keep = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                    .filter(candidate -> !candidate.getId().equals(dupe.getId()))
                    .filter(candidate -> candidate.getName().equals(dupe.getName()))
                    .filter(candidate -> candidate.getId() < dupe.getId())
                    .findFirst()
                    .orElse(null);
            if (keep == null) {
                continue;
            }
            List<BossRun> runs = runRepository.findByBossIdOrderByRunIndexAsc(dupe.getId());
            for (BossRun run : runs) {
                run.setBoss(keep);
                runRepository.save(run);
            }
            if (keep.getIconKey() == null) {
                keep.setIconKey(dupe.getIconKey());
                bossRepository.save(keep);
            }
            bossRepository.delete(dupe);
            mergedCount.merge(keep.getName(), (long) runs.size(), Long::sum);
            log.info("合并同名 BOSS：{}（{} 趟车次并入 id={}）", dupe.getName(), runs.size(), keep.getId());
        }
        mergedCount.forEach((name, count) -> log.info("BOSS 合并完成：{} 共并入 {} 趟车次", name, count));
    }

    /** 1. 猴 / 凶星 / 林波 只能 3 人组队 */
    private void fixBossCapacity() {
        List<Boss> bosses = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc();
        for (Boss boss : bosses) {
            int expected = THREE_MAN_BOSSES.contains(boss.getName()) ? 3 : 6;
            if (boss.getCapacity() != expected) {
                log.info("调整 BOSS 组队上限：{} {} -> {} 人", boss.getName(), boss.getCapacity(), expected);
                boss.setCapacity(expected);
                bossRepository.save(boss);
            }
        }
    }

    /** 2. Excel 解析时 BOSS 名串到了隔壁格，这里纠正 */
    private void fixBossNames() {
        bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                .filter(boss -> boss.getName().contains("H敦凯尔") && !boss.getName().equals("H敦凯尔"))
                .forEach(boss -> {
                    log.info("纠正 BOSS 名称：{} -> H敦凯尔", boss.getName());
                    boss.setName("H敦凯尔");
                    bossRepository.save(boss);
                });
    }

    /** 3. 群友O = 群友Y：角色与已排席位都转到群友Y名下 */
    private void mergePlayers() {
        Player from = playerRepository.findByNickname(MERGE_FROM).orElse(null);
        Player to = playerRepository.findByNickname(MERGE_TO).orElse(null);
        if (from == null) {
            return;
        }
        if (to == null) {
            log.warn("找不到「{}」，无法合并「{}」", MERGE_TO, MERGE_FROM);
            return;
        }
        List<GameCharacter> fromCharacters = characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(from.getId());
        List<GameCharacter> toCharacters = characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(to.getId());
        for (GameCharacter character : fromCharacters) {
            GameCharacter target = toCharacters.stream()
                    .filter(c -> c.getJob().equals(character.getJob()) && c.getPower() == character.getPower())
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                // 群友Y名下没有同样的角色，直接把角色过继过去
                character.setPlayer(to);
                characterRepository.save(character);
                toCharacters.add(character);
                log.info("合并角色：{} 的 {} {}万 -> {}", MERGE_FROM, character.getJob(), character.getPower(), MERGE_TO);
                continue;
            }
            List<RunMember> memberships = memberRepository.findByCharacterIdOrderByIdAsc(character.getId());
            for (RunMember member : memberships) {
                if (memberRepository.findByRunIdAndCharacterId(member.getRun().getId(), target.getId()).isPresent()) {
                    memberRepository.delete(member);
                } else {
                    member.setCharacter(target);
                    memberRepository.save(member);
                }
            }
            memberRepository.deleteAll(memberRepository.findByCharacterIdOrderByIdAsc(character.getId()));
            characterRepository.delete(character);
            log.info("合并角色并转移 {} 个席位：{} -> {}", memberships.size(), MERGE_FROM, MERGE_TO);
        }
        AppUser fromUser = userRepository.findByUsername(MERGE_FROM).orElse(null);
        if (fromUser != null) {
            userRepository.delete(fromUser);
        }
        playerRepository.delete(from);
        log.info("已删除群友「{}」", MERGE_FROM);
    }

    /** 4. 删除已退出群友：先清席位，再删角色与账号 */
    private void removePlayers() {
        for (String nickname : REMOVED_PLAYERS) {
            Player player = playerRepository.findByNickname(nickname).orElse(null);
            if (player == null) {
                continue;
            }
            for (GameCharacter character : characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(player.getId())) {
                memberRepository.deleteAll(memberRepository.findByCharacterIdOrderByIdAsc(character.getId()));
                characterRepository.delete(character);
            }
            userRepository.findByUsername(player.getNickname()).ifPresent(userRepository::delete);
            playerRepository.delete(player);
            log.info("已删除群友「{}」及其账号与席位", nickname);
        }
    }

    /** 5. 写入 QQ 号与显示名称 */
    private void applyQqAndNames() {
        Map<String, String> nicknameToQq = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : RENAMES.entrySet()) {
            playerRepository.findByNickname(entry.getKey()).ifPresent(player -> {
                log.info("群友改名：{} -> {}", entry.getKey(), entry.getValue());
                player.setNickname(entry.getValue());
                playerRepository.save(player);
            });
        }
        for (Map.Entry<String, String> entry : QQ_TO_NAME.entrySet()) {
            String qq = entry.getKey();
            String name = entry.getValue();
            Player player = playerRepository.findByNickname(name)
                    .or(() -> playerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                            .filter(item -> item.getNickname().equalsIgnoreCase(name))
                            .findFirst())
                    .orElse(null);
            if (player == null) {
                log.warn("对照表里的「{}」（QQ {}）在数据库里找不到对应群友", name, qq);
                continue;
            }
            if (!player.getNickname().equals(name)) {
                log.info("显示名称规范化：{} -> {}", player.getNickname(), name);
                player.setNickname(name);
            }
            player.setQq(qq);
            playerRepository.save(player);
            nicknameToQq.put(name, qq);
        }
    }

    /** 6. 账号改为 QQ 号登录（同名群友已有账号则改名保留，多余的旧账号删除） */
    private void rebuildAccounts() {
        List<AppUser> accounts = userRepository.findAll();
        List<AppUser> toDelete = new ArrayList<>();
        List<String> result = new ArrayList<>();

        for (Player player : playerRepository.findAllByOrderBySortOrderAscIdAsc()) {
            if (player.getQq() == null || player.getQq().isBlank()) {
                continue;
            }
            // 已有 QQ 号账号
            AppUser user = accounts.stream()
                    .filter(u -> player.getQq().equals(u.getUsername()))
                    .findFirst()
                    .orElse(null);
            if (user == null) {
                // 退而求其次：占用旧昵称的那个账号直接改名
                user = accounts.stream()
                        .filter(u -> u.getRole() != AppUser.Role.ADMIN)
                        .filter(u -> u.getUsername().equals(player.getNickname())
                                || (u.getPlayer() != null && u.getPlayer().getId().equals(player.getId())))
                        .findFirst()
                        .orElse(null);
                if (user != null) {
                    log.info("登录账号变更：{} -> {}（{}）", user.getUsername(), player.getQq(), player.getNickname());
                }
            }
            if (user == null) {
                user = new AppUser();
                user.setRole(AppUser.Role.VIEWER);
                log.info("为群友「{}」（QQ {}）新建账号", player.getNickname(), player.getQq());
                accounts.add(user);
            }
            // 同一群友名下多余的账号（含旧的昵称账号）删掉
            for (AppUser other : accounts) {
                if (other == user || other.getRole() == AppUser.Role.ADMIN || toDelete.contains(other)) {
                    continue;
                }
                boolean samePlayer = other.getPlayer() != null && other.getPlayer().getId().equals(player.getId());
                boolean sameName = other.getUsername().equals(player.getNickname());
                if (samePlayer || sameName) {
                    toDelete.add(other);
                }
            }
            user.setUsername(player.getQq());
            user.setDisplayName(player.getNickname());
            user.setPlayer(player);
            user.setEnabled(true);
            user.setPasswordHash(passwordEncoder.encode(defaultPassword));
            user.setMustChangePassword(true);
            result.add(player.getNickname() + "(" + player.getQq() + ")");
        }

        // 没有任何群友对应的账号一并清理
        List<String> validNames = new ArrayList<>();
        for (Player player : playerRepository.findAllByOrderBySortOrderAscIdAsc()) {
            validNames.add(player.getNickname());
            if (player.getQq() != null) {
                validNames.add(player.getQq());
            }
        }
        for (AppUser user : accounts) {
            if (user.getRole() == AppUser.Role.ADMIN || toDelete.contains(user)) {
                continue;
            }
            if (!validNames.contains(user.getUsername())) {
                toDelete.add(user);
            }
        }

        for (AppUser user : toDelete) {
            log.info("删除多余/无效账号：{}（{}）", user.getUsername(), user.getDisplayName());
        }
        userRepository.deleteAll(toDelete);
        userRepository.flush();
        for (AppUser user : accounts) {
            if (!toDelete.contains(user)) {
                userRepository.save(user);
            }
        }
        userRepository.flush();
        if (!result.isEmpty()) {
            log.info("已按 QQ 号整理 {} 个群友账号，初始密码 {}，首次登录需修改密码：{}",
                    result.size(), defaultPassword, String.join("、", result));
        }
    }

    private boolean markerExists(String key) {
        return settingRepository.existsById(key);
    }

    private void saveMarker(String key, String value) {
        AppSetting setting = settingRepository.findById(key).orElseGet(() -> new AppSetting(key, value));
        setting.setValue(value);
        setting.setUpdatedAt(java.time.Instant.now());
        settingRepository.save(setting);
    }

}
