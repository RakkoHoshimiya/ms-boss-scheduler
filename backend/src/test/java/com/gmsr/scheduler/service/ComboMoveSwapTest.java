package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
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
 * 融合车的「跨趟调车 / 换位」必须原子生效。
 *
 * 背景：融合车在底层是每个成员 BOSS 各一条车次。之前 move / swap 只处理单条，
 * 前端循环 7 次去补齐；只要中间某次失败，就会留下「某个 BOSS 上多一个人 / 少一个人」，
 * 于是车次一览表、我的车次、boss排班 三处数据永久对不上。
 *
 * 现在 move / swap 在同一个事务里对同一趟的所有成员 BOSS 一起生效，
 * 并且前端重复调用会变成幂等的空操作。
 *
 * 需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
class ComboMoveSwapTest {

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

    private record Fixture(long groupId, List<Boss> combo, long charA, long charB, long playerA, long playerB) {
    }

    private Fixture createFixture(String tag) {
        BossGroup group = new BossGroup();
        group.setName(tag + "_G");
        group.setSortOrder(94);
        groupRepository.save(group);

        List<Boss> combo = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Boss b = new Boss();
            b.setGroup(group);
            b.setName(tag + "_C" + i);
            b.setCapacity(6);
            b.setSortOrder(94 + i);
            b.setComboKey(tag + "-combo");
            b.setTiers("H");
            b.setTier(BossTier.H);
            bossRepository.save(b);
            combo.add(b);
        }

        Player pa = new Player();
        pa.setNickname(tag + "_PA");
        playerRepository.save(pa);
        Player pb = new Player();
        pb.setNickname(tag + "_PB");
        playerRepository.save(pb);

        GameCharacter ca = new GameCharacter();
        ca.setPlayer(pa);
        ca.setJob("Kanna");
        ca.setPower(100);
        characterRepository.save(ca);

        GameCharacter cb = new GameCharacter();
        cb.setPlayer(pb);
        cb.setJob("剑豪");
        cb.setPower(100);
        characterRepository.save(cb);

        return new Fixture(group.getId(), combo, ca.getId(), cb.getId(), pa.getId(), pb.getId());
    }

    /** 这一趟里，某个角色出现在几个成员 BOSS 上。 */
    private int countAcrossTrip(List<Boss> combo, int runIndex, long characterId) {
        int n = 0;
        for (Boss b : combo) {
            boolean onBoard = runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).stream()
                    .filter(r -> r.getRunIndex() == runIndex)
                    .anyMatch(r -> memberRepository.findByRunIdAndCharacterId(r.getId(), characterId).isPresent());
            if (onBoard) {
                n++;
            }
        }
        return n;
    }

    private long memberIdOf(Boss boss, int runIndex, long characterId) {
        var run = runRepository.findByBossIdOrderByRunIndexAsc(boss.getId()).stream()
                .filter(r -> r.getRunIndex() == runIndex)
                .findFirst().orElseThrow();
        RunMember m = memberRepository.findByRunIdAndCharacterId(run.getId(), characterId).orElseThrow();
        return m.getId();
    }

    @Test
    @DisplayName("跨趟调车：融合车 3 个成员 BOSS 一起挪")
    void moveAppliesToAllComboBosses() {
        Fixture f = createFixture("MOVEC" + System.currentTimeMillis());
        try {
            var trip1 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            var trip2 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));

            runService.addMember(trip1.id(), new MemberRequest(f.charA(), null, null, null, null));
            assertEquals(3, countAcrossTrip(f.combo(), trip1.runIndex(), f.charA()), "先确认加人已经同步到 3 个 BOSS");

            // 把 A 从第 1 趟挪到第 2 趟
            long memberId = memberIdOf(f.combo().get(0), trip1.runIndex(), f.charA());
            runService.moveMember(trip1.id(), memberId, trip2.id(), null);

            assertEquals(0, countAcrossTrip(f.combo(), trip1.runIndex(), f.charA()), "第 1 趟应该已经没有 A");
            assertEquals(3, countAcrossTrip(f.combo(), trip2.runIndex(), f.charA()), "第 2 趟的 3 个 BOSS 都应该有 A");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("换位：融合车 3 个成员 BOSS 一起对调")
    void swapAppliesToAllComboBosses() {
        Fixture f = createFixture("SWAPC" + System.currentTimeMillis());
        try {
            var trip1 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));
            var trip2 = runService.create(new RunRequest(f.combo().get(0).getId(), "H", null, null, null, null));

            runService.addMember(trip1.id(), new MemberRequest(f.charA(), null, null, null, null));
            runService.addMember(trip2.id(), new MemberRequest(f.charB(), null, null, null, null));

            long aId = memberIdOf(f.combo().get(0), trip1.runIndex(), f.charA());
            long bId = memberIdOf(f.combo().get(0), trip2.runIndex(), f.charB());
            runService.swapMembers(trip1.id(), aId, bId);

            assertEquals(3, countAcrossTrip(f.combo(), trip1.runIndex(), f.charB()), "第 1 趟的 3 个 BOSS 都应有 B");
            assertEquals(3, countAcrossTrip(f.combo(), trip2.runIndex(), f.charA()), "第 2 趟的 3 个 BOSS 都应有 A");
            assertEquals(0, countAcrossTrip(f.combo(), trip1.runIndex(), f.charA()), "第 1 趟不该再有 A");
        } finally {
            cleanup(f);
        }
    }

    private void cleanup(Fixture f) {
        for (Boss b : f.combo()) {
            runRepository.findByBossIdOrderByRunIndexAsc(b.getId()).forEach(r -> runService.delete(r.getId()));
            bossRepository.delete(b);
        }
        characterRepository.findById(f.charA()).ifPresent(characterRepository::delete);
        characterRepository.findById(f.charB()).ifPresent(characterRepository::delete);
        playerRepository.findById(f.playerA()).ifPresent(playerRepository::delete);
        playerRepository.findById(f.playerB()).ifPresent(playerRepository::delete);
        groupRepository.findById(f.groupId()).ifPresent(groupRepository::delete);
    }
}
