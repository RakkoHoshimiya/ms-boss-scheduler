package com.gmsr.scheduler.security;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 双 token 方案里的 refresh token 管理。
 *
 * 设计：
 * <ul>
 *   <li><b>access token</b>：由 SA-Token 签发，寿命 2 小时（{@code sa-token.timeout}），
 *       接口请求只认它。</li>
 *   <li><b>refresh token</b>：本类管理的随机串，存 Redis，寿命 7 天，
 *       专门用于在 access token 过期时换取新的 access token。</li>
 *   <li><b>7 天无操作即须重新登录</b>：每次刷新都会重置 refresh token 的过期时间
 *       （滑动窗口）。7 天内用过一次就不会掉线；连续 7 天完全没动作则自然过期，
 *       必须重新输入账号密码。</li>
 * </ul>
 *
 * 用随机串而不是 JWT：没有可被解读或伪造的载荷，且**服务端可随时吊销**
 * （登出、改密码、禁用账号都能立刻踢掉）。
 *
 * Redis 结构：
 * <pre>
 *   gmsr:refresh:{token} -> userId      (TTL 7 天)
 *   gmsr:refresh:u:{userId} -> Set&lt;token&gt;  (TTL 7 天，用于按账号批量吊销与限流)
 * </pre>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final String TOKEN_PREFIX = "gmsr:refresh:";
    private static final String USER_PREFIX = "gmsr:refresh:u:";
    /** 单账号最多保留的 refresh token 数（多设备登录），超出时踢掉最早的。 */
    private static final long MAX_PER_USER = 5;

    private final StringRedisTemplate redis;

    public RefreshTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 为用户签发一个新的 refresh token。 */
    public String issue(Long userId) {
        String token = random();
        try {
            redis.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(userId), TTL);
            String userKey = USER_PREFIX + userId;
            redis.opsForSet().add(userKey, token);
            redis.expire(userKey, TTL);
            // 超过上限时踢掉最早的（Set 无序，取任意多余的即可，仅作兜底）
            Long size = redis.opsForSet().size(userKey);
            if (size != null && size > MAX_PER_USER) {
                var members = redis.opsForSet().members(userKey);
                if (members != null) {
                    for (String old : members) {
                        if (!old.equals(token)) {
                            redis.delete(TOKEN_PREFIX + old);
                            redis.opsForSet().remove(userKey, old);
                            break;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("写入 refresh token 失败（Redis 不可用？）：{}", ex.getMessage());
        }
        return token;
    }

    /**
     * 校验并**轮换**：成功返回 userId，并通过 {@code newTokenOut[0]} 回传新的 refresh token。
     * 旧 token 立即作废（一次性使用，防重放）。
     */
    public Long consume(String refreshToken, String[] newTokenOut) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String tokenKey = TOKEN_PREFIX + refreshToken;
        /**
         * 原子地「取出并删除」。
         *
         * 以前是 get → delete → issue 三步：两个并发请求可能都读到同一个旧 token，
         * 于是各自换到新令牌（一次性使用形同虚设），删失败还会被忽略。
         * 现在用 Redis 的 GETDEL（6.2+，线上是 8.x）：**谁先执行谁拿到用户名，另一个直接拿不到**。
         * 读失败也一律当作无效（fail closed），不再"看起来成功"。
         */
        String userId;
        try {
            userId = redis.opsForValue().getAndDelete(tokenKey);
        } catch (Exception ex) {
            log.warn("消费 refresh token 失败（Redis 异常，按无效处理）：{}", ex.getMessage());
            return null;
        }
        if (userId == null) {
            return null;
        }
        Long parsed;
        try {
            parsed = Long.valueOf(userId);
        } catch (NumberFormatException ex) {
            return null;
        }
        // 顺手把它从账号的 token 集合里摘掉（失败只记日志：主键已删除，旧 token 已不可用）
        try {
            redis.opsForSet().remove(USER_PREFIX + parsed, refreshToken);
        } catch (Exception ex) {
            log.warn("清理 refresh token 索引失败：{}", ex.getMessage());
        }
        newTokenOut[0] = issue(parsed);
        return parsed;
    }

    /** 登出：让某个 refresh token 立即失效。 */
    public void revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            String userId = redis.opsForValue().get(TOKEN_PREFIX + refreshToken);
            redis.delete(TOKEN_PREFIX + refreshToken);
            if (userId != null) {
                redis.opsForSet().remove(USER_PREFIX + userId, refreshToken);
            }
        } catch (Exception ex) {
            log.warn("吊销 refresh token 失败：{}", ex.getMessage());
        }
    }

    /** 踢掉某账号的全部 refresh token（改密码 / 禁用账号 / 删除账号时调用）。 */
    public void revokeAll(Long userId) {
        try {
            String userKey = USER_PREFIX + userId;
            var members = redis.opsForSet().members(userKey);
            if (members != null) {
                for (String token : members) {
                    redis.delete(TOKEN_PREFIX + token);
                }
            }
            redis.delete(userKey);
        } catch (Exception ex) {
            log.warn("清理账号 refresh token 失败：{}", ex.getMessage());
        }
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
