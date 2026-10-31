package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.security.AdminAccountInitializer;
import com.gmsr.scheduler.security.PlayerAccountInitializer;
import com.gmsr.scheduler.security.SecurityStartupCheck;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 直接调用 {@link RunService#swapMembers} —— 覆盖用户点「换位」时真正走的代码路径。
 *
 * 为什么单独写：之前的换位测试要么直接写 SQL（{@code RunSwapConstraintTest}），
 * 要么直接操作实体，都**绕过了 service 层**。
 * 而 service 里还有几条业务规则没被验证过：
 *   - 只能在同一 BOSS 的车次之间换位
 *   - 换位后两边都不能出现「同一个群友的两个角色」
 *   - 满车换位要靠数据库延迟约束才能通过
 *
 * 这里用完整 Spring 上下文（真实 PostgreSQL），把启动时的种子/迁移/账号初始化
 * 全部 mock 掉，避免测试互相干扰。
 *
 * 需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gmsr.voice.enabled=false",
})
class RunServiceSwapTest {

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
    private DataSource dataSource;

    /** mA1/mA2 是 P1 的两个角色所在的**席位 id**；mB2 是 P2 的 B2 席位 id。 */
    private record Fixture(long groupId, long bossId, long boss2Id, long runA, long runB,
                           long mA1, long mA2, long mB2) {
    }

    /**
     * 容量 2 的 BOSS1（两趟车都满）+ 一个容量 2 的 BOSS2（用于跨 BOSS 场景）。
     * runA: P1 的 A1(slot1)、P2 的 B1(slot2)
     * runB: P1 的 A2(slot1)、P2 的 B2(slot2)
     */
    private Fixture createFixture(String tag) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long groupId = insertReturning(c,
                    "insert into boss_group (name, sort_order, created_at, updated_at) values (?, 98, now(), now())",
                    tag + "_G");
            long bossId = insertReturning(c,
                    "insert into boss (group_id, name, tier, capacity, sort_order, enabled, created_at, updated_at) "
                            + "values (?, ?, 'H', 2, 98, true, now(), now())",
                    groupId, tag + "_BOSS");
            long boss2Id = insertReturning(c,
                    "insert into boss (group_id, name, tier, capacity, sort_order, enabled, created_at, updated_at) "
                            + "values (?, ?, 'H', 2, 99, true, now(), now())",
                    groupId, tag + "_BOSS2");
            long runA = insertReturning(c,
                    "insert into boss_run (boss_id, tier, run_index, status, created_at, updated_at) "
                            + "values (?, 'H', 1, 'PLANNED', now(), now())", bossId);
            long runB = insertReturning(c,
                    "insert into boss_run (boss_id, tier, run_index, status, created_at, updated_at) "
                            + "values (?, 'H', 2, 'PLANNED', now(), now())", bossId);

            long p1 = player(c, tag + "_P1");
            long p2 = player(c, tag + "_P2");
            long a1 = character(c, p1, "Kanna");
            long a2 = character(c, p1, "剑豪");
            long b1 = character(c, p2, "Kanna");
            long b2 = character(c, p2, "剑豪");

            long mA1 = member(c, runA, a1, 1);
            member(c, runA, b1, 2);
            long mA2 = member(c, runB, a2, 1);
            long mB2 = member(c, runB, b2, 2);
            c.commit();
            return new Fixture(groupId, bossId, boss2Id, runA, runB, mA1, mA2, mB2);
        }
    }

    private long player(Connection c, String nickname) throws Exception {
        return insertReturning(c,
                "insert into player (display_name, active, sort_order, created_at, updated_at) "
                        + "values (?, true, 98, now(), now())", nickname);
    }

    private long character(Connection c, long playerId, String job) throws Exception {
        return insertReturning(c,
                "insert into \"character\" (player_id, job, power, main_char, sort_order, created_at, updated_at) "
                        + "values (?, ?, 100, false, 98, now(), now())", playerId, job);
    }

    private long member(Connection c, long runId, long charId, int slot) throws Exception {
        return insertReturning(c,
                "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'MEMBER', 'CONFIRMED', now(), now())", runId, charId, slot);
    }

    private long insertReturning(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long queryLong(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long runOf(long memberId) throws Exception {
        return queryLong("select run_id from run_member where id = " + memberId);
    }

    @Test
    @DisplayName("满车换位：两趟车各 2 人，交换后仍在原车且人数不变")
    void swapBetweenFullRuns() throws Exception {
        Fixture f = createFixture("SWAP_SVC" + System.currentTimeMillis());
        try {
            assertEquals(2, runService.get(f.runA()).memberCount(), "runA 应为满员 2/2");
            assertEquals(2, runService.get(f.runB()).memberCount(), "runB 应为满员 2/2");

            // P1 的两个角色互换车次（座位号相同，会短暂冲突 → 靠延迟约束放行）
            var result = runService.swapMembers(f.mA1(), f.mA2());

            assertEquals(f.runB(), runOf(f.mA1()), "A1 应换到 runB");
            assertEquals(f.runA(), runOf(f.mA2()), "A2 应换到 runA");
            assertEquals(2, result.get("source").memberCount());
            assertEquals(2, result.get("target").memberCount());
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("跨 BOSS 换位必须被 service 拒绝")
    void swapAcrossBossesRejected() throws Exception {
        Fixture f = createFixture("SWAP_BOSS" + System.currentTimeMillis());
        try {
            // 把 runB 改挂到另一个 BOSS 上
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("update boss_run set boss_id = " + f.boss2Id() + " where id = " + f.runB());
            }
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> runService.swapMembers(f.mA1(), f.mA2()));
            assertTrue(ex.getMessage().contains("同一 BOSS"), "实际提示：" + ex.getMessage());
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("换位导致同人双角色时必须被拒绝，且两边都不变")
    void swapCreatingDuplicatePlayerRejected() throws Exception {
        Fixture f = createFixture("SWAP_DUP" + System.currentTimeMillis());
        try {
            // A1(P1, runA) 与 B2(P2, runB) 互换：
            // runA 会同时出现 P2 的 B1、B2 两个角色 → 非法
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> runService.swapMembers(f.mA1(), f.mB2()));
            assertTrue(ex.getMessage().contains("只能上一个") || ex.getMessage().contains("不能"),
                    "实际提示：" + ex.getMessage());

            assertEquals(f.runA(), runOf(f.mA1()), "A1 不应移动");
            assertEquals(2, runService.get(f.runA()).memberCount());
            assertEquals(2, runService.get(f.runB()).memberCount());
        } finally {
            cleanup(f);
        }
    }

    private void cleanup(Fixture f) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("delete from run_member where run_id in (" + f.runA() + "," + f.runB() + ")");
            st.executeUpdate("delete from \"character\" where player_id in "
                    + "(select id from player where display_name like 'SWAP\\_%')");
            st.executeUpdate("delete from player where display_name like 'SWAP\\_%'");
            st.executeUpdate("delete from boss_run where id in (" + f.runA() + "," + f.runB() + ")");
            st.executeUpdate("delete from boss where id in (" + f.bossId() + "," + f.boss2Id() + ")");
            st.executeUpdate("delete from boss_group where id = " + f.groupId());
        }
    }
}
