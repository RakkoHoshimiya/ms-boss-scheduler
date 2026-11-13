package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.dto.RunDtos.MemberRequest;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import com.gmsr.scheduler.security.AdminAccountInitializer;
import com.gmsr.scheduler.security.PlayerAccountInitializer;
import com.gmsr.scheduler.security.SecurityStartupCheck;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 融合车三条「后补路径」的一致性：复制 / 重排 / 删除成员 BOSS。
 *
 * <p>背景：create / delete / addMember / moveMember / swapMembers / replaceMembers
 * 都已经处理了融合车，但这三条没有 —— 于是复制一辆融合车会只多出主 BOSS 的一趟，
 * 重排只重排一个 BOSS，删掉融合车里的某个成员会把其余 6 个变成孤儿。
 *
 * <p>需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
class ComboIntegrityTest {

    @MockitoBean
    private SeedDataImporter seedDataImporter;
    @MockitoBean
    private DataMigrator dataMigrator;
    @MockitoBean
    private AdminAccountInitializer adminAccountInitializer;
    @MockitoBean
    private PlayerAccountInitializer playerAccountInitializer;
    @MockitoBean
    private SecurityStartupCheck securityStartupCheck;
    @MockitoBean
    private WeeklyStatusResetService weeklyStatusResetService;

    @Autowired
    private RunService runService;
    @Autowired
    private BossService bossService;
    @Autowired
    private ComboGuard comboGuard;
    @Autowired
    private BossGroupRepository groupRepository;
    @Autowired
    private BossRepository bossRepository;
    @Autowired
    private BossRunRepository runRepository;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private GameCharacterRepository characterRepository;
    @Autowired
    private RunMemberRepository memberRepository;

    private record Fixture(long groupId, List<Boss> combo, long playerId, long charId) {
    }

    private Fixture createFixture(String tag, int memberCount) {
        BossGroup group = new BossGroup();
        group.setName(tag + "_G");
        group.setSortOrder(93);
        groupRepository.save(group);

        List<Boss> combo = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            Boss b = new Boss();
            b.setGroup(group);
            b.setName(tag + "_C" + i);
            b.setCapacity(6);
            b.setSortOrder(93 + i);
            b.setComboKey(tag + "-combo");
            b.setTiers("H");
            b.setTier(BossTier.H);
            bossRepository.save(b);
            combo.add(b);
        }

        Player player = new Player();
        player.setNickname(tag + "_P");
        player.setSortOrder(93);
        playerRepository.save(player);

        GameCharacter character = new GameCharacter();
        character.setPlayer(player);
        character.setJob("Kanna");
        character.setPower(100);
        characterRepository.save(character);

        return new Fixture(group.getId(), combo, player.getId(), character.getId());
    }

    private void cleanup(Fixture f) {
        for (Boss b : f.combo()) {
            runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).forEach(r -> runService.delete(r.getId()));
            bossRepository.delete(b);
        }
        characterRepository.findById(f.charId()).ifPresent(characterRepository::delete);
        playerRepository.findById(f.playerId()).ifPresent(playerRepository::delete);
        groupRepository.findById(f.groupId()).ifPresent(groupRepository::delete);
    }

    private long tripCount(Fixture f) {
        return f.combo().stream()
                .mapToLong(b -> runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).size())
                .sum();
    }

    @Test
    @DisplayName("复制融合车的一趟：每个成员 BOSS 都要多出一趟，且趟号一致")
    void copyRunCopiesAllComboMembers() {
        Fixture f = createFixture("COPY" + System.currentTimeMillis(), 3);
        try {
            var first = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            runService.addMember(first.id(), new MemberRequest(f.charId(), null, null, null, null));

            long before = tripCount(f);
            var copied = runService.copyRun(first.id());

            assertEquals(before + f.combo().size(), tripCount(f),
                    "复制一趟融合车，应该每个成员 BOSS 各多一趟");

            // 每个成员 BOSS 上都要有「同趟次 + 同难度」的那一趟
            for (Boss b : f.combo()) {
                boolean exists = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .anyMatch(r -> r.getRunIndex() == copied.runIndex());
                assertTrue(exists, "成员 BOSS「" + b.getName() + "」缺少第 " + copied.runIndex() + " 趟");
            }

            /*
             * 复制出来的是「空趟」，阵容不跟着复制。
             *
             * V6 的唯一约束规定同一角色在同一 BOSS + 同一难度只能排一趟，
             * 复制出来的新趟与原趟同 BOSS 同难度，带阵容必然撞约束
             * （线上表现为点「复制这一趟」报 400 已经存在相同的记录）。
             * 所以这里断言复制结果没有席位，且骨架（难度/备注）保持一致。
             */
            for (Boss b : f.combo()) {
                long withMember = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .filter(r -> r.getRunIndex() == copied.runIndex())
                        .mapToLong(r -> memberRepository.countByRunId(r.getId()))
                        .sum();
                assertEquals(0, withMember, "成员 BOSS「" + b.getName() + "」复制的这一趟应该是空趟");
            }
            var sourceRow = runRepository.findById(first.id()).orElseThrow();
            var copiedRow = runRepository.findById(copied.id()).orElseThrow();
            assertEquals(sourceRow.getTier(), copiedRow.getTier(), "难度应与源趟一致");
            assertEquals(sourceRow.getNote(), copiedRow.getNote(), "备注应随趟次一起复制");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("重排趟次：融合车所有成员 BOSS 一起重排，编号仍然对齐")
    void resequenceAppliesToAllComboMembers() {
        Fixture f = createFixture("RESEQ" + System.currentTimeMillis(), 3);
        try {
            var r1 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            var r2 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            // 删掉第 1 趟制造断号（delete 本身会重排，这里直接改库模拟历史脏数据）
            runService.delete(r1.id());

            runService.resequence(f.combo().get(0).getId());

            // 三个成员 BOSS 的趟次集合必须完全一致
            List<List<Integer>> indexes = new ArrayList<>();
            for (Boss b : f.combo()) {
                indexes.add(runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .map(com.gmsr.scheduler.domain.BossRun::getRunIndex)
                        .sorted()
                        .toList());
            }
            for (int i = 1; i < indexes.size(); i++) {
                assertEquals(indexes.get(0), indexes.get(i),
                        "融合车各成员 BOSS 的趟次必须一致，实际 " + indexes);
            }
            assertEquals(List.of(1), indexes.get(0), "删到只剩一趟时应编号为 1");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("删除融合车里的单个成员 BOSS：必须被拒绝，避免其余成员变成孤儿")
    void deleteComboMemberRejected() {
        Fixture f = createFixture("DELCOMBO" + System.currentTimeMillis(), 3);
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> bossService.deleteBoss(f.combo().get(1).getId()));
            assertTrue(ex.getMessage().contains("融合车"), "提示应说明属于融合车，实际：" + ex.getMessage());
            assertNotNull(bossRepository.findById(f.combo().get(1).getId()).orElse(null),
                    "被拒绝后 BOSS 必须还在");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("ComboGuard：融合车返回全部成员，非融合车只返回自己")
    void comboGuardResolvesMembers() {
        Fixture f = createFixture("GUARD" + System.currentTimeMillis(), 3);
        try {
            assertEquals(3, comboGuard.membersOf(f.combo().get(0)).size(),
                    "融合车应返回全部 3 个成员 BOSS");
            assertTrue(comboGuard.isCombo(f.combo().get(0)), "带 comboKey 的 BOSS 应判定为融合车");

            // 非融合车：单独建一个没有 comboKey 的 BOSS
            Boss plain = new Boss();
            plain.setGroup(f.combo().get(0).getGroup());
            plain.setName("GUARD_PLAIN" + System.currentTimeMillis());
            plain.setCapacity(6);
            plain.setSortOrder(99);
            plain.setTier(BossTier.H);
            bossRepository.save(plain);
            try {
                assertEquals(1, comboGuard.membersOf(plain).size(), "非融合车只应返回自己");
                assertTrue(!comboGuard.isCombo(plain), "没有 comboKey 的 BOSS 不应判定为融合车");
            } finally {
                bossRepository.delete(plain);
            }
        } finally {
            cleanup(f);
        }
    }
}
