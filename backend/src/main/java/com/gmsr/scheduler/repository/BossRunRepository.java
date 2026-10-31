package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.BossRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BossRunRepository extends JpaRepository<BossRun, Long> {

    /** 全部车次，按「BOSS 排序 → 趟次」排好。 */
    @EntityGraph(attributePaths = {"boss", "boss.group"})
    List<BossRun> findAllByOrderByBossSortOrderAscRunIndexAsc();

    /** 某个 BOSS 的车次。 */
    @EntityGraph(attributePaths = {"boss", "boss.group"})
    List<BossRun> findByBossIdOrderByRunIndexAsc(Long bossId);

    long countByBossId(Long bossId);

    @Query("select r.boss.id, count(r) from BossRun r group by r.boss.id")
    List<Object[]> countGroupedByBoss();

    /**
     * 取车次并**加行锁**（SELECT ... FOR UPDATE）。
     *
     * 为什么需要：上人 / 挪人 / 换位都是「先查人数或查是否已在车上，再插入或修改」，
     * 这种检查-然后-写入在没有锁的情况下不安全 ——
     * 两个请求同时看到最后一个空位，就会都通过校验，最后超员；
     * 同理，同一群友的两个角色也可能同时被塞进同一趟车。
     *
     * 锁住车次行后，同一趟车的并发写会串行化，上面的检查才真正成立。
     * 注意：调用方负责在**同一事务**内使用，锁才会持有到提交。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BossRun r where r.id = :id")
    Optional<BossRun> findByIdForUpdate(@Param("id") Long id);

    /** 批量加锁（换位 / 整队保存时用，避免逐条加锁导致死锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BossRun r where r.id in :ids order by r.id")
    List<BossRun> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
