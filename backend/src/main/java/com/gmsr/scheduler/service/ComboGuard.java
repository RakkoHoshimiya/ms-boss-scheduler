package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 融合车「要打全打」不变量的唯一入口。
 *
 * 背景：融合车在数据层是「每个成员 BOSS 各一条 boss_run」。任何只动其中一条的写操作，
 * 都会让各 BOSS 的趟次或阵容错位，而这种错位在三张页面上表现成「数据不同步」，
 * 事后很难修复。
 *
 * 以前 addMember / removeMember / moveMember / swapMembers / replaceMembers
 * 各自复制了一份「找出同一趟的所有成员」的逻辑，而
 * copyRun / resequence / deleteBoss 三条路径**漏掉了**。为了不再漏第四次，
 * 这里把「同一趟是哪些车次」收敛成一个组件，凡是涉及趟次增删改的地方都从这里取。
 *
 * 注意：本类只负责**定位**同一趟的全部车次，不负责加锁与校验；
 * 调用方仍需在同一事务内自行加锁（见 RunService#lockTrip）。
 */
@Component
public class ComboGuard {

    private final BossRepository bossRepository;
    private final BossRunRepository runRepository;

    public ComboGuard(BossRepository bossRepository, BossRunRepository runRepository) {
        this.bossRepository = bossRepository;
        this.runRepository = runRepository;
    }

    /** 这个 BOSS 是否属于融合车。 */
    public boolean isCombo(Boss boss) {
        return boss != null && boss.getComboKey() != null && !boss.getComboKey().isBlank();
    }

    public boolean isCombo(BossRun run) {
        return run != null && isCombo(run.getBoss());
    }

    /** 同一融合车的全部成员 BOSS（按 sortOrder 排序，第一个视为主 BOSS）。 */
    @Transactional(readOnly = true)
    public List<Boss> membersOf(Boss boss) {
        if (!isCombo(boss)) {
            return boss == null ? List.of() : List.of(boss);
        }
        String comboKey = boss.getComboKey();
        return bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                .filter(candidate -> comboKey.equals(candidate.getComboKey()))
                .toList();
    }

    /**
     * 与给定车次「同一趟」（同难度 + 同趟次）的全部成员车次；非融合车返回自身。
     *
     * 这是 copyRun / resequence / delete 等操作必须使用的定位方式。
     */
    @Transactional(readOnly = true)
    public List<BossRun> tripOf(BossRun run) {
        if (!isCombo(run)) {
            return List.of(run);
        }
        List<BossRun> siblings = runRepository.findComboSiblings(
                run.getBoss().getComboKey(), run.getTier(), run.getRunIndex());
        // 兜底：融合车成员还没建齐（历史数据残缺）时，至少保证不丢当前这一条
        return siblings.isEmpty() ? List.of(run) : siblings;
    }

    /** 给本趟的每个成员 BOSS 找出「对应那一条」车次。 */
    public BossRun siblingOf(List<BossRun> trip, Boss boss) {
        if (boss == null) {
            return null;
        }
        return trip.stream()
                .filter(candidate -> candidate.getBoss().getId().equals(boss.getId()))
                .findFirst()
                .orElse(null);
    }
}
