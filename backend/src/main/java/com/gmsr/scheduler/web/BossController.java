package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.BossDtos.BossRequest;
import com.gmsr.scheduler.dto.BossDtos.BossView;
import com.gmsr.scheduler.dto.BossDtos.GroupRequest;
import com.gmsr.scheduler.dto.BossDtos.GroupView;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.dto.RunDtos.RunView;
import com.gmsr.scheduler.service.BossService;
import com.gmsr.scheduler.service.RunService;
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

/** BOSS 分组、BOSS 与车次接口。 */
@RestController
@RequestMapping("/api")
public class BossController {

    private final BossService bossService;
    private final RunService runService;

    public BossController(BossService bossService, RunService runService) {
        this.bossService = bossService;
        this.runService = runService;
    }

    @GetMapping("/boss-groups")
    public List<GroupView> groups() {
        return bossService.listGroups();
    }

    @PostMapping("/boss-groups")
    public GroupView createGroup(@Valid @RequestBody GroupRequest request) {
        return bossService.createGroup(request);
    }

    @PutMapping("/boss-groups/{id}")
    public GroupView updateGroup(@PathVariable Long id, @Valid @RequestBody GroupRequest request) {
        return bossService.updateGroup(id, request);
    }

    @DeleteMapping("/boss-groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        bossService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bosses")
    public List<BossView> bosses() {
        return bossService.listBosses();
    }

    @GetMapping("/bosses/{id}")
    public BossView boss(@PathVariable Long id) {
        return bossService.getBoss(id);
    }

    @PostMapping("/bosses")
    public BossView createBoss(@Valid @RequestBody BossRequest request) {
        return bossService.createBoss(request);
    }

    @PutMapping("/bosses/{id}")
    public BossView updateBoss(@PathVariable Long id, @Valid @RequestBody BossRequest request) {
        return bossService.updateBoss(id, request);
    }

    @DeleteMapping("/bosses/{id}")
    public ResponseEntity<Void> deleteBoss(@PathVariable Long id) {
        bossService.deleteBoss(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bosses/{id}/runs")
    public List<RunView> runsOfBoss(@PathVariable Long id) {
        return runService.list().stream().filter(r -> r.bossId().equals(id)).toList();
    }

    @PostMapping("/runs")
    public RunView createRun(@Valid @RequestBody RunRequest request) {
        return runService.create(request);
    }
}
