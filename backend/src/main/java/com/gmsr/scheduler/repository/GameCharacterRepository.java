package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.GameCharacter;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {

    @EntityGraph(attributePaths = "player")
    List<GameCharacter> findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc();

    @EntityGraph(attributePaths = "player")
    List<GameCharacter> findByPlayerIdOrderBySortOrderAscIdAsc(Long playerId);

    long countByPlayerId(Long playerId);
}
