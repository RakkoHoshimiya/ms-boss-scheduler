package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    /** 通过绑定的群友找账号（改群友名时要同步账号显示名）。 */
    Optional<AppUser> findByPlayerId(Long playerId);
}
