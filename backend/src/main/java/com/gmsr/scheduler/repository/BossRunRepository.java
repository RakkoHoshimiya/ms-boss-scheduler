package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.BossRun;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
