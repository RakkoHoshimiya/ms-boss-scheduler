package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.BossGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BossGroupRepository extends JpaRepository<BossGroup, Long> {

    List<BossGroup> findAllByOrderBySortOrderAscIdAsc();

    java.util.Optional<BossGroup> findByName(String name);
}
