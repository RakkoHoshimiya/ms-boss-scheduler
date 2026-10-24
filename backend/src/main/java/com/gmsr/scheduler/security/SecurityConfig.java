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

/** 安全配置：JWT 无状态鉴权，读接口匿名可访问，写接口需要编辑权限。 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SaTokenAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Value("${gmsr.cors.allowed-origins:}")
    private String allowedOrigins;

    public SecurityConfig(SaTokenAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
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
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/health")
                        .permitAll()
                        // 登录前就要判断设备类型（决定用手机版还是电脑版界面）
                        .requestMatchers(HttpMethod.GET, "/api/client-info").permitAll()
                        // 我的角色：任何方法都要登录（服务端校验只能改自己的），必须放在 GET 放行之前
                        .requestMatchers("/api/me/**").authenticated()
                        // 账号管理 / 系统设置：只有管理员（必须放在 GET 放行之前，否则会被放行规则吃掉）
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 登录用户即可标记自己所在车次的状态（必须在 boss排班 规则之前，普通群友也要能标记）
                        .requestMatchers(HttpMethod.PUT, "/api/runs/*/status").authenticated()
                        // 下面几条 GET 也必须放在下面那行 `GET /api/**` permitAll 之前，
                        // 否则会被宽泛放行规则抢先匹配，变成永远不会生效的死规则。
                        .requestMatchers(HttpMethod.GET, "/api/runs/*/my-members").authenticated()
                        // BOSS 语音频道：登录后才能拿 token 进房
                        .requestMatchers("/api/voice/**").authenticated()
                        // 自助：看自己信息 / 改密码 / 改显示名 / 认领群友 —— 登录即可
                        .requestMatchers(HttpMethod.PUT, "/api/auth/display-name").authenticated()
                        .requestMatchers("/api/auth/me", "/api/auth/password").authenticated()
                        // 其余 GET 接口公开：车次一览表在未登录时也要能看，这是产品要求
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        // boss排班（BOSS、分组、车次、周期复制）：有排班权限的账号都能改
                        // ADMIN 天然拥有 ROLE_SCHEDULE，其他账号看账号上的 canSchedule
                        // 注意分组接口的真实路径是 /api/boss-groups（曾误写成 /api/groups，导致规则不生效）
                        .requestMatchers("/api/bosses/**", "/api/boss-groups/**", "/api/runs/**")
                        .hasRole("SCHEDULE")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> writeError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) -> writeError(
                                response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有操作权限")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
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
