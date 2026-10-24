package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.RunMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunMemberRepository extends JpaRepository<RunMember, Long> {

    @EntityGraph(attributePaths = {"character", "character.player"})
    List<RunMember> findByRunIdOrderBySlotNoAscIdAsc(Long runId);

    @EntityGraph(attributePaths = {"character", "character.player", "run"})
    @Query("select m from RunMember m where m.run.id in :runIds order by m.slotNo asc, m.id asc")
    List<RunMember> findByRunIds(@Param("runIds") Collection<Long> runIds);

    Optional<RunMember> findByRunIdAndCharacterId(Long runId, Long characterId);

    /** 这趟车上某个群友（玩家）已经上了哪些角色 —— 用于「同一趟车只能上一个自己的角色」。 */
    @EntityGraph(attributePaths = {"character", "character.player"})
    List<RunMember> findByRunIdAndCharacterPlayerId(Long runId, Long playerId);

    long countByCharacterId(Long characterId);

    long countByRunId(Long runId);

    @EntityGraph(attributePaths = {"character", "character.player", "run", "run.boss", "run.boss.group"})
    List<RunMember> findByCharacterIdOrderByIdAsc(Long characterId);

    @EntityGraph(attributePaths = {"character", "character.player", "run", "run.boss", "run.boss.group"})
    List<RunMember> findByCharacterPlayerIdOrderByIdAsc(Long playerId);


    @Query("select m.run.id, count(m) from RunMember m group by m.run.id")
    List<Object[]> countGroupedByRun();

    @Modifying
    @Query("delete from RunMember m where m.run in :runs")
    void deleteByRunIn(@Param("runs") Collection<BossRun> runs);
}
