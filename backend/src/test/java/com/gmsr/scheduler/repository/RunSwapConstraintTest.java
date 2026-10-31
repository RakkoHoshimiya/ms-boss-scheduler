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
 * 换位相关的数据库约束行为（V3 延迟约束）。
 *
 * 背景（第四轮评审 P1）：
 *   V2 把 uq_run_slot 和容量检查做成「即时」的，结果换位在**中间状态**就被拒绝：
 *   第一条 UPDATE 把 A 挪到对方位置时，对方还占着那个位置 ——
 *   唯一索引报「座位号重复」，满员车报「已满」，即使交换后的最终阵容完全合法。
 *
 * V3 把这两项改成 DEFERRABLE（提交时才校验）。本测试用真实 PostgreSQL 验证：
 *   1. 同一群友的两个角色互换车次（座位号相同）能提交成功 —— 满车换位的典型场景
 *   2. 交换后若是非法终态（同人双角色 / 超员），提交时仍然被拒绝
 *   3. 被拒绝的事务不留部分写入
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
class RunSwapConstraintTest {

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RunMemberRepository memberRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private TransactionTemplate newTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private record Fixture(long bossId, long groupId, long runA, long runB,
                           long p1Id, long p1a, long p1b,
                           long p2Id, long p2a, long p2b,
                           long p3Id, long p3a, long p4Id, long p4a) {
    }

    /**
     * 容量 2 的 BOSS + 两趟满员车：
     *   runA: P1 的角色 A1（slot1）、P2 的角色 B1（slot2）
     *   runB: P1 的角色 A2（slot1）、P2 的角色 B2（slot2）
     * 另有 P3、P4 各一个角色（不在任何车上，用于超员测试）。
     */
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

            BossRun runA = new BossRun();
            runA.setBoss(boss);
            runA.setTier(BossTier.H);
            runA.setRunIndex(1);
            entityManager.persist(runA);

            BossRun runB = new BossRun();
            runB.setBoss(boss);
            runB.setTier(BossTier.H);
            runB.setRunIndex(2);
            entityManager.persist(runB);

            Player p1 = player(tag + "_P1");
            Player p2 = player(tag + "_P2");
            Player p3 = player(tag + "_P3");
            Player p4 = player(tag + "_P4");

            GameCharacter a1 = character(p1, "Kanna");
            GameCharacter a2 = character(p1, "剑豪");
            GameCharacter b1 = character(p2, "Kanna");
            GameCharacter b2 = character(p2, "剑豪");
            GameCharacter c1 = character(p3, "Kanna");
            GameCharacter d1 = character(p4, "Kanna");

            member(runA, a1, 1);
            member(runA, b1, 2);
            member(runB, a2, 1);
            member(runB, b2, 2);
            entityManager.flush();

            return new Fixture(boss.getId(), group.getId(), runA.getId(), runB.getId(),
                    p1.getId(), a1.getId(), a2.getId(),
                    p2.getId(), b1.getId(), b2.getId(),
                    p3.getId(), c1.getId(), p4.getId(), d1.getId());
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

    private void member(BossRun run, GameCharacter character, int slot) {
        RunMember m = new RunMember();
        m.setRun(run);
        m.setCharacter(character);
        m.setSlotNo(slot);
        entityManager.persist(m);
    }

    private long memberId(long runId, long characterId) {
        return memberRepository.findByRunIdAndCharacterId(runId, characterId).orElseThrow().getId();
    }

    @Test
    @DisplayName("同一群友的两个角色互换车次（座位号相同）必须能提交 —— 这是换位的核心场景")
    void swapSamePlayerCharactersCommits() {
        String tag = "SWAPP1" + System.currentTimeMillis();
        Fixture f = createFixture(tag);
        try {
            // P1 的角色 A1 在 runA(slot1)，A2 在 runB(slot1) —— 交换必然短暂撞同一座位
            long m1 = memberId(f.runA(), f.p1a());
            long m2 = memberId(f.runB(), f.p1b());

            newTx().execute(status -> {
                RunMember a = entityManager.find(RunMember.class, m1);
                RunMember b = entityManager.find(RunMember.class, m2);
                BossRun runA = a.getRun();
                BossRun runB = b.getRun();
                int slotA = a.getSlotNo();
                int slotB = b.getSlotNo();
                a.setRun(runB);
                a.setSlotNo(slotB);
                b.setRun(runA);
                b.setSlotNo(slotA);
                entityManager.flush();
                return null;
            });

            assertEquals(f.runB(), memberRepository.findRunIdById(m1).orElseThrow(),
                    "A1 应该换到 runB");
            assertEquals(f.runA(), memberRepository.findRunIdById(m2).orElseThrow(),
                    "A2 应该换到 runA");
            // 两趟车仍然各 2 人，且各自只有一个 P1 角色
            assertEquals(2, memberRepository.countByRunId(f.runA()));
            assertEquals(2, memberRepository.countByRunId(f.runB()));
            assertEquals(1, memberRepository.findByRunIdAndCharacterPlayerId(f.runA(), f.p1Id()).size());
            assertEquals(1, memberRepository.findByRunIdAndCharacterPlayerId(f.runB(), f.p1Id()).size());
        } finally {
            cleanup(f, tag);
        }
    }

    @Test
    @DisplayName("交换后如果同一群友在同一车次出现两个角色，提交必须失败")
    void swapThatCreatesDuplicatePlayerFails() {
        String tag = "SWAPBAD" + System.currentTimeMillis();
        Fixture f = createFixture(tag);
        try {
            long a2 = memberId(f.runB(), f.p1b());
            long b1 = memberId(f.runA(), f.p2a());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> newTx().execute(status -> {
                        // B1 让位离开 runA，P1 的 A2 补进来 → runA 里出现 P1 的两个角色
                        entityManager.remove(entityManager.find(RunMember.class, b1));
                        RunMember m = entityManager.find(RunMember.class, a2);
                        m.setRun(entityManager.find(BossRun.class, f.runA()));
                        m.setSlotNo(2);
                        entityManager.flush();
                        return null;
                    }),
                    "同人双角色的终态应该在提交时被拒绝");
            assertTrue(chainContains(ex, "只能上一个角色"),
                    "错误信息应说明同人单角色，实际：" + chainMessage(ex));

            // 失败事务不应留下部分写入
            assertEquals(f.runB(), memberRepository.findRunIdById(a2).orElseThrow(),
                    "失败后 A2 应该还在 runB");
            assertEquals(f.runA(), memberRepository.findRunIdById(b1).orElseThrow(),
                    "失败后 B1 应该还在 runA");
            assertEquals(2, memberRepository.countByRunId(f.runA()));
        } finally {
            cleanup(f, tag);
        }
    }

    @Test
    @DisplayName("交换后如果某趟车超员，提交必须失败且不留部分写入")
    void swapThatOverfillsFails() {
        String tag = "SWAPOVER" + System.currentTimeMillis();
        Fixture f = createFixture(tag);
        try {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> newTx().execute(status -> {
                        // 往已满（2/2）的 runA 里再塞两个不同群友的角色
                        BossRun runA = entityManager.find(BossRun.class, f.runA());
                        member(runA, entityManager.find(GameCharacter.class, f.p3a()), 3);
                        member(runA, entityManager.find(GameCharacter.class, f.p4a()), 4);
                        entityManager.flush();
                        return null;
                    }),
                    "超员终态应该在提交时被拒绝");
            assertTrue(chainContains(ex, "已满"),
                    "错误信息应说明已满，实际：" + chainMessage(ex));

            assertEquals(2, memberRepository.countByRunId(f.runA()), "runA 不应被改动");
        } finally {
            cleanup(f, tag);
        }
    }

    private void cleanup(Fixture f, String tag) {
        newTx().execute(status -> {
            entityManager.createQuery("delete from RunMember m where m.run.id in (:a, :b)")
                    .setParameter("a", f.runA()).setParameter("b", f.runB()).executeUpdate();
            entityManager.createQuery("delete from GameCharacter c where c.player.id in "
                            + "(select p.id from Player p where p.nickname like :tag)")
                    .setParameter("tag", tag + "%").executeUpdate();
            entityManager.createQuery("delete from Player p where p.nickname like :tag")
                    .setParameter("tag", tag + "%").executeUpdate();
            entityManager.createQuery("delete from BossRun r where r.id in (:a, :b)")
                    .setParameter("a", f.runA()).setParameter("b", f.runB()).executeUpdate();
            entityManager.createQuery("delete from Boss b where b.id = :id")
                    .setParameter("id", f.bossId()).executeUpdate();
            entityManager.createQuery("delete from BossGroup g where g.id = :id")
                    .setParameter("id", f.groupId()).executeUpdate();
            return null;
        });
    }

    private static boolean chainContains(Throwable ex, String needle) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String chainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
