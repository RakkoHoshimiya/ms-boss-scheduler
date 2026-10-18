package com.gmsr.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/** 排班周期相关 DTO。 */
public final class WeekDtos {

    private WeekDtos() {
    }

    public record WeekView(
            String weekKey,
            String label,
            LocalDate startDate,
            LocalDate endDate,
            String note,
            long runCount) {
    }

    /** 上周 / 本周 / 下周 + 当前周标识。 */
    public record WeekDetail(WeekView lastWeek, WeekView thisWeek, WeekView nextWeek, String currentWeekKey) {
    }

    public record CopyWeekRequest(
            @NotBlank(message = "源周期不能为空") String fromWeekKey,
            @NotBlank(message = "目标周期不能为空") String toWeekKey,
            Boolean overwrite) {
    }

    /** 按单个 BOSS 复制排班。 */
    public record CopyBossWeekRequest(
            @NotBlank(message = "源周期不能为空") String fromWeekKey,
            @NotBlank(message = "目标周期不能为空") String toWeekKey,
            @jakarta.validation.constraints.NotNull(message = "BOSS 不能为空") Long bossId,
            /** 只复制这个难度的车次；留空表示全部难度 */
            String tier,
            Boolean overwrite) {
    }

    public record WeekListResponse(String currentWeekKey, List<WeekView> weeks) {
    }
}
