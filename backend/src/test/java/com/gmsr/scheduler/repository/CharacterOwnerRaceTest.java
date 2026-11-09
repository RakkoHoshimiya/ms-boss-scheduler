package com.gmsr.scheduler.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「给还没上车的角色加席位」与「把这个角色改给别人」并发时的 write skew。
 *
 * 背景（第六轮评审 P1）：
 *   V4 的属主触发器只锁「该角色当前已提交的席位所在 BOSS」。
 *   角色还没上车时它找不到要锁的 BOSS，于是两个事务各自看到旧状态：
 *     A：把甲的角色 C 加进一趟已有乙角色的车（未提交）
 *     B：把 C 的属主改成乙（看不到 A 的席位 → 不锁 BOSS，也不报冲突）
 *   两者都通过 → 车上出现乙的两个角色。
 *
 * V5 引入**角色级锁**：加席位和改属主都先锁这个角色，顺序固定为「角色 → BOSS」。
 * 本测试用两个真实连接 + 屏障制造上述交错，断言：
 *   - 最终同一趟车里同一个群友的角色数 ≤ 1
 *   - 两个操作不会同时成功
 *
 * 需要真实 PostgreSQL：GMSR_INTEGRATION_DB=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CharacterOwnerRaceTest {

    @Autowired
    private DataSource dataSource;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private record Fixture(long groupId, long bossId, long runId, long charC, long playerB) {
    }

    /**
     * BOSS 容量 3；一趟车已经有玩家 B 的角色 B1；玩家 A 的角色 C **还没上车**。
     */
    private Fixture createFixture(String tag) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long groupId = insertReturning(c,
                    "insert into boss_group (name, sort_order, created_at, updated_at) values (?, 97, now(), now())",
                    tag + "_G");
            long bossId = insertReturning(c,
                    "insert into boss (group_id, name, tier, capacity, sort_order, enabled, created_at, updated_at) "
                            + "values (?, ?, 'H', 3, 97, true, now(), now())",
                    groupId, tag + "_BOSS");
            long runId = insertReturning(c,
                    "insert into boss_run (boss_id, tier, run_index, status, created_at, updated_at) "
                            + "values (?, 'H', 1, 'PLANNED', now(), now())", bossId);

            long playerA = player(c, tag + "_PA");
            long playerB = player(c, tag + "_PB");
            long charC = character(c, playerA, "Kanna");   // 甲的 C，尚未上车
            long charB1 = character(c, playerB, "Kanna");  // 乙的 B1，已经在车上

            member(c, runId, charB1, 1);
            c.commit();
            return new Fixture(groupId, bossId, runId, charC, playerB);
        }
    }

    private long player(Connection c, String nickname) throws Exception {
        return insertReturning(c,
                "insert into player (display_name, active, sort_order, created_at, updated_at) "
                        + "values (?, true, 97, now(), now())", nickname);
    }

    private long character(Connection c, long playerId, String job) throws Exception {
        return insertReturning(c,
                "insert into \"character\" (player_id, job, power, main_char, sort_order, created_at, updated_at) "
                        + "values (?, ?, 100, false, 97, now(), now())", playerId, job);
    }

    private long member(Connection c, long runId, long charId, int slot) throws Exception {
        return insertReturning(c,
                "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'MEMBER', 'CONFIRMED', now(), now())", runId, charId, slot);
    }

    private void insert(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
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

    private boolean runInTransaction(CyclicBarrier barrier, TxWork work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 先汇合再动手：两个事务同时发起，剩下的交给数据库的锁去串行化
                barrier.await(15, TimeUnit.SECONDS);
                work.run(c);
                c.commit();
                return true;
            } catch (Exception ex) {
                c.rollback();
                System.out.println("    [事务失败] " + rootMessage(ex));
                return false;
            }
        } catch (Exception ex) {
            System.out.println("    [连接失败] " + rootMessage(ex));
            return false;
        }
    }

    @FunctionalInterface
    private interface TxWork {
        void run(Connection c) throws Exception;
    }

    @Test
    @DisplayName("给未上车的角色加席位 与 改属主 并发：不出现同人双角色")
    void addSeatVersusOwnerChange() throws Exception {
        Fixture f = createFixture("OWNER" + System.currentTimeMillis());
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);

            // A：把角色 C（属于甲）加进这趟车
            var addSeat = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                            + "values (?, ?, 2, 'MEMBER', 'CONFIRMED', now(), now())", f.runId(), f.charC())), pool);

            // B：把角色 C 改属到乙名下（此时乙已经在这趟车上有一个角色）
            var changeOwner = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "update \"character\" set player_id = ?, updated_at = now() where id = ?",
                            f.playerB(), f.charC())), pool);

            boolean seatOk = addSeat.get(60, TimeUnit.SECONDS);
            boolean ownerOk = changeOwner.get(60, TimeUnit.SECONDS);

            long bCharsInRun = queryLong(
                    "select count(*) from run_member m join \"character\" c on c.id = m.character_id "
                            + "where m.run_id = " + f.runId() + " and c.player_id = " + f.playerB());

            System.out.println("  加席位=" + seatOk + "  改属主=" + ownerOk
                    + "  车上乙的角色数=" + bCharsInRun);

            assertTrue(bCharsInRun <= 1,
                    "不变量被破坏：同一趟车里乙有 " + bCharsInRun + " 个角色");
            assertTrue(!(seatOk && ownerOk),
                    "两个操作都成功了 —— 说明角色级锁没有生效");
            assertTrue(seatOk || ownerOk, "至少应有一个成功");
        } finally {
            cleanup(f);
            pool.shutdownNow();
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

    private void cleanup(Fixture f) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("delete from run_member where run_id = " + f.runId());
            st.executeUpdate("delete from \"character\" where player_id in "
                    + "(select id from player where display_name like 'OWNER%')");
            st.executeUpdate("delete from player where display_name like 'OWNER%'");
            st.executeUpdate("delete from boss_run where id = " + f.runId());
            st.executeUpdate("delete from boss where id = " + f.bossId());
            st.executeUpdate("delete from boss_group where id = " + f.groupId());
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = String.valueOf(t.getMessage());
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
