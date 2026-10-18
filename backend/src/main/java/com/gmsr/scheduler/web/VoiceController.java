package com.gmsr.scheduler.web;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.security.CurrentUser;
import com.gmsr.scheduler.service.BusinessException;
import com.gmsr.scheduler.service.NotFoundException;
import com.gmsr.scheduler.voice.VoiceRoom;
import com.gmsr.scheduler.voice.VoicePresenceService;
import com.gmsr.scheduler.voice.VoiceTokenService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BOSS 语音频道：频道列表 + 进房 token。 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceTokenService tokenService;
    private final VoicePresenceService presenceService;
    private final AppUserRepository userRepository;

    public VoiceController(
            VoiceTokenService tokenService,
            VoicePresenceService presenceService,
            AppUserRepository userRepository) {
        this.tokenService = tokenService;
        this.presenceService = presenceService;
        this.userRepository = userRepository;
    }

    /** 频道列表（含是否已开通）。 */
    @GetMapping("/rooms")
    public Map<String, Object> rooms() {
        Map<String, List<String>> presence = presenceService.presence();
        List<Map<String, Object>> list = new ArrayList<>();
        for (VoiceRoom.Room room : VoiceRoom.ROOMS) {
            List<String> online = presence.getOrDefault(room.id(), List.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", room.id());
            item.put("name", room.name());
            item.put("description", room.description());
            item.put("icon", room.icon());
            // 直接把「谁在里面」返回给列表页，不用进去才知道
            item.put("onlineCount", online.size());
            item.put("online", online);
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", tokenService.ready());
        result.put("wsUrl", tokenService.ready() ? tokenService.wsUrl() : null);
        result.put("rooms", list);
        return result;
    }

    /** 换取进房 token。 */
    @PostMapping("/rooms/{roomId}/token")
    public Map<String, Object> token(@PathVariable String roomId) {
        Long userId = CurrentUser.id();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        if (!VoiceRoom.exists(roomId)) {
            throw new NotFoundException("语音频道不存在：" + roomId);
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在：" + userId));
        boolean canPublish = !user.isMustChangePassword();
        String identity = "u" + user.getId();
        String token = tokenService.issue(roomId, identity, user.getDisplayName(), canPublish);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("wsUrl", tokenService.wsUrl());
        result.put("roomId", roomId);
        result.put("identity", identity);
        result.put("displayName", user.getDisplayName());
        result.put("canPublish", canPublish);
        return result;
    }
}
