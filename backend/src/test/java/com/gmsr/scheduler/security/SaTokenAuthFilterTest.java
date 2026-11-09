package com.gmsr.scheduler.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「首次登录必须改密」的服务端拦截白名单。
 *
 * 这条规则以前只在前端弹窗里做，知道初始密码的人可以直接调接口绕过去。
 * 现在服务端只放行「看自己 / 改密码 / 登出 / 公开信息」，其余一律 403。
 */
class SaTokenAuthFilterTest {

    @Test
    @DisplayName("待改密时放行：查自己、改密码、登出")
    void allowsOnlyPasswordFlow() {
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/auth/me"));
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/auth/password"));
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/auth/logout"));
    }

    @Test
    @DisplayName("待改密时放行：公开信息接口")
    void allowsPublicEndpoints() {
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/health"));
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/client-info"));
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/status-reset"));
    }

    @Test
    @DisplayName("待改密时拦截：排班写接口")
    void blocksSchedulingWrites() {
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/runs"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/runs/1/members"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("PUT /api/runs/1/members/2"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("DELETE /api/runs/1"));
    }

    @Test
    @DisplayName("待改密时拦截：群友与账号管理")
    void blocksPlayerAndAdmin() {
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/players"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/players"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/admin/users"));
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/admin/runs/reset-status"));
    }

    @Test
    @DisplayName("方法不同也算不同（GET 改密码不放过）")
    void methodMatters() {
        assertFalse(SaTokenAuthFilter.isAllowedWhenPasswordExpired("GET /api/auth/password"));
        assertTrue(SaTokenAuthFilter.isAllowedWhenPasswordExpired("POST /api/auth/password"));
    }
}
