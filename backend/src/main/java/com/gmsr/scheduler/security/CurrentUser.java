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
     * 是否有权限维护群友与角色目录（改资料 / 登记角色）。
     *
     * <p>口径与 SecurityConfig 完全一致：**管理员，或有 boss排班 权限的账号**。
     * 以前这里认的是 ADMIN/EDITOR，而 EDITOR 已从角色模型移除（parseRole 只产出 ADMIN/VIEWER），
     * 结果「能改却因为不是 EDITOR 而看不到 QQ」——前端显示一片空白。
     */
    public static boolean isStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SCHEDULE".equals(a.getAuthority()));
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
