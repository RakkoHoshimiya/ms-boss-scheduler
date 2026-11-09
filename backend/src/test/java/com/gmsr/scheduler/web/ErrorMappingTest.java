package com.gmsr.scheduler.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmsr.scheduler.service.BusinessException;
import com.gmsr.scheduler.service.FeatureUnavailableException;
import com.gmsr.scheduler.service.ForbiddenException;
import com.gmsr.scheduler.service.NotFoundException;
import com.gmsr.scheduler.service.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一异常映射：HTTP 状态码必须能区分「客户端错了」和「服务端错了」。
 *
 * <p>背景（本次评审 H1/H2）：
 * 以前 4xx 的错误（请求体格式错、路径参数类型错、未登录、无权限、功能未配置）
 * 有一半会落到兜底的 {@code Exception} 处理器，被报成 500「服务器内部错误」。
 * 前端因此无法判断该重试、该刷新 token、还是该提示用户改输入。
 */
class ErrorMappingTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/unauthorized")
        String unauthorized() {
            throw new UnauthorizedException("请先登录");
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new ForbiddenException("你不在这一趟车上");
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException("这趟车已满");
        }

        @GetMapping("/notfound")
        String notFound() {
            throw new NotFoundException("车次不存在");
        }

        @GetMapping("/feature")
        String feature() {
            throw new FeatureUnavailableException("语音功能尚未配置");
        }

        @GetMapping("/runs/{id}")
        String run(@PathVariable Long id) {
            return "ok";
        }

        @PostMapping("/echo")
        String echo(@RequestBody EchoBody body) {
            return "ok";
        }

        record EchoBody(int runIndex) {
        }
    }

    @Test
    @DisplayName("未登录 → 401（前端据此触发无感刷新，而不是当成普通错误）")
    void unauthorizedMapsTo401() throws Exception {
        mockMvc.perform(get("/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("无权限 → 403（不是 401，避免前端误判掉线）")
    void forbiddenMapsTo403() throws Exception {
        mockMvc.perform(get("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("业务校验失败 → 400")
    void businessMapsTo400() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"));
    }

    @Test
    @DisplayName("资源不存在 → 404")
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/notfound"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("功能未开通 → 503（不是 500，前端可提示「暂未开放」）")
    void featureUnavailableMapsTo503() throws Exception {
        mockMvc.perform(get("/feature"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("路径参数类型不对（/runs/abc）→ 400，不是 500")
    void typeMismatchMapsTo400() throws Exception {
        mockMvc.perform(get("/runs/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("请求体字段类型错 → 400，不是 500")
    void unreadableBodyMapsTo400() throws Exception {
        mockMvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runIndex\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400，不是 500")
    void malformedJsonMapsTo400() throws Exception {
        mockMvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }
}
