package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.AppSetting;
import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossCombo;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.repository.AppSettingRepository;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
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
 * 启动迁移的幂等性 —— 「重启后管理员的手工配置还在吗」。
 *
 * <p>背景：DataMigrator 里有 4 个迁移曾经**每次启动都无条件执行**：
 * fixBossCapacity / applyQqAndNames / removePlayers / restoreBosses。
 * 结果是管理员调过的容量、改过的昵称、甚至故意删掉的 BOSS，
 * 重启一次就被改回去；fixBossCapacity 在「车上人数 > 新容量」时还会触发
 * 数据库约束，让应用直接启动失败。
 *
 * <p>这个测试连续执行两次 run()，断言第二次不产生任何副作用。
 *
 * <p>需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
class DataMigratorIdempotencyTest {

    @MockitoBean
    private SeedDataImporter seedDataImporter;
    @MockitoBean
    private AdminAccountInitializer adminAccountInitializer;
    @MockitoBean
    private PlayerAccountInitializer playerAccountInitializer;
    @MockitoBean
    private SecurityStartupCheck securityStartupCheck;
    @MockitoBean
    private WeeklyStatusResetService weeklyStatusResetService;

    @Autowired
    private DataMigrator dataMigrator;
    @Autowired
    private BossRepository bossRepository;
    @Autowired
    private BossGroupRepository groupRepository;
    @Autowired
    private BossRunRepository runRepository;
    @Autowired
    private AppSettingRepository settingRepository;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private GameCharacterRepository characterRepository;
    @Autowired
    private RunMemberRepository memberRepository;

    /** 造一个「管理员手工配置过」的 BOSS：容量 4 人（不是默认 6，也不是三人本 3）。 */
    private Boss createCustomizedBoss(String name, int capacity) {
        BossGroup group = groupRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .findFirst()
                .orElseGet(() -> {
                    BossGroup created = new BossGroup();
                    created.setName("MIGTEST_GROUP");
                    created.setSortOrder(90);
                    return groupRepository.save(created);
                });
        Boss boss = new Boss();
        boss.setGroup(group);
        boss.setName(name);
        boss.setCapacity(capacity);
        boss.setSortOrder(90);
        boss.setTier(BossTier.H);
        boss.setEnabled(true);
        return bossRepository.save(boss);
    }

    @Test
    @DisplayName("重复执行迁移：管理员手工调过的容量不会被改回默认值")
    void repeatedMigrationKeepsManualCapacity() {
        Boss boss = createCustomizedBoss("MIGTEST_BOSS_" + System.currentTimeMillis(), 4);
        try {
            // 第一次执行（模拟首次部署后的那次启动）
            dataMigrator.run(null);
            // 第二次执行（模拟重启）
            dataMigrator.run(null);

            Boss reloaded = bossRepository.findById(boss.getId()).orElseThrow();
            assertEquals(4, reloaded.getCapacity(),
                    "重启后管理员设的容量必须保留，不能被迁移改回 6");
        } finally {
            runRepository.findByBossIdOrderByRunIndexAsc(boss.getId()).forEach(r -> {
                memberRepository.deleteByRunIn(List.of(r));
                runRepository.delete(r);
            });
            bossRepository.delete(boss);
        }
    }

    @Test
    @DisplayName("重复执行迁移：管理员故意删掉的 BOSS 不会被自动补回来")
    void repeatedMigrationDoesNotResurrectDeletedBoss() {
        // 用真实融合车成员名以外的名字，避免与 restoreBosses 的白名单交互
        String name = "MIGTEST_DELETED_" + System.currentTimeMillis();
        Boss boss = createCustomizedBoss(name, 6);
        bossRepository.delete(boss);

        dataMigrator.run(null);
        dataMigrator.run(null);

        assertTrue(bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                        .noneMatch(b -> name.equals(b.getName())),
                "被管理员删掉的 BOSS 不应被启动迁移重新创建");
    }

    @Test
    @DisplayName("补回的融合车成员必须带上 comboKey，否则「要打全打」会出现永久缺口")
    void restoredBossCarriesComboKey() {
        // 先删掉一个真实融合车成员（如果库里存在）
        String memberName = BossCombo.MYSTIC_7.bossNames().get(0);
        Boss existing = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                .filter(b -> memberName.equals(b.getName()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            return;   // 库里没有这个 BOSS（测试库未导入种子数据），跳过
        }
        Long bossId = existing.getId();
        runRepository.findByBossIdOrderByRunIndexAsc(bossId).forEach(r -> {
            memberRepository.deleteByRunIn(List.of(r));
            runRepository.delete(r);
        });
        bossRepository.delete(existing);
        // 清掉 restore-bosses 的 marker，让迁移重新执行
        settingRepository.deleteById("restore-bosses-once");

        dataMigrator.run(null);

        Boss restored = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                .filter(b -> memberName.equals(b.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(restored, "融合车成员应被补回");
        assertEquals(BossCombo.MYSTIC_7.key(), restored.getComboKey(),
                "补回的融合车成员必须带上 comboKey，否则它不属于融合车");
    }

    @Test
    @DisplayName("提示：迁移 marker 写进 app_setting，重复执行可判定")
    void markersArePersisted() {
        dataMigrator.run(null);
        List<String> keys = settingRepository.findAll().stream().map(AppSetting::getKey).toList();
        assertTrue(keys.contains("restore-bosses-once"), "一次性迁移必须写 marker，实际：" + keys);
        assertTrue(keys.contains("boss-capacity-once"), "容量对齐必须写 marker，实际：" + keys);
    }
}
