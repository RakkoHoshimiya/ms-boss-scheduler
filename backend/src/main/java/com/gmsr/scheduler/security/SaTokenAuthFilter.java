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
            // 必须放行 refresh：待改密账号的 access token 只有 2 小时，
            // 不放行的话用户改完密码前就掉线，而掉线后又因为拿不到新 token
            // 连改密接口都进不去，形成死锁。
            "POST /api/auth/refresh",
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
        authenticate();
        if (rejectIfPasswordExpired(request, response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 不需要鉴权的路径直接跳过。
     *
     * 为什么值得单独判断：本过滤器对每个请求都要**查一次数据库**（以库里的现状为准，
     * 这样撤权限/禁用才能立即生效）。而车次一览表被设计成匿名可读，
     * 这些读请求、登录请求、健康检查都查库纯属浪费连接池
     * （默认只有 5 个连接，见 application.yml）。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String apiPath = apiPath(request);
        /*
         * 只跳过「明确不需要会话」的接口。
         *
         * 注意不能跳过 GET /api/runs 之类的匿名读接口：
         * 虽然匿名也能看，但**已登录用户的 mustChangePassword / 队列版本校验**必须照常生效。
         * （本过滤器还要给这些请求建立鉴权上下文，否则 /api/runs 上的「我参加」标记会失效。）
         */
        return PUBLIC_PATHS.contains(apiPath) || "/error".equals(request.getRequestURI());
    }

    /** 不需要会话的接口（与 SecurityConfig 里 permitAll 的那几条保持一致）。 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/client-info",
            "/api/status-reset");

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
        String key = request.getMethod().toUpperCase() + " " + apiPath(request);
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
     * 取「/api 开始」的路径。
     *
     * 不能直接用 getRequestURI()：一旦将来配置了 server.servlet.context-path，
     * 白名单里的固定字符串就全部对不上，待改密用户连改密接口都会被拒，永久锁死。
     */
    private static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int apiIndex = uri.indexOf("/api/");
        return apiIndex >= 0 ? uri.substring(apiIndex) : uri;
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
        // 同一请求内可能被多次调用（例如错误转发），已经建立上下文就不再重复查库
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }
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
        /**
         * 会话版本校验：改密 / 重置 / 禁用会把账号的 sessionVersion +1，
         * 而 access token 对应的会话里存着签发时的版本。
         * 两者不一致 → 这个 access token 是撤销之前签的，立即作废。
         *
         * 这一层是「登录签发」之外的兜底：即使某条路径在撤销后仍签出了 access token，
         * 它也会在第一次请求时被这里挡下，不需要等到 2 小时过期。
         * 没有版本信息的旧会话按 0 处理（未改过密码的账号版本也是 0，不受影响）。
         */
        long sessionVersion;
        try {
            Object stored = StpUtil.getSessionByLoginId(userId, false)
                    .get(com.gmsr.scheduler.service.AuthService.SESSION_VERSION_KEY);
            sessionVersion = stored == null ? 0L : Long.parseLong(String.valueOf(stored));
        } catch (Exception ex) {
            sessionVersion = 0L;
        }
        if (sessionVersion != user.getSessionVersion()) {
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
