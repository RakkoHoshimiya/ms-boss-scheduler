package com.gmsr.scheduler.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 验证 V2 迁移加的两条**数据库级**不变量：
 *   1. 一趟车的席位数不能超过 BOSS 容量
 *   2. 同一趟车里同一个群友只能有一个角色
 *
 * 这里刻意**绕过 RunService**，直接用 EntityManager 写库 ——
 * 就是要证明「就算有人不走应用层的检查，数据库也会拒绝」。
 *
 * 需要真实 PostgreSQL（触发器是数据库对象），设 GMSR_INTEGRATION_DB=1 才运行；
 * 空库也能跑，Flyway 会先把结构建好。
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunMemberInvariantTest {

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RunMemberRepository memberRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private record Fixture(long bossId, long runId, long groupId, long playerId,
                           long charA, long charB, long charC, long charD) {
    }

    private TransactionTemplate newTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** 容量 2 的 BOSS + 一趟车 + 三个群友（第一个有两个角色）。 */
    private Fixture createFixture(String tag) {
        return newTx().execute(status -> {
            BossGroup group = new BossGroup();
            group.setName(tag + "_G");
            entityManager.persist(group);

            Boss boss = new Boss();
            boss.setGroup(group);
            boss.setName(tag + "_BOSS");
            boss.setCapacity(2);
            entityManager.persist(boss);

            BossRun run = new BossRun();
            run.setBoss(boss);
            run.setTier(BossTier.H);
            run.setRunIndex(1);
            entityManager.persist(run);

            Player p1 = player(tag + "_P1");
            GameCharacter a = character(p1, "Kanna");
            GameCharacter b = character(p1, "剑豪");

            Player p2 = player(tag + "_P2");
            GameCharacter c = character(p2, "Kanna");

            Player p3 = player(tag + "_P3");
            GameCharacter d = character(p3, "Kanna");

            entityManager.flush();
            return new Fixture(boss.getId(), run.getId(), group.getId(), p1.getId(),
                    a.getId(), b.getId(), c.getId(), d.getId());
        });
    }

    private Player player(String nickname) {
        Player p = new Player();
        p.setNickname(nickname);
        entityManager.persist(p);
        return p;
    }

    private GameCharacter character(Player player, String job) {
        GameCharacter c = new GameCharacter();
        c.setPlayer(player);
        c.setJob(job);
        c.setPower(100);
        c.setMainChar(false);
        entityManager.persist(c);
        return c;
    }

    /** 插一个席位（独立事务，用来观察数据库是否拒绝）。 */
    private void addMember(long runId, long characterId, int slot) {
        newTx().execute(status -> {
            RunMember m = new RunMember();
            m.setRun(entityManager.find(BossRun.class, runId));
            m.setCharacter(entityManager.find(GameCharacter.class, characterId));
            m.setSlotNo(slot);
            entityManager.persist(m);
            entityManager.flush();
            return null;
        });
    }

    @Test
    @DisplayName("超过容量时数据库必须拒绝（绕过应用层检查）")
    void rejectsOverCapacity() {
        String tag = "INVCAP" + System.currentTimeMillis();
        Fixture f = createFixture(tag);
        try {
            addMember(f.runId(), f.charA(), 1);
            addMember(f.runId(), f.charC(), 2);
            assertEquals(2, memberRepository.countByRunId(f.runId()));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> addMember(f.runId(), f.charD(), 3),
                    "超过容量时应该抛出约束异常");
            assertTrue(messageChainContains(ex, "已满"),
                    "错误信息应说明车次已满，实际：" + fullMessage(ex));
        } finally {
            cleanup(f, tag);
        }
    }

    @Test
    @DisplayName("同一群友的两个角色进同一趟车时数据库必须拒绝")
    void rejectsSamePlayerTwice() {
        String tag = "INVPLAYER" + System.currentTimeMillis();
        Fixture f = createFixture(tag);
        try {
            addMember(f.runId(), f.charA(), 1);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> addMember(f.runId(), f.charB(), 2),
                    "同一群友的第二个角色应该被拒绝");
            assertTrue(messageChainContains(ex, "只能上一个角色"),
                    "错误信息应说明同人单角色，实际：" + fullMessage(ex));
        } finally {
            cleanup(f, tag);
        }
    }

    /** 沿异常链找关键字（Hibernate 会把数据库错误包一层，不同路径包的类不一样）。 */
    private static boolean messageChainContains(Throwable ex, String needle) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String fullMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private void cleanup(Fixture f, String tag) {
        newTx().execute(status -> {
            entityManager.createQuery("delete from RunMember m where m.run.id = :runId")
                    .setParameter("runId", f.runId()).executeUpdate();
            entityManager.createQuery("delete from GameCharacter c where c.player.id in "
                            + "(select p.id from Player p where p.nickname like :tag)")
                    .setParameter("tag", tag + "%").executeUpdate();
            entityManager.createQuery("delete from Player p where p.nickname like :tag")
                    .setParameter("tag", tag + "%").executeUpdate();
            entityManager.createQuery("delete from BossRun r where r.id = :id")
                    .setParameter("id", f.runId()).executeUpdate();
            entityManager.createQuery("delete from Boss b where b.id = :id")
                    .setParameter("id", f.bossId()).executeUpdate();
            entityManager.createQuery("delete from BossGroup g where g.id = :id")
                    .setParameter("id", f.groupId()).executeUpdate();
            return null;
        });
    }
}
