package com.gmsr.scheduler.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 车次与席位相关 DTO。 */
public final class RunDtos {

    private RunDtos() {
    }

    public record MemberView(
            Long id,
            Long characterId,
            Long playerId,
            String playerNickname,
            String job,
            int power,
            int slotNo,
            String role,
            String status,
            /** 席位备注（预留） */
            String note,
            /** 角色备注：前端悬停角色时显示（如「主号 / 只打周末」） */
            String characterNote,
            /** 是否主号 */
            boolean mainChar) {
    }

    public record RunView(
            Long id,
            String weekKey,
            String weekLabel,
            Long bossId,
            String bossName,
            String bossIconKey,
            String groupName,
            String tier,
            String tierLabel,
            int capacity,
            int runIndex,
            String scheduled,
            String status,
            String note,
            int memberCount,
            int powerTotal,
            List<MemberView> members) {
    }

    public record RunRequest(
            /** 排班周期；为空表示本周 */
            String weekKey,
            @NotNull(message = "BOSS 不能为空") Long bossId,
            String tier,
            @Min(value = 1, message = "车次序号至少为 1") Integer runIndex,
            String scheduled,
            String status,
            String note) {
    }

    public record MemberRequest(
            @NotNull(message = "角色不能为空") Long characterId,
            Integer slotNo,
            String role,
            String status,
            String note) {
    }

    public record MemberUpdateRequest(Integer slotNo, String role, String status, String note) {
    }

    /** 拖拽调车：把某个席位拖到另一趟车上。 */
    public record MoveMemberRequest(
            @NotNull(message = "目标车次不能为空") Long targetRunId,
            Integer slotNo) {
    }

    /** 拖拽换位请求：把两个席位对调。 */
    public record SwapMemberRequest(
            @jakarta.validation.constraints.NotNull(message = "缺少另一个席位") Long otherMemberId) {
    }

    /** 整队保存：一次性替换这趟车的成员名单。 */
    public record MemberBatchRequest(List<MemberRequest> members) {
    }

    /** 群友的车次安排。 */
    public record PlayerRunView(
            Long runId,
            Long bossId,
            String bossName,
            String groupName,
            String tier,
            int runIndex,
            String scheduled,
            String status,
            int memberCount,
            int capacity,
            /** 角色 id：前端「按角色筛选」需要（只靠职业名会混淆同职业的两个角色） */
            Long characterId,
            String characterJob,
            int characterPower,
            String weekKey,
            String weekLabel) {
    }
}
