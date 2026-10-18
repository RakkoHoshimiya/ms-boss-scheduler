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

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
