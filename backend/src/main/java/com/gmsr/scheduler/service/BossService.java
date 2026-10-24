package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossCombo;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.BossTierCatalog;
import com.gmsr.scheduler.dto.BossDtos.BossRequest;
import com.gmsr.scheduler.dto.BossDtos.BossView;
import com.gmsr.scheduler.dto.BossDtos.GroupRequest;
import com.gmsr.scheduler.dto.BossDtos.GroupView;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import com.gmsr.scheduler.domain.BossRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BOSS 分组与 BOSS 维护。 */
@Service
public class BossService {

    private final BossGroupRepository groupRepository;
    private final BossRepository bossRepository;
    private final BossRunRepository runRepository;
    private final RunMemberRepository memberRepository;

    public BossService(
            BossGroupRepository groupRepository,
            BossRepository bossRepository,
            BossRunRepository runRepository,
            RunMemberRepository memberRepository) {
        this.groupRepository = groupRepository;
        this.bossRepository = bossRepository;
        this.runRepository = runRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public List<GroupView> listGroups() {
        List<BossGroup> groups = groupRepository.findAllByOrderBySortOrderAscIdAsc();
        List<BossView> bosses = listBosses();
        List<GroupView> result = new ArrayList<>(groups.size());
        for (BossGroup group : groups) {
            long count = bosses.stream().filter(b -> b.groupId().equals(group.getId())).count();
            result.add(new GroupView(group.getId(), group.getName(), group.getSortOrder(), group.getNote(), count));
        }
        return result;
    }

    /** 全部 BOSS（含每个 BOSS 的车次数与已排席位数）。 */
    @Transactional(readOnly = true)
    public List<BossView> listBosses() {
        List<Boss> bosses = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc();
        Map<Long, Long> runCountByBoss = new HashMap<>();
        for (Object[] row : runRepository.countGroupedByBoss()) {
            runCountByBoss.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        List<BossView> result = new ArrayList<>(bosses.size());
        for (Boss boss : bosses) {
            long bossRuns = runCountByBoss.getOrDefault(boss.getId(), 0L);
            result.add(new BossView(
                    boss.getId(),
                    boss.getGroup().getId(),
                    boss.getGroup().getName(),
                    boss.getName(),
                    boss.getIconKey(),
                    boss.getTier().name(),
                    boss.getTier().label(),
                    tiersOf(boss),
                    BossTierCatalog.allowedTiersOf(boss.getName()),
                    boss.getCapacity(),
                    boss.getSortOrder(),
                    boss.isEnabled(),
                    boss.getNote(),
                    boss.getComboKey(),
                    comboNameOf(boss.getComboKey()),
                    comboTierOf(boss.getComboKey()),
                    bossRuns,
                    0));
        }
        if (bosses.isEmpty()) {
            return result;
        }
        return result;
    }

    /** 融合车显示名（不是融合车成员时返回 null）。 */
    private static String comboNameOf(String comboKey) {
        BossCombo.Combo combo = BossCombo.byKey(comboKey);
        return combo == null ? null : combo.name();
    }

    /** 融合车固定难度（不是融合车成员时返回 null）。 */
    private static String comboTierOf(String comboKey) {
        BossCombo.Combo combo = BossCombo.byKey(comboKey);
        return combo == null ? null : combo.tier();
    }

    @Transactional(readOnly = true)
    public BossView getBoss(Long id) {
        return listBosses().stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("BOSS 不存在：" + id));
    }

    @Transactional
    public GroupView createGroup(GroupRequest request) {
        BossGroup group = new BossGroup();
        group.setName(request.name().trim());
        group.setNote(request.note());
        group.setSortOrder(request.sortOrder() == null ? (int) groupRepository.count() + 1 : request.sortOrder());
        groupRepository.save(group);
        return listGroups().stream()
                .filter(g -> g.id().equals(group.getId()))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public GroupView updateGroup(Long id, GroupRequest request) {
        BossGroup group = groupRepository.findById(id).orElseThrow(() -> new NotFoundException("分组不存在：" + id));
        group.setName(request.name().trim());
        group.setNote(request.note());
        if (request.sortOrder() != null) {
            group.setSortOrder(request.sortOrder());
        }
        groupRepository.save(group);
        return listGroups().stream()
                .filter(g -> g.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public void deleteGroup(Long id) {
        BossGroup group = groupRepository.findById(id).orElseThrow(() -> new NotFoundException("分组不存在：" + id));
        for (Boss boss : bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc()) {
            if (boss.getGroup().getId().equals(id)) {
                purgeRunsOfBoss(boss.getId());
            }
        }
        groupRepository.delete(group);
    }

    @Transactional
    public BossView createBoss(BossRequest request) {
        Boss boss = new Boss();
        applyBoss(boss, request);
        if (request.sortOrder() == null) {
            long sameGroup = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                    .filter(b -> b.getGroup().getId().equals(boss.getGroup().getId()))
                    .count();
            boss.setSortOrder((int) sameGroup + 1);
        }
        bossRepository.save(boss);
        return getBoss(boss.getId());
    }

    @Transactional
    public BossView updateBoss(Long id, BossRequest request) {
        Boss boss = bossRepository.findById(id).orElseThrow(() -> new NotFoundException("BOSS 不存在：" + id));
        applyBoss(boss, request);
        if (request.sortOrder() != null) {
            boss.setSortOrder(request.sortOrder());
        }
        bossRepository.save(boss);
        return getBoss(id);
    }

    @Transactional
    public void deleteBoss(Long id) {
        Boss boss = bossRepository.findById(id).orElseThrow(() -> new NotFoundException("BOSS 不存在：" + id));
        // run_member 有外键引用 boss_run，必须先把席位删干净再删车次/BOSS
        purgeRunsOfBoss(id);
        bossRepository.delete(boss);
    }

    /** 清掉某个 BOSS 名下所有车次与席位。 */
    private void purgeRunsOfBoss(Long bossId) {
        List<BossRun> runs = runRepository.findByBossIdOrderByRunIndexAsc(bossId);
        if (!runs.isEmpty()) {
            memberRepository.deleteByRunIn(runs);
            memberRepository.flush();
            runRepository.deleteAll(runs);
            runRepository.flush();
        }
    }

    private void applyBoss(Boss boss, BossRequest request) {
        if (request.groupId() != null) {
            BossGroup group = groupRepository.findById(request.groupId())
                    .orElseThrow(() -> new NotFoundException("分组不存在：" + request.groupId()));
            boss.setGroup(group);
        } else if (boss.getGroup() == null) {
            throw new BusinessException("必须指定 BOSS 所属分组");
        }
        boss.setName(request.name().trim());
        boss.setTier(BossTier.valueOf(request.tier() == null || request.tier().isBlank() ? "NONE" : request.tier()));
        if (request.capacity() != null) {
            boss.setCapacity(request.capacity());
        }
        if (request.enabled() != null) {
            boss.setEnabled(request.enabled());
        }
        if (request.tiers() != null) {
            String normalized = normalizeTiers(request.tiers(), boss.getTier());
            ensureTiersExistInGame(boss.getName(), normalized);
            boss.setTiers(normalized);
        }
        if (request.iconKey() != null) {
            boss.setIconKey(request.iconKey().isBlank() ? null : request.iconKey().trim());
        }
        boss.setNote(request.note());
    }

    /** 读出 BOSS 支持的难度；没有配置时回退到它自己的默认难度。 */
    public static List<String> tiersOf(Boss boss) {
        String raw = boss.getTiers();
        if (raw == null || raw.isBlank()) {
            return boss.getTier() == BossTier.NONE ? List.of() : List.of(boss.getTier().name());
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toUpperCase();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 校验难度是否都在游戏里真实存在。
     *
     * 前端会禁用不存在的难度，但接口可以被直接调用，所以后端必须再挡一次 ——
     * 否则还是能存进「卡尔洛斯 + Hard」这种脏数据。
     */
    private void ensureTiersExistInGame(String bossName, String normalizedTiers) {
        List<String> allowed = BossTierCatalog.allowedTiersOf(bossName);
        if (allowed.isEmpty() || normalizedTiers == null || normalizedTiers.isBlank()) {
            // 未登记的 BOSS 不做限制，方便先排班再补登记
            return;
        }
        for (String tier : normalizedTiers.split(",")) {
            String value = tier.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!allowed.contains(value)) {
                throw new BusinessException(
                        "「" + bossName + "」在游戏里没有 " + BossTierCatalog.labelOf(value) + " 难度（可选："
                                + String.join(" / ", allowed.stream().map(BossTierCatalog::labelOf).toList()) + "）");
            }
        }
    }

    /** 规范化难度列表：去掉 NONE / 非法值，保持固定顺序，并把默认难度包含进去。 */
    public static String normalizeTiers(List<String> tiers, BossTier defaultTier) {
        List<String> order = List.of("E", "N", "H", "C", "EX");
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (String tier : tiers) {
            if (tier == null || tier.isBlank()) {
                continue;
            }
            String value = tier.trim().toUpperCase();
            if ("NONE".equals(value) || !order.contains(value)) {
                continue;
            }
            keep.add(value);
        }
        if (defaultTier != null && defaultTier != BossTier.NONE) {
            keep.add(defaultTier.name());
        }
        if (keep.isEmpty()) {
            return null;
        }
        return order.stream().filter(keep::contains).reduce((a, b) -> a + "," + b).orElse(null);
    }

    /** 校验并解析难度，空值回退到 NONE。 */
    public static BossTier parseTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return BossTier.NONE;
        }
        try {
            return BossTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("未知难度：" + tier);
        }
    }
}
