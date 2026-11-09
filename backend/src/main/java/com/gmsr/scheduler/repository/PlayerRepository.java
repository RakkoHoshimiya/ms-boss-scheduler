package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findAllByOrderBySortOrderAscIdAsc();

    Optional<Player> findByNickname(String nickname);

    Optional<Player> findByQq(String qq);
}
