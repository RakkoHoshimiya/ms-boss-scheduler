package com.gmsr.scheduler.voice;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 签发 LiveKit 进房 token。
 * 只依赖已有的 jjwt，不额外引 SDK；LiveKit 的 token 就是 HS256 的 JWT。
 */
@Service
public class VoiceTokenService {

    private final String apiKey;
    private final String apiSecret;
    private final String wsUrl;
    private final Duration ttl;
    private final boolean enabled;

    public VoiceTokenService(
            @Value("${gmsr.voice.enabled:false}") boolean enabled,
            @Value("${gmsr.voice.api-key:}") String apiKey,
            @Value("${gmsr.voice.api-secret:}") String apiSecret,
            @Value("${gmsr.voice.ws-url:}") String wsUrl,
            @Value("${gmsr.voice.token-hours:6}") long tokenHours) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.wsUrl = wsUrl == null ? "" : wsUrl.trim();
        this.ttl = Duration.ofHours(tokenHours);
    }

    /** 语音功能是否已配置好（没配好时前端显示"未开放"）。 */
    public boolean ready() {
        return enabled && !apiKey.isEmpty() && !apiSecret.isEmpty() && !wsUrl.isEmpty();
    }

    public String wsUrl() {
        return wsUrl;
    }

    /**
     * 给某个用户签发一个只能进指定房间的 token。
     * 房间名直接用 roomId，方便服务端统计。
     */
    public String issue(String roomId, String identity, String displayName, boolean canPublish) {
        if (!ready()) {
            throw new IllegalStateException("语音功能尚未配置");
        }
        SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        Map<String, Object> grant = Map.of(
                "roomJoin", true,
                "room", roomId,
                "canPublish", canPublish,
                "canSubscribe", true,
                "canPublishData", true);
        return Jwts.builder()
                .issuer(apiKey)
                .subject(identity)
                .claim("name", displayName)
                .claim("video", grant)
                .claim("metadata", "")
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now.minusSeconds(10)))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
