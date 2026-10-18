package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.PlayerDtos.CharacterRequest;
import com.gmsr.scheduler.dto.PlayerDtos.CharacterView;
import com.gmsr.scheduler.dto.PlayerDtos.PlayerRequest;
import com.gmsr.scheduler.dto.PlayerDtos.PlayerView;
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

/** 群友与角色接口。 */
@RestController
@RequestMapping("/api")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @GetMapping("/players")
    public List<PlayerView> players() {
        return playerService.list();
    }

    @GetMapping("/players/{id}")
    public PlayerView player(@PathVariable Long id) {
        return playerService.get(id);
    }

    @PostMapping("/players")
    public PlayerView createPlayer(@Valid @RequestBody PlayerRequest request) {
        return playerService.create(request);
    }

    @PutMapping("/players/{id}")
    public PlayerView updatePlayer(@PathVariable Long id, @Valid @RequestBody PlayerRequest request) {
        return playerService.update(id, request);
    }

    @DeleteMapping("/players/{id}")
    public ResponseEntity<Void> deletePlayer(@PathVariable Long id) {
        playerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/characters")
    public List<CharacterView> characters() {
        return playerService.listCharacters();
    }

    @PostMapping("/characters")
    public CharacterView createCharacter(@Valid @RequestBody CharacterRequest request) {
        return playerService.createCharacter(request);
    }

    @PutMapping("/characters/{id}")
    public CharacterView updateCharacter(@PathVariable Long id, @Valid @RequestBody CharacterRequest request) {
        return playerService.updateCharacter(id, request);
    }

    @DeleteMapping("/characters/{id}")
    public ResponseEntity<Void> deleteCharacter(@PathVariable Long id) {
        playerService.deleteCharacter(id);
        return ResponseEntity.noContent().build();
    }
}
