package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.PlayerDtos.MyCharacterRequest;
import com.gmsr.scheduler.dto.PlayerDtos.CharacterView;
import com.gmsr.scheduler.security.CurrentUser;
import com.gmsr.scheduler.service.BusinessException;
import com.gmsr.scheduler.service.PlayerService;
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
            throw new BusinessException("请先登录");
        }
        Long playerId = CurrentUser.playerId();
        if (playerId == null) {
            throw new BusinessException("你的账号还没有绑定群友，请先到「我的车次」页面选择自己的昵称");
        }
        return playerId;
    }
}
