package com.gmsr.scheduler.security;

import com.gmsr.scheduler.service.BusinessException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
 *   <li><b>7 天无操作即须重新登录</b>：每次刷新都会重置过期时间（滑动窗口）。</li>
 * </ul>
 *
 * 用随机串而不是 JWT：没有可被解读或伪造的载荷，且**服务端可随时吊销**。
 *
 * Redis 结构：
 * <pre>
 *   gmsr:refresh:{token} -> "userId|sessionVersion"   (TTL 7 天)
 *   gmsr:refresh:u:{userId} -> Set&lt;token&gt;             (TTL 7 天，用于按账号批量吊销)
 * </pre>
 *
 * 为什么 value 里要带 sessionVersion：
 * 刷新与「改密/禁用触发的撤销」是两条并发路径。只靠删 token 有窗口 ——
 * 刷新可能已经把旧 token 消费掉，管理员随后才执行 revokeAll，
 * 于是刷新请求接着签发的新会话就绕过了那次撤销。
 * 把账号的会话版本一起存进去，刷新时拿它和数据库里的当前版本比对，
 * 版本不一致就拒绝；配合「读用户行时加锁」可以彻底串行化这两条路径。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final String TOKEN_PREFIX = "gmsr:refresh:";
    private static final String USER_PREFIX = "gmsr:refresh:u:";
    /** 单账号最多保留的 refresh token 数（多设备登录），超出时踢掉最早的。 */
    private static final long MAX_PER_USER = 5;

    /**
     * 签发脚本：把「写 token」和「写账号索引」放在一次原子执行里。
     * 分开写的话，第二步失败会留下一张无法通过 revokeAll 找到的 token。
     */
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                    + "redis.call('SADD', KEYS[2], ARGV[3]) "
                    + "redis.call('EXPIRE', KEYS[2], ARGV[2]) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;

    public RefreshTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 一次性消费的结果：谁、以及当时那个会话的版本号。 */
    public record Consumed(Long userId, long sessionVersion) {
    }

    /**
     * 为用户签发一个新的 refresh token。
     *
     * @param sessionVersion 当前的会话版本（改密/禁用会让它 +1）
     * @throws BusinessException Redis 写入失败时抛出 —— **不能**返回一个没存进去的令牌，
     *                           否则登录接口会报成功、用户下次刷新却突然掉线。
     */
    public String issue(Long userId, long sessionVersion) {
        String token = random();
        try {
            redis.execute(
                    ISSUE_SCRIPT,
                    java.util.List.of(TOKEN_PREFIX + token, USER_PREFIX + userId),
                    userId + "|" + sessionVersion,
                    String.valueOf(TTL.toSeconds()),
                    token);
        } catch (Exception ex) {
            log.error("签发 refresh token 失败（Redis 不可用）：{}", ex.getMessage());
            throw new BusinessException("会话服务暂时不可用，请稍后重试");
        }
        trim(userId, token);
        return token;
    }

    /**
     * 原子地消费一个 refresh token（GETDEL：谁先执行谁拿到，另一个拿不到）。
     *
     * @return 消费结果；无效 / Redis 异常返回 null（fail closed）
     */
    public Consumed consume(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(TOKEN_PREFIX + refreshToken);
        } catch (Exception ex) {
            log.warn("消费 refresh token 失败（Redis 异常，按无效处理）：{}", ex.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        // 兼容旧格式（只有 userId）：没有版本信息时按 0 处理
        String[] parts = raw.split("\\|", 2);
        Long userId;
        long version = 0L;
        try {
            userId = Long.valueOf(parts[0]);
            if (parts.length > 1) {
                version = Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        try {
            redis.opsForSet().remove(USER_PREFIX + userId, refreshToken);
        } catch (Exception ex) {
            log.warn("清理 refresh token 索引失败：{}", ex.getMessage());
        }
        return new Consumed(userId, version);
    }

    /** 登出：让某个 refresh token 立即失效。 */
    public void revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            String raw = redis.opsForValue().get(TOKEN_PREFIX + refreshToken);
            redis.delete(TOKEN_PREFIX + refreshToken);
            if (raw != null) {
                String userId = raw.split("\\|", 2)[0];
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

    /** 超出单账号上限时，删掉一个别的（仅兜底，失败不影响主流程）。 */
    private void trim(Long userId, String keep) {
        try {
            String userKey = USER_PREFIX + userId;
            Long size = redis.opsForSet().size(userKey);
            if (size == null || size <= MAX_PER_USER) {
                return;
            }
            var members = redis.opsForSet().members(userKey);
            if (members == null) {
                return;
            }
            for (String old : members) {
                if (!old.equals(keep)) {
                    redis.delete(TOKEN_PREFIX + old);
                    redis.opsForSet().remove(userKey, old);
                    return;
                }
            }
        } catch (Exception ex) {
            log.warn("清理多余 refresh token 失败：{}", ex.getMessage());
        }
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
