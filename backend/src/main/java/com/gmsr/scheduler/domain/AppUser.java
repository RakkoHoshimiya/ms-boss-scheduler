package com.gmsr.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "app_user")
public class AppUser extends AuditedEntity {

    public enum Role {
        ADMIN, EDITOR, VIEWER
    }

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.VIEWER;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * 是否有 boss排班 权限（管理员天然拥有）。
     * 群友账号默认可排班，但账号管理 / 系统设置仍然只有 ADMIN 能进。
     */
    @Column(name = "can_schedule", nullable = false)
    private boolean canSchedule = true;

    /** 是否必须修改密码（首次登录 / 管理员重置后为 true）。 */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * 会话版本。改密 / 重置 / 禁用 / 改权限时 +1。
     *
     * 作用：refresh token 里带着签发时的版本号，刷新时和这里的值比对。
     * 这样即使「刷新」与「撤销」并发，晚到的那次刷新也会因为版本对不上被拒绝，
     * 不会凭空造出一个绕过撤销的新会话。
     */
    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    public long getSessionVersion() {
        return sessionVersion;
    }

    public void setSessionVersion(long sessionVersion) {
        this.sessionVersion = sessionVersion;
    }

    /** 绑定的群友；绑定后该账号可以标记自己所在车次的攻坚状态。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Player player;

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCanSchedule() {
        return canSchedule;
    }

    public void setCanSchedule(boolean canSchedule) {
        this.canSchedule = canSchedule;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
