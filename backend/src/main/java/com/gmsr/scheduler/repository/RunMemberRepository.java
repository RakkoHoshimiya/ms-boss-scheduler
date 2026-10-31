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

    /**
     * 只查「这个席位现在在哪趟车上」。
     *
     * 为什么不用 findById().getRun().getId()：
     * JPA 的持久化上下文（一级缓存）会把同一个事务里第一次读到的实体缓存起来，
     * 后续 findById 直接返回缓存实例、**不会重新查库** ——
     * 于是「加锁后重新读取」等于没读，另一个事务在此期间把席位挪走也发现不了。
     * 标量投影查询会真的执行 SQL，能拿到数据库当前值。
     */
    @Query("select m.run.id from RunMember m where m.id = :id")
    Optional<Long> findRunIdById(@Param("id") Long id);

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
