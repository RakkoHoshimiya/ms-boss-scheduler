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
 */
@Component
public class SaTokenAuthFilter extends OncePerRequestFilter {

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
        chain.doFilter(request, response);
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
                "playerId", user.getPlayer() == null ? 0L : user.getPlayer().getId());
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, authorities);
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
