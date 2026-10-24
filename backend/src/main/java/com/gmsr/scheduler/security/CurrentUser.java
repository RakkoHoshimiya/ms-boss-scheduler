package com.gmsr.scheduler.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 读取当前登录用户信息的小工具。 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(auth.getPrincipal()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 当前账号绑定的群友 ID；未绑定返回 null。 */
    public static Long playerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof java.util.Map<?, ?> details)) {
            return null;
        }
        Object value = details.get("playerId");
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 是否有权限管理群友（改资料 / 登记角色）。
     * 与前端 canEdit 的口径一致：ADMIN 或 EDITOR —— 能改就应该能看到 QQ，
     * 否则会出现「能覆盖 QQ 却看不到 QQ」的怪事。
     */
    public static boolean isStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_EDITOR".equals(a.getAuthority()));
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
