package com.gmsr.scheduler.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.repository.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「首次登录必须改密」在**真实过滤器链**上的端到端行为。
 *
 * 之前的 {@code SaTokenAuthFilterTest} 只测了白名单判断函数，
 * 没有经过过滤器、安全上下文和一次真实 HTTP 请求 —— 评审指出这不能证明拦截生效。
 *
 * 这里用 MockMvc + 真的 {@link SaTokenAuthFilter}，
 * 只把 SA-Token 的静态会话查询 mock 掉（避免依赖 Redis），
 * 然后发真实 HTTP 请求，断言：
 *   - 待改密账号：白名单接口放行，其余 403 + MUST_CHANGE_PASSWORD
 *   - 正常账号：全部放行
 */
class SaTokenAuthFilterHttpTest {

    private MockMvc mockMvc;
    private AppUserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .addFilters(new SaTokenAuthFilter(userRepository))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/auth/me")
        String me() {
            return "me";
        }

        @PostMapping("/api/auth/password")
        String password() {
            return "password";
        }

        @PostMapping("/api/auth/logout")
        String logout() {
            return "logout";
        }

        @GetMapping("/api/runs")
        String runs() {
            return "runs";
        }

        @PostMapping("/api/runs")
        String createRun() {
            return "created";
        }
    }

    /** 装配一个已登录用户，并让 SA-Token 返回指定会话版本。 */
    private MockedStatic<StpUtil> loginAs(AppUser user, long sessionVersion) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        SaSession session = mock(SaSession.class);
        when(session.get(any())).thenReturn(sessionVersion);

        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(user.getId());
        stp.when(() -> StpUtil.getSessionByLoginId(any(), eq(false))).thenReturn(session);
        return stp;
    }

    private AppUser user(boolean mustChangePassword) {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setUsername("probe");
        u.setDisplayName("probe");
        u.setRole(AppUser.Role.VIEWER);
        u.setEnabled(true);
        u.setMustChangePassword(mustChangePassword);
        u.setSessionVersion(0L);
        return u;
    }

    @Test
    @DisplayName("待改密账号：白名单放行，排班接口 403 MUST_CHANGE_PASSWORD")
    void mustChangePasswordBlocksScheduling() throws Exception {
        try (MockedStatic<StpUtil> ignored = loginAs(user(true), 0L)) {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/auth/password"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/runs"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("MUST_CHANGE_PASSWORD")));

            mockMvc.perform(post("/api/runs"))
                    .andExpect(status().isForbidden());

            // 待改密账号本身是「已登录」的，只是被密码闸门挡在业务接口之外
            assertTrue(SecurityContextHolder.getContext().getAuthentication() != null,
                    "应该已经完成鉴权，只是不放到业务接口");
        }
    }

    @Test
    @DisplayName("正常账号：排班接口放行")
    void normalUserPasses() throws Exception {
        try (MockedStatic<StpUtil> ignored = loginAs(user(false), 0L)) {
            mockMvc.perform(get("/api/runs"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/runs"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("会话版本与数据库不一致时，access token 立即失效（按未登录处理）")
    void staleSessionVersionIsRejected() throws Exception {
        // 数据库里版本是 5，会话里是 0 —— 说明这是改密之前签发的旧 token
        AppUser u = user(false);
        u.setSessionVersion(5L);
        try (MockedStatic<StpUtil> stp = loginAs(u, 0L)) {
            mockMvc.perform(get("/api/runs")).andExpect(status().isOk());

            // 关键行为：过滤器主动 logout，并且**不建立鉴权上下文**
            stp.verify(() -> StpUtil.logout(1L));
            assertNull(SecurityContextHolder.getContext().getAuthentication(),
                    "版本不一致时不应建立鉴权上下文（后续请求按未登录处理）");
        }
    }
}
