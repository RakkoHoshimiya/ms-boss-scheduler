package com.gmsr.scheduler.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redis / 存储故障必须映射成 503（可重试），而不是 500（内部错误）。
 *
 * 背景：登录与刷新会写 Redis（会话、refresh token）。Redis 抖动时
 * 如果被当成 500，客户端会以为服务端有 bug；503 才能让前端提示「稍后再试」。
 *
 * 这条以前只手工用 CLIENT PAUSE 验证过，现在进 CI。
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new BoomController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @RestController
    static class BoomController {
        @GetMapping("/boom/redis")
        String redisTimeout() {
            throw new QueryTimeoutException("Redis command timed out");
        }
    }

    @Test
    @DisplayName("Redis 超时 → 503 STORAGE_UNAVAILABLE")
    void redisTimeoutMapsTo503() throws Exception {
        mockMvc.perform(get("/boom/redis"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STORAGE_UNAVAILABLE")));
    }
}
