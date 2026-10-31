package com.gmsr.scheduler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmsr.scheduler.service.BusinessException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * refresh token 的并发与故障行为。
 *
 * 评审指出这些之前只有手工 curl 验证，没有进 CI：
 *   - 同一个 refresh token 并发刷新，必须只有一个成功（防重放）
 *   - Redis 不可用时签发必须**明确失败**，不能返回一个没存下的令牌
 *
 * 这里连真实 Redis（CI 里是 redis service），只断言行为不依赖具体实现。
 * 需要真实 Redis：GMSR_INTEGRATION_REDIS=1
 */
@EnabledIfEnvironmentVariable(named = "GMSR_INTEGRATION_REDIS", matches = "1",
        disabledReason = "需要真实 Redis：设 GMSR_INTEGRATION_REDIS=1 并注入连接信息")
class RefreshTokenServiceTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RefreshTokenService service;

    @BeforeAll
    static void connect() {
        String host = env("GMSR_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("GMSR_REDIS_PORT", "6379"));
        String password = System.getenv().getOrDefault("GMSR_REDIS_PASSWORD", "");

        factory = new LettuceConnectionFactory(host, port);
        if (!password.isBlank()) {
            factory.setPassword(password);
        }
        // 用单独的库，避免把测试 token 混进真实会话
        factory.setDatabase(9);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        service = new RefreshTokenService(redis);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Test
    @DisplayName("签发的 token 能用一次，第二次消费返回 null")
    void consumeOnce() {
        String token = service.issue(1001L, 7L);

        RefreshTokenService.Consumed first = service.consume(token);
        assertNotNull(first, "第一次应该能消费");
        assertEquals(1001L, first.userId());
        assertEquals(7L, first.sessionVersion(), "版本号应随 token 一起保存");

        assertNull(service.consume(token), "同一个 token 不能再消费一次");
    }

    @Test
    @DisplayName("并发消费同一个 refresh token：只有一个成功")
    void concurrentConsumeOnlyOneWins() throws Exception {
        String token = service.issue(2002L, 0L);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        try {
            List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    if (service.consume(token) != null) {
                        success.incrementAndGet();
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        System.out.println("  并发消费成功次数：" + success.get() + "/" + threads);
        assertEquals(1, success.get(), "同一个 refresh token 并发时只能被消费一次");
    }

    @Test
    @DisplayName("Redis 不可用时签发必须抛错，不能返回没存下的令牌")
    void issueFailsLoudlyWhenRedisUnavailable() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("Redis 超时"));

        RefreshTokenService failing = new RefreshTokenService(broken);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> failing.issue(3003L, 0L),
                "Redis 写入失败时必须抛错");
        assertTrue(ex.getMessage().contains("不可用") || ex.getMessage().contains("稍后"),
                "提示应说明暂时不可用，实际：" + ex.getMessage());
    }
}
