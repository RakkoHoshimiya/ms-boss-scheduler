package com.gmsr.scheduler.security;

import cn.dev33.satoken.stp.StpUtil;
import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 基于 SA-Token 的鉴权过滤器（替代原来的手写 JWT）。
 *
 * 与旧实现的区别：**以服务端会话为准**。
 * 旧实现完全信任 token 里的 role / canSchedule / playerId 声明，
 * 于是「管理员撤销排班权限、禁用账号、甚至删号」之后，对方手里的 token
 * 在 7 天有效期内仍然畅通无阻。现在每次请求都用 SA-Token 校验会话有效性，
 * 再按**数据库里的当前状态**授予权限，管理员改动立即生效。
 *
 * 另外：**「首次登录必须改密」也从服务端强制**。
 * 只靠前端弹窗拦截是假的 —— 知道初始密码的人可以绕开页面直接调接口。
 */
@Component
public class SaTokenAuthFilter extends OncePerRequestFilter {

    /**
     * 待改密账号还允许访问的接口（否则连改密码本身都做不了）。
     * 格式：`方法 路径`。
     */
    private static final Set<String> ALLOWED_WHEN_PASSWORD_EXPIRED = Set.of(
            "GET /api/auth/me",
            "POST /api/auth/password",
            "POST /api/auth/logout",
            "GET /api/client-info",
            "GET /api/health",
            "GET /api/status-reset");

    private final AppUserRepository userRepository;

    public SaTokenAuthFilter(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate();
        }
        if (rejectIfPasswordExpired(request, response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 待改密账号只放行白名单接口。
     *
     * @return true 表示已经拦下（调用方不要再往下走）
     */
    private boolean rejectIfPasswordExpired(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // 预检请求不拦（同源部署其实用不到，但别让它把浏览器卡死）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof Map<?, ?> details)) {
            return false;
        }
        if (!Boolean.TRUE.equals(details.get("mustChangePassword"))) {
            return false;
        }
        String key = request.getMethod().toUpperCase() + " " + request.getRequestURI();
        if (isAllowedWhenPasswordExpired(key)) {
            return false;
        }
        // 用 403 + 明确的错误码，前端据此弹出改密框（而不是当成掉线）
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"MUST_CHANGE_PASSWORD\",\"message\":\"首次登录必须先设置新密码\"}");
        return true;
    }

    /**
     * 待改密账号能否访问这个接口。抽成静态方法便于单测
     * （过滤器本身要起 Spring 上下文，测起来重）。
     *
     * @param key `方法 路径`，例如 {@code POST /api/auth/password}
     */
    static boolean isAllowedWhenPasswordExpired(String key) {
        return ALLOWED_WHEN_PASSWORD_EXPIRED.contains(key);
    }

    /** 从 SA-Token 会话里取登录 id，再查库组装权限；任何一步不成立就当作未登录。 */
    private void authenticate() {
        Object loginId;
        try {
            if (!StpUtil.isLogin()) {
                return;
            }
            loginId = StpUtil.getLoginIdDefaultNull();
        } catch (Exception ex) {
            // 会话无效 / Redis 不可用：按未登录处理，不阻塞请求（公开接口仍可访问）
            return;
        }
        if (loginId == null) {
            return;
        }
        Long userId;
        try {
            userId = Long.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException ex) {
            return;
        }
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            // 账号已被删除或禁用 → 立即失效（旧 JWT 实现做不到这一点）
            StpUtil.logout(userId);
            return;
        }

        String role = user.getRole().name();
        boolean canSchedule = "ADMIN".equals(role) || user.isCanSchedule();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        if (canSchedule) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SCHEDULE"));
        }
        Map<String, Object> details = Map.of(
                "playerId", user.getPlayer() == null ? 0L : user.getPlayer().getId(),
                "mustChangePassword", user.isMustChangePassword());
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, authorities);
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
