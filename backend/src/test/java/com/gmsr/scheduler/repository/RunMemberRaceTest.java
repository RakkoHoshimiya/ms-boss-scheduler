package com.gmsr.scheduler.repository;

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
 * 「加人」与「改容量」并发时的不变量（V4 的 BOSS 级咨询锁）。
 *
 * 背景（第五轮评审 P1）：
 *   V3 的三个触发器各自 count，但看不到对方**未提交**的修改：
 *   事务 A 加人（未提交）+ 事务 B 缩小容量（未提交）→ 两边都通过 → 提交后超员。
 *
 * V4 让所有相关路径先抢同一把 BOSS 级咨询锁（pg_advisory_xact_lock），
 * 抢到锁再计数，从而串行化。
 *
 * 本测试用**两个真实数据库连接 + 同步屏障**制造交错：
 *   两条连接各自 BEGIN → 做写操作 → 在屏障处汇合（此时两边都还没提交）→ 再提交。
 * 用原生 JDBC 而不是 JPA，是为了精确控制事务边界、避免持久化上下文的干扰。
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
class RunMemberRaceTest {

    @Autowired
    private DataSource dataSource;

    /**
     * 专用线程池：ForkJoinPool.commonPool() 在核数少的机器上并行度可能只有 1，
     * 两个「并发」任务实际会串行执行 —— 那样屏障永远等不到第二方。
     */
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private record Fixture(long bossId, long runId, long char1, long char2, long char3, long char4) {
    }

    /**
     * 准备数据（独立事务、直接 JDBC）：
     * 一个容量 3 的 BOSS、一趟已坐 2 人的车、4 个不同群友的角色。
     */
    private Fixture createFixture(String tag) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            long groupId = insertReturning(c,
                    "insert into boss_group (name, sort_order, created_at, updated_at) values (?, 99, now(), now())",
                    tag + "_G");
            long bossId = insertReturning(c,
                    "insert into boss (group_id, name, tier, capacity, sort_order, enabled, created_at, updated_at) "
                            + "values (?, ?, 'H', 3, 99, true, now(), now())",
                    groupId, tag + "_BOSS");
            long runId = insertReturning(c,
                    "insert into boss_run (boss_id, tier, run_index, status, created_at, updated_at) "
                            + "values (?, 'H', 1, 'PLANNED', now(), now())",
                    bossId);
            long[] chars = new long[4];
            for (int i = 0; i < 4; i++) {
                long playerId = insertReturning(c,
                        "insert into player (display_name, active, sort_order, created_at, updated_at) "
                                + "values (?, true, 99, now(), now())",
                        tag + "_P" + i);
                chars[i] = insertReturning(c,
                        "insert into \"character\" (player_id, job, power, main_char, sort_order, created_at, updated_at) "
                                + "values (?, 'Kanna', 100, false, 99, now(), now())",
                        playerId);
            }
            insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                    + "values (?, ?, 1, 'MEMBER', 'CONFIRMED', now(), now())", runId, chars[0]);
            insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                    + "values (?, ?, 2, 'MEMBER', 'CONFIRMED', now(), now())", runId, chars[1]);
            c.commit();
            return new Fixture(bossId, runId, chars[0], chars[1], chars[2], chars[3]);
        }
    }

    private long insertReturning(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insert(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    private void bind(PreparedStatement ps, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /**
     * 在一个真实事务里执行 work，然后与另一个线程在屏障处汇合，再提交。
     *
     * @return true 表示提交成功；false 表示被数据库约束拒绝或屏障超时
     */
    private boolean runInTransaction(CyclicBarrier barrier, TxWork work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                // 关键：两边都写完了、但都还没提交，此时汇合
                barrier.await(15, TimeUnit.SECONDS);
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
    @DisplayName("加人 与 缩小容量 并发：最多一个成功，且最终不超员")
    void addMemberVersusShrinkCapacity() throws Exception {
        Fixture f = createFixture("RACE" + System.currentTimeMillis());
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);

            var add = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                            + "values (?, ?, 3, 'MEMBER', 'CONFIRMED', now(), now())", f.runId(), f.char3())), pool);

            var shrink = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "update boss set capacity = 2, updated_at = now() where id = ?", f.bossId())), pool);

            boolean addOk = add.get(60, TimeUnit.SECONDS);
            boolean shrinkOk = shrink.get(60, TimeUnit.SECONDS);

            long members = queryLong("select count(*) from run_member where run_id = " + f.runId());
            long capacity = queryLong("select capacity from boss where id = " + f.bossId());

            System.out.println("  加人成功=" + addOk + "  缩容成功=" + shrinkOk
                    + "  最终 " + members + "/" + capacity);

            assertTrue(members <= capacity,
                    "不变量被破坏：席位数 " + members + " 超过容量 " + capacity);
            assertTrue(!(addOk && shrinkOk),
                    "两个操作都成功了 —— 说明并发检查被绕过");
            assertTrue(addOk || shrinkOk, "至少应有一个成功");
        } finally {
            cleanup(f);
        }
    }

    @Test
    @DisplayName("两个并发加人抢最后一个空位：最多一个成功")
    void twoConcurrentAddsToLastSlot() throws Exception {
        Fixture f = createFixture("RACE2" + System.currentTimeMillis());
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);

            var first = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                            + "values (?, ?, 3, 'MEMBER', 'CONFIRMED', now(), now())", f.runId(), f.char3())), pool);
            var second = CompletableFuture.supplyAsync(() -> runInTransaction(barrier, c ->
                    insert(c, "insert into run_member (run_id, character_id, slot_no, role, status, created_at, updated_at) "
                            + "values (?, ?, 3, 'MEMBER', 'CONFIRMED', now(), now())", f.runId(), f.char4())), pool);

            boolean a = first.get(60, TimeUnit.SECONDS);
            boolean b = second.get(60, TimeUnit.SECONDS);
            long members = queryLong("select count(*) from run_member where run_id = " + f.runId());

            System.out.println("  加人1=" + a + "  加人2=" + b + "  最终 " + members + " 人（容量 3）");

            assertTrue(members <= 3, "超员：" + members + " > 3");
            assertTrue(!(a && b), "最后一个空位不应被两个事务同时拿到");
        } finally {
            cleanup(f);
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
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("delete from run_member where run_id = " + f.runId());
                st.executeUpdate("delete from \"character\" where id in (" + f.char1() + ","
                        + f.char2() + "," + f.char3() + "," + f.char4() + ")");
                st.executeUpdate("delete from player where display_name like 'RACE%'");
                st.executeUpdate("delete from boss_run where id = " + f.runId());
                st.executeUpdate("delete from boss where id = " + f.bossId());
                st.executeUpdate("delete from boss_group where name like 'RACE%'");
            }
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
