package com.gmsr.scheduler.voice;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 查询各语音频道当前有谁在线。
 * 走 LiveKit 的 RoomService（Twirp/JSON），需要带一个「可列房间」的管理 token。
 * 查不到（服务没开 / 超时）时返回空，前端只会显示 0 人，不影响进房。
 */
@Service
public class VoicePresenceService {

    private static final Logger log = LoggerFactory.getLogger(VoicePresenceService.class);
    private static final String LIST_ROOMS = "/twirp/livekit.RoomService/ListRooms";
    /** 结果缓存时长：避免多人同时刷新时把 LiveKit 打爆 */
    private static final long CACHE_MILLIS = 3000;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final VoiceTokenService tokenService;
    private final String apiKey;
    private final String apiSecret;
    private final String httpBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile Map<String, List<String>> cache = Map.of();
    private volatile long cacheAt = 0;

    public VoicePresenceService(
            VoiceTokenService tokenService,
            @Value("${gmsr.voice.api-key:}") String apiKey,
            @Value("${gmsr.voice.api-secret:}") String apiSecret,
            @Value("${gmsr.voice.api-http-base:http://127.0.0.1:7880}") String httpBase) {
        this.tokenService = tokenService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.httpBase = httpBase == null ? "" : httpBase.trim();
    }

    /** 频道 id -> 在线成员显示名列表。 */
    public Map<String, List<String>> presence() {
        if (!tokenService.ready()) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        if (now - cacheAt < CACHE_MILLIS) {
            return cache;
        }
        try {
            Map<String, List<String>> fresh = fetch();
            cache = fresh;
            cacheAt = now;
            return fresh;
        } catch (Exception ex) {
            log.debug("查询语音在线状态失败：{}", ex.getMessage());
            cacheAt = now;
            return cache;
        }
    }

    /** 一个频道的在线情况。 */
    public record Presence(int count, List<String> names) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> fetch() throws Exception {
        String listRooms = post("/twirp/livekit.RoomService/ListRooms", "{\"names\":[]}", "");
        log.debug("[语音] ListRooms 返回：{}", listRooms == null ? "null(请求失败)" : listRooms.substring(0, Math.min(300, listRooms.length())));
        if (listRooms == null) {
            return cache;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Object parsed = MAPPER.readValue(listRooms, Object.class);
        if (parsed instanceof Map<?, ?> root && root.get("rooms") instanceof List<?> rooms) {
            for (Object item : rooms) {
                if (item instanceof Map<?, ?> room && room.get("name") != null) {
                    Object num = room.get("num_participants");
                    int count = num instanceof Number n ? n.intValue() : 0;
                    counts.put(String.valueOf(room.get("name")), count);
                }
            }
        }
        log.debug("[语音] 解析到的房间人数：{}", counts);
        // 只有真的有人的房间才再去查名单，减少调用
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            List<String> names = new ArrayList<>();
            if (entry.getValue() > 0) {
                String listPeople = post("/twirp/livekit.RoomService/ListParticipants",
                        "{\"room\":\"" + entry.getKey() + "\"}", entry.getKey());
                if (listPeople != null) {
                    Object peopleParsed = MAPPER.readValue(listPeople, Object.class);
                    if (peopleParsed instanceof Map<?, ?> pr && pr.get("participants") instanceof List<?> people) {
                        for (Object person : people) {
                            if (person instanceof Map<?, ?> p) {
                                Object display = p.get("name");
                                Object identity = p.get("identity");
                                String label = display != null && !String.valueOf(display).isBlank()
                                        ? String.valueOf(display)
                                        : String.valueOf(identity);
                                names.add(label);
                            }
                        }
                    }
                }
            }
            // 名单可能查不到（权限/异常），但人数必须以 ListRooms 的 num_participants 为准，
            // 否则界面会显示「暂无人在线」而实际有人。
            if (names.size() < entry.getValue()) {
                for (int i = names.size(); i < entry.getValue(); i++) {
                    names.add("语音中");
                }
            }
            result.put(entry.getKey(), names);
        }
        return result;
    }

    /** 发一个 Twirp 请求，失败返回 null。 */
    private String post(String path, String body, String roomName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(httpBase + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + adminToken(roomName))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.debug("[语音] 调用 LiveKit {} 返回 {}：{}", path, response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }
            return response.body();
        } catch (Exception ex) {
            log.debug("[语音] 调用 LiveKit {} 异常：{}", path, ex.toString());
            return null;
        }
    }

    /**
     * 生成一个「列房间 / 看参与者」的管理 token（只在后端用）。
     * 注意：ListParticipants 除了 roomList 还要求 video.room 里有具体房间的 admin 授权。
     */
    private String adminToken(String roomId) {
        SecretKey key = Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        Map<String, Object> grant = Map.of(
                "roomList", true,
                "roomAdmin", true,
                "room", roomId,
                "roomJoin", true);
        return Jwts.builder()
                .issuer(apiKey)
                .subject("gmsr-backend")
                .claim("video", grant)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now.minusSeconds(10)))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
