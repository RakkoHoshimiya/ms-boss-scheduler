package com.gmsr.scheduler.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmsr.scheduler.dto.ApiDtos.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 安全配置（SA-Token 服务端会话鉴权，无状态请求）。
 *
 * <p><b>授权口径（唯一一份，别再散落在各处）</b>
 * <ul>
 *   <li><b>匿名</b>：只能看「车次一览表」= 车次详情 + BOSS 目录 + 分组 + 重置时间 + 健康检查。
 *       这是产品明确要求的「不登录也能查排班」。
 *       群友列表、角色列表、账号信息、语音、按人查行程一律要登录。</li>
 *   <li><b>登录用户</b>：可以维护自己的角色（/api/me/**）、标记自己所在车次的状态、
 *       使用语音、看群友名单（QQ 由服务层按权限脱敏）。</li>
 *   <li><b>排班权限（ROLE_SCHEDULE：ADMIN 天然有，其他账号看 can_schedule）</b>：
 *       BOSS / 分组 / 车次 / 群友 / 角色的增删改。</li>
 *   <li><b>管理员（ROLE_ADMIN）</b>：账号管理、系统设置、维护接口。</li>
 * </ul>
 *
 * <p><b>为什么写成一串「显式 deny 在前、宽泛放行在后」</b><br>
 * Spring Security 按声明顺序**首个匹配生效**。以前用的是「GET /api/** permitAll 提前放行」，
 * 于是它后面的规则全部变成永远不会命中的死规则（历史上因此漏掉过 3 条鉴权）。
 * 这里反过来：先把所有需要登录/需要权限的路径钉死，最后才放行剩余的 GET。
 * 仍然保留一个兜底 {@code denyAll}，新增接口若忘了归类会直接 403（fail closed），
 * 而不是静默变成公开接口。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * EDITOR 角色已经废弃（{@code AuthService#parseRole} 只会产出 ADMIN / VIEWER）。
     * 为确保历史数据里残留的 EDITOR 账号不会因为「角色名刚好匹配」而拿到额外权限，
     * 这里刻意**不再**出现任何 EDITOR 判断。
     */

    private final SaTokenAuthFilter authFilter;
    private final ObjectMapper objectMapper;

    @Value("${gmsr.cors.allowed-origins:}")
    private String allowedOrigins;

    public SecurityConfig(SaTokenAuthFilter authFilter, ObjectMapper objectMapper) {
        this.authFilter = authFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Spring Boot 的错误转发端点：必须放行，否则过滤器层抛出的异常
                        // 会被 denyAll 二次拦截，客户端只会看到一个 403 而不是真实错误。
                        .requestMatchers("/error").permitAll()

                        // ---------- 完全公开：登录 / 刷新 / 登出 / 健康检查 / 设备识别 ----------
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/client-info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/status-reset").permitAll()

                        // ---------- 匿名可见：只有「车次一览表」所需的数据 ----------
                        // 车次详情（含成员名单、战力、状态）—— 产品要求不登录也能查排班
                        .requestMatchers(HttpMethod.GET, "/api/runs", "/api/runs/*").permitAll()
                        // BOSS 目录与分组（排班表的列头 / 融合车合并需要）
                        .requestMatchers(HttpMethod.GET, "/api/bosses", "/api/bosses/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/bosses/*/runs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/boss-groups").permitAll()

                        // ---------- 以下一律要求登录（必须在任何 GET 放行之前） ----------
                        // 我的角色：服务层再校验只能改自己的
                        .requestMatchers("/api/notes", "/api/notes/**").authenticated()
                        .requestMatchers("/api/me/**").authenticated()
                        // 语音频道
                        .requestMatchers("/api/voice/**").authenticated()
                        // 自助账号操作
                        .requestMatchers("/api/auth/me", "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/auth/display-name").authenticated()
                        // 群友与角色名单：不再匿名开放。
                        // 以前 GET /api/players 连 QQ 一起返回，等于把全群登录用户名公开。
                        .requestMatchers(HttpMethod.GET, "/api/players", "/api/players/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/characters").authenticated()
                        // 按人查行程：会暴露某人的全部行程，只对本人和排班人员开放
                        .requestMatchers(HttpMethod.GET, "/api/players/*/runs").authenticated()
                        // 车次上「我的席位」
                        .requestMatchers(HttpMethod.GET, "/api/runs/*/my-members").authenticated()
                        // 标记自己所在车次的状态
                        .requestMatchers(HttpMethod.PUT, "/api/runs/*/status").authenticated()

                        // ---------- 管理员 ----------
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ---------- 排班权限：BOSS / 分组 / 车次 / 群友 / 角色的写操作 ----------
                        // 注意分组接口的真实路径是 /api/boss-groups（曾误写成 /api/groups）
                        .requestMatchers(HttpMethod.POST, "/api/bosses/**", "/api/boss-groups/**", "/api/runs/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.PUT, "/api/bosses/**", "/api/boss-groups/**", "/api/runs/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.PATCH, "/api/bosses/**", "/api/boss-groups/**", "/api/runs/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.DELETE, "/api/bosses/**", "/api/boss-groups/**", "/api/runs/**")
                        .hasRole("SCHEDULE")
                        // 群友 / 角色的维护同样属于「排班」范畴（管理员天然有排班权限）
                        .requestMatchers(HttpMethod.POST, "/api/players/**", "/api/characters/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.PUT, "/api/players/**", "/api/characters/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.PATCH, "/api/players/**", "/api/characters/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.DELETE, "/api/players/**", "/api/characters/**")
                        .hasRole("SCHEDULE")

                        // ---------- 兜底：未归类的接口一律拒绝（fail closed） ----------
                        // 新增接口必须显式归类。以前这里是 permitAll，
                        // 一旦引入 actuator / springdoc 之类就会静默变成公开接口。
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> writeError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) -> writeError(
                                response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有操作权限")))
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiError(code, message));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
