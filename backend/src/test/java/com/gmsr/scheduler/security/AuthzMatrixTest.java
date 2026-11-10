package com.gmsr.scheduler.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 授权矩阵回归测试 —— 锁死「谁能访问什么」。
 *
 * <p>为什么必须有：
 * Spring Security 是「首个匹配生效」，规则顺序错一行就会让后面的规则变成永远不命中的死规则。
 * 历史事故两次：{@code GET /api/**} permitAll 写得太靠前导致 3 条规则失效、
 * 分组路径写成 {@code /api/groups}（实际是 {@code /api/boss-groups}）导致规则从未生效。
 * 两次都没有任何测试能发现 —— 这个类就是为了堵住这条路。
 *
 * <p>做法：用**真实的 {@link SecurityConfig}**（同一份授权规则）+ 桩控制器，
 * 把 SA-Token 过滤器换成按请求头装配身份的假过滤器，不依赖 Redis 与数据库。
 */
@WebMvcTest(controllers = AuthzMatrixProbeController.class)
@Import({SecurityConfig.class, AuthzMatrixTest.RoleHeaderConfig.class})
class AuthzMatrixTest {

    /** 真实过滤器要查库，这里换掉；授权规则仍来自真实的 SecurityConfig。 */
    @MockitoBean
    private SaTokenAuthFilter saTokenAuthFilter;

    @Autowired
    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void forwardMockFilter() throws Exception {
        // A mocked servlet filter otherwise swallows the request and returns a false 200.
        org.mockito.Mockito.doAnswer(invocation -> {
            new RoleHeaderFilter().doFilter(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(saTokenAuthFilter).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 按请求头装配身份：
     * {@code X-Test-Role: anon / viewer / scheduler / admin}。
     */
    static class RoleHeaderFilter extends org.springframework.web.filter.OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            String role = request.getHeader("X-Test-Role");
            if (role != null && !role.isBlank() && !"anon".equals(role)) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                if ("admin".equals(role) || "scheduler".equals(role)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_SCHEDULE"));
                }
                var auth = new UsernamePasswordAuthenticationToken("1", null, authorities);
                auth.setDetails(Map.of("playerId", 1L, "mustChangePassword", false));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class RoleHeaderConfig {
        @org.springframework.context.annotation.Bean
        RoleHeaderFilter roleHeaderFilter() {
            return new RoleHeaderFilter();
        }
    }

    private static RequestPostProcessor as(String role) {
        return request -> {
            request.addHeader("X-Test-Role", role);
            return request;
        };
    }

    /** 期望矩阵：method + path + 身份 + 期望状态码。 */
    private static Stream<Arguments> matrix() {
        return Stream.of(
            Arguments.of("PUT", "/api/notes/1/pin", "anon", 401),
            Arguments.of("PUT", "/api/notes/1/pin", "viewer", 200),
            Arguments.of("PUT", "/api/notes/1/pin", "admin", 200),
            Arguments.of("POST", "/api/notes/attachments", "anon", 401),
            Arguments.of("GET", "/api/notes/attachments/a", "anon", 401),
            Arguments.of("DELETE", "/api/notes/attachments/a", "anon", 401),
            Arguments.of("POST", "/api/notes/attachments", "viewer", 200),
            Arguments.of("GET", "/api/notes/attachments/a", "viewer", 200),
            Arguments.of("DELETE", "/api/notes/attachments/a", "viewer", 200),
            Arguments.of("GET", "/api/notes", "anon", 401),
            Arguments.of("GET", "/api/notes/1", "anon", 401),
            Arguments.of("GET", "/api/notes/1/attachments/a", "anon", 401),
            Arguments.of("POST", "/api/notes", "anon", 401),
            Arguments.of("PUT", "/api/notes/1", "anon", 401),
            Arguments.of("DELETE", "/api/notes/1", "anon", 401),
            Arguments.of("GET", "/api/notes", "viewer", 200),
            Arguments.of("GET", "/api/notes/1/attachments/a", "viewer", 200),
            Arguments.of("POST", "/api/notes", "viewer", 200),
            Arguments.of("PUT", "/api/notes/1", "viewer", 200),
            Arguments.of("DELETE", "/api/notes/1", "viewer", 200),
            Arguments.of("POST", "/api/notes/1/attachments", "viewer", 200),
            Arguments.of("DELETE", "/api/notes/1/attachments/a", "viewer", 200),
            // ---------- 匿名可看：车次一览表所需 ----------
            Arguments.of("GET", "/api/runs", "anon", 200),
            Arguments.of("GET", "/api/runs/1", "anon", 200),
            Arguments.of("GET", "/api/bosses", "anon", 200),
            Arguments.of("GET", "/api/bosses/1", "anon", 200),
            Arguments.of("GET", "/api/bosses/1/runs", "anon", 200),
            Arguments.of("GET", "/api/boss-groups", "anon", 200),
            Arguments.of("GET", "/api/status-reset", "anon", 200),
            Arguments.of("GET", "/api/health", "anon", 200),
            Arguments.of("GET", "/api/client-info", "anon", 200),

            // ---------- 匿名不可看：群友 / 角色 / 按人查行程 / 语音 / 账号 ----------
            Arguments.of("GET", "/api/players", "anon", 401),
            Arguments.of("GET", "/api/players/1", "anon", 401),
            Arguments.of("GET", "/api/players/1/runs", "anon", 401),
            Arguments.of("GET", "/api/characters", "anon", 401),
            Arguments.of("GET", "/api/voice/rooms", "anon", 401),
            Arguments.of("GET", "/api/auth/me", "anon", 401),
            Arguments.of("GET", "/api/admin/users", "anon", 401),
            Arguments.of("GET", "/api/runs/1/my-members", "anon", 401),

            // ---------- 群友（已登录、无排班权限） ----------
            Arguments.of("GET", "/api/players", "viewer", 200),
            Arguments.of("GET", "/api/characters", "viewer", 200),
            Arguments.of("GET", "/api/voice/rooms", "viewer", 200),
            Arguments.of("GET", "/api/me/characters", "viewer", 200),
            Arguments.of("POST", "/api/runs", "viewer", 403),
            Arguments.of("DELETE", "/api/runs/1", "viewer", 403),
            Arguments.of("POST", "/api/players", "viewer", 403),
            Arguments.of("PUT", "/api/players/1", "viewer", 403),
            Arguments.of("GET", "/api/admin/users", "viewer", 403),

            // ---------- 排班员（canSchedule，非管理员） ----------
            Arguments.of("POST", "/api/runs", "scheduler", 200),
            Arguments.of("PUT", "/api/runs/1", "scheduler", 200),
            Arguments.of("DELETE", "/api/runs/1", "scheduler", 200),
            Arguments.of("POST", "/api/runs/1/members", "scheduler", 200),
            Arguments.of("POST", "/api/bosses", "scheduler", 200),
            Arguments.of("POST", "/api/boss-groups", "scheduler", 200),
            Arguments.of("POST", "/api/players", "scheduler", 200),
            Arguments.of("POST", "/api/characters", "scheduler", 200),
            Arguments.of("PUT", "/api/runs/1/status", "scheduler", 200),
            Arguments.of("GET", "/api/admin/users", "scheduler", 403),
            Arguments.of("POST", "/api/admin/users", "scheduler", 403),

            // ---------- 管理员 ----------
            Arguments.of("GET", "/api/admin/users", "admin", 200),
            Arguments.of("POST", "/api/admin/users", "admin", 200),
            Arguments.of("POST", "/api/admin/runs/reset-status", "admin", 200),

            // ---------- 未知接口：一律拒绝（fail closed） ----------
            Arguments.of("GET", "/api/unknown-endpoint", "anon", 401),
            Arguments.of("GET", "/api/unknown-endpoint", "viewer", 403)
        );
    }

    @ParameterizedTest(name = "{0} {1} as {2} → {3}")
    @MethodSource("matrix")
    @DisplayName("授权矩阵：匿名 / 群友 / 排班员 / 管理员")
    void authorizationMatrix(String method, String path, String role, int expectedStatus) throws Exception {
        /*
         * 不能用 switch (HttpMethod.valueOf(method)) { case GET -> ... }：
         * Spring 6 的 org.springframework.http.HttpMethod 是普通类而非枚举，
         * Java 的 switch 只接受枚举常量，编译器会报 "cannot find symbol: GET"。
         * 因此这里按方法名字符串分发。
         */
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("unhandled method " + method);
        };
        mockMvc.perform(request.with(as(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("登录接口对匿名开放")
    void loginIsPublic() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk());
    }
}
