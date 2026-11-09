package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.security.AdminAccountInitializer;
import com.gmsr.scheduler.security.PlayerAccountInitializer;
import com.gmsr.scheduler.security.SecurityStartupCheck;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 融合车（combo）的趟次一致性。
 *
 * 背景（线上问题）：融合车在底层是每个成员 BOSS 各一条车次，
 * 但之前 create / delete 只动单个 BOSS。用户在融合车上加一趟时只建了主 BOSS 的一条，
 * 于是「融合车 3 趟，但另外 6 个 BOSS 只有 2 趟」——
 * 车次一览表按主 BOSS 显示 3 趟，别的页面按成员显示 2 趟，看起来就是「数据不同步」。
 *
 * 现在 create/delete 会连带处理同一趟的所有成员，本测试锁住这个行为。
 *
 * 需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
class ComboRunTest {

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
    private BossRepository bossRepository;
    @Autowired
    private BossRunRepository runRepository;
    @Autowired
    private com.gmsr.scheduler.repository.BossGroupRepository groupRepository;
    @Autowired
    private com.gmsr.scheduler.repository.PlayerRepository playerRepository;
    @Autowired
    private com.gmsr.scheduler.repository.GameCharacterRepository characterRepository;
    @Autowired
    private com.gmsr.scheduler.repository.RunMemberRepository memberRepository;

    /** 造一个融合车（3 个成员）+ 一个普通 BOSS，避免依赖库里已有数据。 */
    private record Fixture(List<Boss> combo, Boss plain, long groupId, long charId, long playerId) {
    }

    private Fixture createFixture(String tag) {
        com.gmsr.scheduler.domain.BossGroup group = new com.gmsr.scheduler.domain.BossGroup();
        group.setName(tag + "_G");
        group.setSortOrder(95);
        groupRepository.save(group);

        List<Boss> combo = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Boss b = new Boss();
            b.setGroup(group);
            b.setName(tag + "_COMBO" + i);
            b.setCapacity(3);
            b.setSortOrder(95 + i);
            b.setComboKey(tag + "-combo");
            b.setTiers("H");
            b.setTier(com.gmsr.scheduler.domain.BossTier.H);
            bossRepository.save(b);
            combo.add(b);
        }
        Boss plain = new Boss();
        plain.setGroup(group);
        plain.setName(tag + "_PLAIN");
        plain.setCapacity(3);
        plain.setSortOrder(99);
        plain.setTiers("H");
        plain.setTier(com.gmsr.scheduler.domain.BossTier.H);
        bossRepository.save(plain);

        // 造一个群友 + 一个角色，供成员操作测试用
        Player player = new Player();
        player.setNickname(tag + "_P");
        player.setSortOrder(95);
        playerRepository.save(player);
        GameCharacter character = new GameCharacter();
        character.setPlayer(player);
        character.setJob("Kanna");
        character.setPower(100);
        characterRepository.save(character);

        return new Fixture(combo, plain, group.getId(), character.getId(), player.getId());
    }

    private void cleanup(Fixture f) {
        for (Boss b : f.combo()) {
            runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).forEach(r -> {
                runService.delete(r.getId());
            });
        }
        runRepository.findByBossIdOrderByRunIndexAsc(f.plain().getId()).forEach(r -> runService.delete(r.getId()));
        bossRepository.deleteAll(f.combo());
        bossRepository.delete(f.plain());
        characterRepository.findById(f.charId()).ifPresent(characterRepository::delete);
        playerRepository.findById(f.playerId()).ifPresent(playerRepository::delete);
        groupRepository.findById(f.groupId()).ifPresent(groupRepository::delete);
    }

    @Test
    @DisplayName("给融合车加一趟：所有成员 BOSS 都要建；删一趟：全部一起删")
    void comboCreateAndDeleteStayAligned() {
        Fixture f = createFixture("COMBO" + System.currentTimeMillis());
        try {
        List<Boss> combo = f.combo();
        assertTrue(combo.size() >= 2, "应有融合车成员，实际 " + combo.size() + " 个");

        Boss primary = combo.get(0);
        int before = runRepository.findByBossIdOrderByRunIndexAsc(primary.getId()).size();

        // 用主 BOSS 建一趟
        var created = runService.create(new RunRequest(primary.getId(), "H", null, null, null, null));
        int index = created.runIndex();

        // 每个成员都应该有「同难度 + 同趟次」的车次
        for (Boss b : combo) {
            boolean exists = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                    .anyMatch(r -> r.getRunIndex() == index && r.getTier().name().equals("H"));
            assertTrue(exists, "融合车成员「" + b.getName() + "」缺少第 " + index + " 趟");
        }

        // 删一条，应该把所有成员的这一趟都删掉
        runService.delete(created.id());
        for (Boss b : combo) {
            boolean exists = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                    .anyMatch(r -> r.getRunIndex() == index && r.getTier().name().equals("H"));
            assertTrue(!exists, "融合车成员「" + b.getName() + "」的第 " + index + " 趟应已删除");
        }
        assertEquals(before, runRepository.findByBossIdOrderByRunIndexAsc(primary.getId()).size(),
                "删完后主 BOSS 的趟数应回到原值");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("删除后趟次重新编号，不留断号")
    void deleteRenumbersLeftoverRuns() {
        Fixture f = createFixture("RENUM" + System.currentTimeMillis());
        Boss boss = f.plain();

        var first = runService.create(new RunRequest(boss.getId(), "H", null, null, null, null));
        var second = runService.create(new RunRequest(boss.getId(), "H", null, null, null, null));
        try {
            // 删掉第 1 趟，第 2 趟应该变成第 1 趟
            runService.delete(first.id());
            List<BossRun> left = runRepository.findByBossIdOrderByRunIndexAsc(boss.getId()).stream()
                    .filter(r -> "H".equals(r.getTier().name()))
                    .toList();
            for (int i = 0; i < left.size(); i++) {
                assertEquals(i + 1, left.get(i).getRunIndex(),
                        "删完应连续编号，实际第 " + (i + 1) + " 个是 " + left.get(i).getRunIndex());
            }
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("融合车加人：7 个成员 BOSS 都要有；重复调用是幂等的")
    void addMemberAppliesToAllComboBosses() {
        Fixture f = createFixture("COMBOMEM" + System.currentTimeMillis());
        try {
            var run = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            long charId = f.charId();

            runService.addMember(run.id(), new com.gmsr.scheduler.dto.RunDtos.MemberRequest(charId, null, null, null, null));

            for (Boss b : f.combo()) {
                boolean onBoard = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .filter(r -> r.getRunIndex() == run.runIndex())
                        .findFirst()
                        .map(r -> memberRepository.findByRunIdAndCharacterId(r.getId(), charId).isPresent())
                        .orElse(false);
                assertTrue(onBoard, "融合车成员「" + b.getName() + "」上应该有这个角色");
            }

            // 重复加（前端会循环调用）不应报错
            runService.addMember(run.id(), new com.gmsr.scheduler.dto.RunDtos.MemberRequest(charId, null, null, null, null));
            for (Boss b : f.combo()) {
                long count = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .filter(r -> r.getRunIndex() == run.runIndex())
                        .mapToLong(r -> memberRepository.countByRunId(r.getId()))
                        .sum();
                assertEquals(1, count, "「" + b.getName() + "」上不应出现重复席位");
            }
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("融合车标记完成：7 个成员 BOSS 状态一起变")
    void updateStatusAppliesToAllComboBosses() {
        Fixture f = createFixture("COMBOFIN" + System.currentTimeMillis());
        try {
            var run = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            runService.updateStatus(run.id(), "DONE");

            for (Boss b : f.combo()) {
                runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                        .filter(r -> r.getRunIndex() == run.runIndex())
                        .forEach(r -> assertEquals(com.gmsr.scheduler.domain.BossRun.Status.DONE, r.getStatus(),
                                "「" + b.getName() + "」的状态应同步为已完成"));
            }
        } finally {
            cleanup(f);
        }
    }

}
