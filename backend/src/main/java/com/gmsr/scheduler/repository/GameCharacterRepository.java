package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.GameCharacter;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {

    @EntityGraph(attributePaths = "player")
    List<GameCharacter> findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc();

    @EntityGraph(attributePaths = "player")
    List<GameCharacter> findByPlayerIdOrderBySortOrderAscIdAsc(Long playerId);

    long countByPlayerId(Long playerId);

    /** 删群友时一次性清掉他的角色（character.player_id 有外键，不先删会失败）。 */
    @Modifying
    @Query("delete from GameCharacter c where c.player.id = :playerId")
    void deleteByPlayerId(@Param("playerId") Long playerId);
}
