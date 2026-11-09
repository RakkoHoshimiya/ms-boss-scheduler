package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.Boss;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BossRepository extends JpaRepository<Boss, Long> {

    @EntityGraph(attributePaths = "group")
    List<Boss> findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc();
}
