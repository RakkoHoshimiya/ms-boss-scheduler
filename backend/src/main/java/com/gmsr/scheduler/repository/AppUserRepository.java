package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    /** 通过绑定的群友找账号（改群友名时要同步账号显示名）。 */
    Optional<AppUser> findByPlayerId(Long playerId);

    /**
     * 取账号并加行锁，用于**把「刷新会话」和「撤销会话」串行化**。
     *
     * 两条路径都会锁同一行：
     *   - 刷新：锁 → 比对 sessionVersion → 签发新 token
     *   - 改密/禁用：锁 → sessionVersion + 1 → revokeAll
     * 这样不会出现「刷新先消费、撤销后执行、刷新又签出新会话」的窗口。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);
}
