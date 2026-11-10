package com.gmsr.scheduler.security;

import com.gmsr.scheduler.dto.ApiDtos.LoginResponse;
import com.gmsr.scheduler.dto.ApiDtos.UserView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只为 {@link AuthzMatrixTest} 存在的桩控制器。
 *
 * 它把被测端点全部声明出来（路径与方法必须与真实控制器一致），
 * 这样授权规则可以被独立验证，不必启动整个应用、也不依赖数据库。
 * 真实控制器一旦改了路径而这里没跟着改，矩阵测试会立刻失败 —— 这正是想要的效果。
 */
@RestController
@RequestMapping("/api")
class AuthzMatrixProbeController {

    @RequestMapping(value={"/notes/{id}/pin", "/notes/attachments", "/notes/attachments/{aid}", "/notes", "/notes/{id}", "/notes/{id}/attachments", "/notes/{id}/attachments/{aid}"})
    String notes() { return "ok"; }

    @GetMapping("/runs")
    List<String> runs() {
        return List.of();
    }

    @GetMapping("/runs/{id}")
    String run(@PathVariable Long id) {
        return "ok";
    }

    @PutMapping("/runs/{id}")
    String updateRun(@PathVariable Long id) {
        return "ok";
    }

    @DeleteMapping("/runs/{id}")
    String deleteRun(@PathVariable Long id) {
        return "ok";
    }

    @PostMapping("/runs")
    String createRun() {
        return "ok";
    }

    @PostMapping("/runs/{id}/members")
    String addMember(@PathVariable Long id) {
        return "ok";
    }

    @PutMapping("/runs/{id}/status")
    String status(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/runs/{id}/my-members")
    List<String> myMembers(@PathVariable Long id) {
        return List.of();
    }

    @GetMapping("/bosses")
    List<String> bosses() {
        return List.of();
    }

    @GetMapping("/bosses/{id}")
    String boss(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/bosses/{id}/runs")
    List<String> bossRuns(@PathVariable Long id) {
        return List.of();
    }

    @PostMapping("/bosses")
    String createBoss() {
        return "ok";
    }

    @GetMapping("/boss-groups")
    List<String> groups() {
        return List.of();
    }

    @PostMapping("/boss-groups")
    String createGroup() {
        return "ok";
    }

    @GetMapping("/players")
    List<String> players() {
        return List.of();
    }

    @GetMapping("/players/{id}")
    String player(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/players/{id}/runs")
    List<String> playerRuns(@PathVariable Long id) {
        return List.of();
    }

    @PostMapping("/players")
    String createPlayer() {
        return "ok";
    }

    @PutMapping("/players/{id}")
    String updatePlayer(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/characters")
    List<String> characters() {
        return List.of();
    }

    @PostMapping("/characters")
    String createCharacter() {
        return "ok";
    }

    @GetMapping("/me/characters")
    List<String> myCharacters() {
        return List.of();
    }

    @GetMapping("/voice/rooms")
    List<String> voiceRooms() {
        return List.of();
    }

    @GetMapping("/status-reset")
    String statusReset() {
        return "ok";
    }

    @GetMapping("/health")
    String health() {
        return "ok";
    }

    @GetMapping("/client-info")
    String clientInfo() {
        return "ok";
    }

    @PostMapping("/auth/login")
    LoginResponse login() {
        return new LoginResponse("t", "r", 7200,
                new UserView(1L, "u", "u", "VIEWER", null, null, null, false, true), false);
    }

    @GetMapping("/auth/me")
    UserView me() {
        return new UserView(1L, "u", "u", "VIEWER", null, null, null, false, true);
    }

    @GetMapping("/admin/users")
    List<String> users() {
        return List.of();
    }

    @PostMapping("/admin/users")
    String createUser() {
        return "ok";
    }

    @PostMapping("/admin/runs/reset-status")
    String resetStatus() {
        return "ok";
    }
}
