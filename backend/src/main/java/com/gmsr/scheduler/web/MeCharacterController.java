package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.PlayerDtos.MyCharacterRequest;
import com.gmsr.scheduler.dto.PlayerDtos.CharacterView;
import com.gmsr.scheduler.security.CurrentUser;
import com.gmsr.scheduler.service.ForbiddenException;
import com.gmsr.scheduler.service.PlayerService;
import com.gmsr.scheduler.service.UnauthorizedException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的角色」：登录用户维护自己的职业与战力。
 * 服务端强制校验角色归属，只能动自己名下的角色。
 */
@RestController
@RequestMapping("/api/me/characters")
public class MeCharacterController {

    private final PlayerService playerService;

    public MeCharacterController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @GetMapping
    public List<CharacterView> myCharacters() {
        return playerService.charactersOfPlayer(requirePlayerId());
    }

    @PostMapping
    public CharacterView create(@Valid @RequestBody MyCharacterRequest request) {
        return playerService.createOwnCharacter(requirePlayerId(), request);
    }

    @PutMapping("/{id}")
    public CharacterView update(@PathVariable Long id, @Valid @RequestBody MyCharacterRequest request) {
        return playerService.updateOwnCharacter(requirePlayerId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        playerService.deleteOwnCharacter(requirePlayerId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 未绑定群友的账号不能维护角色。 */
    private static Long requirePlayerId() {
        Long userId = CurrentUser.id();
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        Long playerId = CurrentUser.playerId();
        if (playerId == null) {
            // 已登录但没绑定群友：这是「没有权限」，不是「没登录」，用 403 避免前端反复刷新 token
            throw new ForbiddenException("你的账号还没有绑定群友，请联系管理员");
        }
        return playerId;
    }
}
