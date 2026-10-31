package com.gmsr.scheduler.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CompletableFuture;
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
 * 验证「席位被并发挪走后，加锁后的重新读取能不能看到新位置」。
 *
 * 背景（第二轮评审 P1）：原来用 findById 做「加锁后重新读取」，
 * 但同一个事务里 JPA 持久化上下文（一级缓存）会直接返回第一次加载的实体，
 * **不会重新查库**，所以那个检查等于没做。
 *
 * 这个测试用**真实 PostgreSQL + 两个真实事务**证明：
 *   1. 另一个事务提交后，本事务里 findById 仍然看到旧的 run_id（缓存命中）；
 *   2. 标量投影 findRunIdById 能读到数据库当前值。
 *
 * 数据由测试自己创建（不依赖库里现有数据），空库（CI）也能跑：
 *   GMSR_INTEGRATION_DB=1 mvn test -Dtest=RunMemberStaleReadTest
 * 未设置该环境变量时跳过，避免在没有数据库的环境里失败。
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunMemberStaleReadTest {

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RunMemberRepository memberRepository;
    @Autowired
    private BossRunRepository runRepository;
    @Autowired
    private EntityManager entityManager;

    private TransactionTemplate newTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    @Test
    @DisplayName("一级缓存会让 findById 读到旧车次，投影查询能读到新值")
    void staleFirstLevelCacheIsBypassedByProjection() {
        TransactionTemplate tx = newTx();
        String tag = "STALE" + System.currentTimeMillis();

        // ---- 自建数据：一个 BOSS 下两趟车次 + 一个群友一个角色 + 一条席位 ----
        long[] ids = tx.execute(status -> {
            BossGroup group = new BossGroup();
            group.setName(tag + "_G");
            entityManager.persist(group);

            Boss boss = new Boss();
            boss.setGroup(group);
            boss.setName(tag + "_BOSS");
            boss.setCapacity(6);
            entityManager.persist(boss);

            BossRun src = new BossRun();
            src.setBoss(boss);
            src.setTier(BossTier.H);
            src.setRunIndex(1);
            entityManager.persist(src);

            BossRun dst = new BossRun();
            dst.setBoss(boss);
            dst.setTier(BossTier.H);
            dst.setRunIndex(2);
            entityManager.persist(dst);

            Player player = new Player();
            player.setNickname(tag + "_P");
            entityManager.persist(player);

            GameCharacter character = new GameCharacter();
            character.setPlayer(player);
            character.setJob("Kanna");
            character.setPower(100);
            character.setMainChar(false);
            entityManager.persist(character);

            RunMember member = new RunMember();
            member.setRun(src);
            member.setCharacter(character);
            member.setSlotNo(1);
            entityManager.persist(member);

            entityManager.flush();
            return new long[] {member.getId(), src.getId(), dst.getId(), boss.getId(), group.getId(),
                    player.getId(), character.getId()};
        });
        long memberId = ids[0];
        long srcRunId = ids[1];
        long dstRunId = ids[2];
        assertNotEquals(srcRunId, dstRunId, "需要两趟不同的车次");

        org.springframework.transaction.TransactionStatus outer = transactionManager
                .getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        try {
            // ---- 会话 A：第一次读取（此时席位在 srcRun）----
            RunMember cached = memberRepository.findById(memberId).orElseThrow();
            assertEquals(srcRunId, cached.getRun().getId(), "初始应在源车次");

            // ---- 会话 B（另一个线程、另一个事务）：把它挪到 dstRun 并提交 ----
            CompletableFuture.runAsync(() -> newTx().execute(s -> {
                RunMember other = memberRepository.findById(memberId).orElseThrow();
                other.setRun(runRepository.findById(dstRunId).orElseThrow());
                return null;
            })).join();

            // ---- 回到会话 A ----
            // 1) findById：命中一级缓存，仍返回旧车次 —— 这正是原来的 bug
            RunMember again = memberRepository.findById(memberId).orElseThrow();
            assertEquals(srcRunId, again.getRun().getId(),
                    "findById 命中一级缓存，应仍看到旧车次（证明「加锁后重新读取」无效）");

            // 2) 标量投影：真的执行 SQL，读到数据库当前值
            Long current = memberRepository.findRunIdById(memberId).orElseThrow();
            assertEquals(dstRunId, current,
                    "投影查询应读到数据库里的新位置 —— 这才是并发检查该用的读法");
        } finally {
            transactionManager.rollback(outer);
            cleanup(ids);
        }
        assertTrue(memberRepository.findById(memberId).isEmpty(), "临时数据应已清理");
    }

    /** 按依赖顺序删掉本次测试造的数据。 */
    private void cleanup(long[] ids) {
        newTx().execute(s -> {
            // 按外键依赖顺序批量删除（先删席位，再删角色/群友/车次/BOSS/分组）
            entityManager.createQuery("delete from RunMember m where m.id = :id")
                    .setParameter("id", ids[0]).executeUpdate();
            entityManager.createQuery("delete from GameCharacter c where c.id = :id")
                    .setParameter("id", ids[6]).executeUpdate();
            entityManager.createQuery("delete from Player p where p.id = :id")
                    .setParameter("id", ids[5]).executeUpdate();
            entityManager.createQuery("delete from BossRun r where r.id in (:a, :b)")
                    .setParameter("a", ids[1]).setParameter("b", ids[2]).executeUpdate();
            entityManager.createQuery("delete from Boss b where b.id = :id")
                    .setParameter("id", ids[3]).executeUpdate();
            entityManager.createQuery("delete from BossGroup g where g.id = :id")
                    .setParameter("id", ids[4]).executeUpdate();
            return null;
        });
    }
}
