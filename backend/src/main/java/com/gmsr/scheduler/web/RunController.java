package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.RunDtos.MemberBatchRequest;
import com.gmsr.scheduler.dto.RunDtos.MemberRequest;
import com.gmsr.scheduler.dto.RunDtos.MemberUpdateRequest;
import com.gmsr.scheduler.dto.RunDtos.PlayerRunView;
import com.gmsr.scheduler.dto.RunDtos.MemberView;
import com.gmsr.scheduler.dto.RunDtos.MoveMemberRequest;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.dto.RunDtos.SwapMemberRequest;
import com.gmsr.scheduler.dto.RunDtos.RunView;
import com.gmsr.scheduler.security.CurrentUser;
import com.gmsr.scheduler.service.BusinessException;
import com.gmsr.scheduler.service.RunService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 车次与席位接口。 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping("/runs")
    public List<RunView> runs() {
        return runService.list();
    }

    @GetMapping("/runs/{id}")
    public RunView run(@PathVariable Long id) {
        return runService.get(id);
    }

    /**
     * 标记攻坚状态：管理员/编辑可以标记任意车次，
     * 绑定了群友的账号只能标记自己所在的车次。
     */
    @PutMapping("/runs/{id}/status")
    public RunView updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        boolean editor = isEditor();
        Long playerId = CurrentUser.playerId();
        if (!runService.canTouch(id, editor, playerId)) {
            throw new BusinessException("你不在这一趟车上，不能标记它的状态");
        }
        return runService.updateStatus(id, request == null ? null : request.status());
    }

    /** 查询当前账号在这趟车上的角色，用于前端显示「我的车」标记。 */
    @GetMapping("/runs/{id}/my-members")
    public List<MemberView> myMembers(@PathVariable Long id) {
        return runService.myMembers(id, CurrentUser.playerId());
    }

    @PutMapping("/runs/{id}")
    public RunView update(@PathVariable Long id, @Valid @RequestBody RunRequest request) {
        return runService.update(id, request);
    }

    private static boolean isEditor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_EDITOR".equals(a.getAuthority()));
    }

    public record StatusRequest(String status) {
    }

    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        runService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/{id}/resequence")
    public List<RunView> resequence(@PathVariable Long id) {
        RunView run = runService.get(id);
        return runService.resequence(run.bossId());
    }

    @PostMapping("/runs/{id}/members")
    public RunView addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
        return runService.addMember(id, request);
    }

    /** 整队保存：一次性替换这趟车的成员名单。 */
    @PutMapping("/runs/{id}/members")
    public RunView replaceMembers(@PathVariable Long id, @RequestBody MemberBatchRequest request) {
        return runService.replaceMembers(id, request == null ? null : request.members());
    }

    @PutMapping("/runs/{id}/members/{memberId}")
    public RunView updateMember(
            @PathVariable Long id,
            @PathVariable Long memberId,
            @RequestBody MemberUpdateRequest request) {
        return runService.updateMember(memberId, request);
    }

    @DeleteMapping("/runs/{id}/members/{memberId}")
    public RunView removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        return runService.removeMember(memberId);
    }

    /** 拖拽调车：把某个席位拖到另一趟车上（返回源/目标两趟车的最新数据）。 */
    @PostMapping("/runs/{runId}/members/{memberId}/move")
    public Map<String, RunView> moveMember(
            @PathVariable Long runId,
            @PathVariable Long memberId,
            @Valid @RequestBody MoveMemberRequest request) {
        return runService.moveMember(memberId, request.targetRunId(), request.slotNo());
    }

    /** 拖拽换位：把两个席位对调（同一 BOSS 下的两趟车之间）。 */
    @PostMapping("/runs/{runId}/members/{memberId}/swap")
    public Map<String, RunView> swapMembers(
            @PathVariable Long runId,
            @PathVariable Long memberId,
            @Valid @RequestBody SwapMemberRequest request) {
        return runService.swapMembers(memberId, request.otherMemberId());
    }

    /** 某个群友的车次安排。 */
    @GetMapping("/players/{playerId}/runs")
    public List<PlayerRunView> playerRuns(@PathVariable Long playerId) {
        return runService.playerRuns(playerId);
    }

    /** 复制一趟车到同一 BOSS 下的新序号。 */
    @PostMapping("/runs/{id}/copy")
    public RunView copy(@PathVariable Long id) {
        RunView source = runService.get(id);
        RunView created = runService.create(new RunRequest(
                source.bossId(),
                source.tier(),
                null,
                source.scheduled(),
                "PLANNED",
                source.note()));
        for (var member : source.members()) {
            runService.addMember(created.id(), new MemberRequest(
                    member.characterId(),
                    member.slotNo(),
                    member.role(),
                    member.status(),
                    member.note()));
        }
        return runService.get(created.id());
    }
}
