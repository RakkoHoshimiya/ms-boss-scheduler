package com.gmsr.scheduler.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录失败次数限制，防暴力破解：
 * - 单账号：连续失败达到阈值即锁定（默认 8 次 / 15 分钟）
 * - 单来源 IP：阈值放宽很多（默认 30 次 / 15 分钟），避免同一网络下有人输错就把整网用户一起锁住
 * 单实例部署内存计数即可；多实例场景应换成 Redis。
 */
@Component
public class LoginAttemptService {

    private static final long WINDOW_SECONDS = 900;
    private static final int MAX_TRACKED_KEYS = 20000;

    private final int maxAttemptsPerAccount;
    private final int maxAttemptsPerIp;
    private final Duration lockDuration;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(
            @Value("${gmsr.security.login.max-attempts:8}") int maxAttemptsPerAccount,
            @Value("${gmsr.security.login.max-attempts-per-ip:30}") int maxAttemptsPerIp,
            @Value("${gmsr.security.login.lock-minutes:15}") long lockMinutes) {
        this.maxAttemptsPerAccount = Math.max(3, maxAttemptsPerAccount);
        this.maxAttemptsPerIp = Math.max(this.maxAttemptsPerAccount, maxAttemptsPerIp);
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    /** 被锁定则返回剩余分钟数，否则返回 0。 */
    public long lockedMinutes(String username, String ip) {
        Instant now = Instant.now();
        cleanup(now);
        return Math.max(remaining(accountKey(username), now), remaining(ipKey(ip), now));
    }

    public void recordFailure(String username, String ip) {
        Instant now = Instant.now();
        count(accountKey(username), maxAttemptsPerAccount, now);
        count(ipKey(ip), maxAttemptsPerIp, now);
    }

    public void recordSuccess(String username, String ip) {
        attempts.remove(accountKey(username));
        // IP 计数不清零：避免攻击者用一次正确登录把整个来源的计数重置掉
    }

    private long remaining(String key, Instant now) {
        Attempt attempt = attempts.get(key);
        if (attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(now)) {
            return Math.max(1, Duration.between(now, attempt.lockedUntil).toMinutes() + 1);
        }
        return 0;
    }

    private void count(String key, int limit, Instant now) {
        attempts.compute(key, (k, existing) -> {
            Attempt attempt = existing;
            if (attempt == null || Duration.between(attempt.windowStart, now).getSeconds() > WINDOW_SECONDS) {
                attempt = new Attempt(now);
            }
            attempt.failures++;
            if (attempt.failures >= limit) {
                attempt.lockedUntil = now.plus(lockDuration);
                attempt.failures = 0;
                attempt.windowStart = now;
            }
            return attempt;
        });
    }

    private static String accountKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase());
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    private void cleanup(Instant now) {
        if (attempts.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        attempts.entrySet().removeIf(entry -> {
            Attempt attempt = entry.getValue();
            Instant reference = attempt.lockedUntil != null ? attempt.lockedUntil : attempt.windowStart;
            return Duration.between(reference, now).getSeconds() > WINDOW_SECONDS;
        });
    }

    private static final class Attempt {
        private Instant windowStart;
        private int failures;
        private Instant lockedUntil;

        private Attempt(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
