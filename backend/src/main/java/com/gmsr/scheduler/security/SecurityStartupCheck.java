package com.gmsr.scheduler.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时的安全 / 可用性自检。
 *
 * 两件事：
 * 1. **登录会话存储必须可用**：SA-Token 的会话放在 Redis 里。如果 Redis 连不上，
 *    所有人都会立刻变成未登录状态。这里主动 ping 一次，把问题在启动日志里说清楚，
 *    而不是等用户反馈「一直提示请先登录」。
 * 2. **管理员密码不能是源码里的默认值**：默认口令写在仓库里，等于公开。
 */
@Component
@Order(1)
public class SecurityStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);
    private static final String DEFAULT_ADMIN_PASSWORD = "CHANGE_ME_ADMIN_PASSWORD";

    private final StringRedisTemplate redis;

    @Value("${gmsr.admin.password:}")
    private String adminPassword;

    public SecurityStartupCheck(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkRedis();
        if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            log.warn("【安全告警】管理员密码仍是源码里的默认值 {}，请设置 GMSR_ADMIN_PASSWORD 并尽快登录修改",
                    DEFAULT_ADMIN_PASSWORD);
        }
    }

    private void checkRedis() {
        try {
            String key = "gmsr:startup-check";
            redis.opsForValue().set(key, "1", java.time.Duration.ofSeconds(10));
            String value = redis.opsForValue().get(key);
            redis.delete(key);
            if (!"1".equals(value)) {
                throw new IllegalStateException("写入后读回不一致");
            }
            log.info("登录会话存储（Redis）连接正常：SA-Token 会话与 refresh token 都存放于此");
        } catch (Exception ex) {
            log.error("【严重】无法连接 Redis，登录功能将不可用（所有人都会变成未登录）：{}。"
                    + "请检查 GMSR_REDIS_HOST / GMSR_REDIS_PORT / GMSR_REDIS_PASSWORD 配置",
                    ex.getMessage());
        }
    }
}
