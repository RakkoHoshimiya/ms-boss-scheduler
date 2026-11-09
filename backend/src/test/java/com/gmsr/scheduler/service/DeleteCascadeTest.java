package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import com.gmsr.scheduler.security.AdminAccountInitializer;
import com.gmsr.scheduler.security.PlayerAccountInitializer;
import com.gmsr.scheduler.security.SecurityStartupCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删除操作的级联顺序。
 *
 * 背景（自查）：删群友 / 删角色 / 删分组都只删了自己那一行，
 * 没先处理外键引用（run_member.character_id、character.player_id、boss.group_id），
 * 于是：
 *   - 删有席位的角色 → 500
 *   - 删有角色的群友 → 500
 *   - 删还有 BOSS 的分组 → 500
 * 现在按「席位 → 角色 → 群友」「席位 → 车次 → BOSS → 分组」的顺序删。
 *
 * 需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeleteCascadeTest {

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
    private PlayerService playerService;
    @Autowired
    private BossService bossService;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private GameCharacterRepository characterRepository;
    @Autowired
    private BossGroupRepository groupRepository;
    @Autowired
    private BossRepository bossRepository;
    @Autowired
    private BossRunRepository runRepository;
    @Autowired
    private RunMemberRepository memberRepository;

    private record Fixture(long groupId, long bossId, long runId, long playerId, long charId) {
    }

    /** 造一条完整链路：分组 → BOSS → 车次 → 席位 ← 角色 ← 群友。 */
    private Fixture createChain(String tag) {
        BossGroup group = new BossGroup();
        group.setName(tag + "_G");
        group.setSortOrder(96);
        groupRepository.save(group);

        Boss boss = new Boss();
        boss.setGroup(group);
        boss.setName(tag + "_B");
        boss.setCapacity(6);
        boss.setSortOrder(96);
        bossRepository.save(boss);

        BossRun run = new BossRun();
        run.setBoss(boss);
        run.setTier(BossTier.H);
        run.setRunIndex(1);
        runRepository.save(run);

        Player player = new Player();
        player.setNickname(tag + "_P");
        player.setSortOrder(96);
        playerRepository.save(player);

        GameCharacter ch = new GameCharacter();
        ch.setPlayer(player);
        ch.setJob("Kanna");
        ch.setPower(100);
        characterRepository.save(ch);

        RunMember m = new RunMember();
        m.setRun(run);
        m.setCharacter(ch);
        m.setSlotNo(1);
        m.setRole(RunMember.Role.MEMBER);
        memberRepository.save(m);

        return new Fixture(group.getId(), boss.getId(), run.getId(), player.getId(), ch.getId());
    }

    @Test
    @DisplayName("删有席位的角色：先清席位再删角色")
    void deleteCharacterWithSeat() {
        Fixture f = createChain("DELCHAR" + System.currentTimeMillis());
        try {
            assertEquals(1, memberRepository.findByRunIdOrderBySlotNoAscIdAsc(f.runId()).size());

            characterRepository.findById(f.charId()).orElseThrow();
            playerService.deleteCharacter(f.charId());

            assertFalse(characterRepository.findById(f.charId()).isPresent(), "角色应已删除");
            assertEquals(0, memberRepository.findByRunIdOrderBySlotNoAscIdAsc(f.runId()).size(),
                    "席位应随之清空");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("删有角色的群友：先清席位、角色，再删群友")
    void deletePlayerWithCharacters() {
        Fixture f = createChain("DELPLAYER" + System.currentTimeMillis());
        try {
            playerService.delete(f.playerId());

            assertFalse(playerRepository.findById(f.playerId()).isPresent(), "群友应已删除");
            assertFalse(characterRepository.findById(f.charId()).isPresent(), "角色应一并删除");
            assertEquals(0, memberRepository.findByRunIdOrderBySlotNoAscIdAsc(f.runId()).size(),
                    "席位应一并清空");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("删还有 BOSS 的分组：先删车次与 BOSS，再删分组")
    void deleteGroupWithBosses() {
        Fixture f = createChain("DELGROUP" + System.currentTimeMillis());
        try {
            bossService.deleteGroup(f.groupId());

            assertFalse(groupRepository.findById(f.groupId()).isPresent(), "分组应已删除");
            assertFalse(bossRepository.findById(f.bossId()).isPresent(), "组内 BOSS 应一并删除");
            assertFalse(runRepository.findById(f.runId()).isPresent(), "车次应一并删除");
        } finally {
            cleanup(f);
        }
    }

    /** 兜底清理：测试失败时也不会留下脏数据。 */
    private void cleanup(Fixture f) {
        memberRepository.findByRunIdOrderBySlotNoAscIdAsc(f.runId()).forEach(memberRepository::delete);
        characterRepository.findById(f.charId()).ifPresent(characterRepository::delete);
        playerRepository.findById(f.playerId()).ifPresent(playerRepository::delete);
        runRepository.findById(f.runId()).ifPresent(runRepository::delete);
        bossRepository.findById(f.bossId()).ifPresent(bossRepository::delete);
        groupRepository.findById(f.groupId()).ifPresent(groupRepository::delete);
    }
}
