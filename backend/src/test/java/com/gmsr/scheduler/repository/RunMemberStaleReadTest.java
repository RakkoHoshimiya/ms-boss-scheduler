package com.gmsr.scheduler.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.GameCharacter;
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
 * 运行方式（需要真实库）：
 *   SPRING_DATASOURCE_URL=... SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... \
 *   mvn test -Dtest=RunMemberStaleReadTest
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_DB", matches = "1",
        disabledReason = "需要真实 PostgreSQL：设 GMSR_INTEGRATION_DB=1 并注入数据源")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "gmsr.voice.enabled=false",
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

        // ---- 准备数据：找两趟现有车次 + 一个现有角色，建一条临时席位 ----
        long[] ids = tx.execute(status -> {
            var runs = runRepository.findAllByOrderByBossSortOrderAscRunIndexAsc();
            BossRun src = runs.get(0);
            BossRun dst = runs.get(runs.size() - 1);
            GameCharacter character = entityManager
                    .createQuery("select c from GameCharacter c", GameCharacter.class)
                    .setMaxResults(1).getSingleResult();
            RunMember member = new RunMember();
            member.setRun(src);
            member.setCharacter(character);
            member.setSlotNo(99);
            entityManager.persist(member);
            return new long[] {member.getId(), src.getId(), dst.getId()};
        });
        long memberId = ids[0];
        long srcRunId = ids[1];
        long dstRunId = ids[2];
        assertNotEquals(srcRunId, dstRunId, "需要两趟不同的车次");

        org.springframework.transaction.TransactionStatus outer =
                transactionManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
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
            // 清理临时席位
            newTx().execute(s -> {
                memberRepository.findById(memberId).ifPresent(memberRepository::delete);
                return null;
            });
        }
        assertTrue(memberRepository.findById(memberId).isEmpty(), "临时数据应已清理");
    }
}
