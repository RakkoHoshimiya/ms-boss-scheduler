package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.BossRun;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BossRunRepository extends JpaRepository<BossRun, Long> {

    /** 全部周期（旧接口兼容，按 BOSS 顺序）。 */
    @EntityGraph(attributePaths = {"boss", "boss.group", "week"})
    List<BossRun> findAllByOrderByBossSortOrderAscRunIndexAsc();

    /** 某个周期的全部车次。 */
    @EntityGraph(attributePaths = {"boss", "boss.group", "week"})
    List<BossRun> findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(String weekKey);

    /** 只用于统计，按 id 排序即可（run_index 是字符串，排序语义不重要）。 */
    List<BossRun> findByWeekWeekKeyOrderByIdAsc(String weekKey);

    /** 某个 BOSS 的车次（可按周期过滤）。 */
    @EntityGraph(attributePaths = {"boss", "boss.group", "week"})
    List<BossRun> findByBossIdOrderByRunIndexAsc(Long bossId);

    long countByBossId(Long bossId);

    long countByWeekWeekKey(String weekKey);

    long countByWeekIsNull();

    @Query("select r.boss.id, count(r) from BossRun r group by r.boss.id")
    List<Object[]> countGroupedByBoss();
}
