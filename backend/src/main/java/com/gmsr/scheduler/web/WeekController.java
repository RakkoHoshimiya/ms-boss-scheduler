package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.WeekDtos.CopyBossWeekRequest;
import com.gmsr.scheduler.dto.WeekDtos.CopyWeekRequest;
import com.gmsr.scheduler.dto.WeekDtos.WeekDetail;
import com.gmsr.scheduler.dto.WeekDtos.WeekListResponse;
import com.gmsr.scheduler.dto.WeekDtos.WeekView;
import com.gmsr.scheduler.dto.RunDtos.RunView;
import com.gmsr.scheduler.service.RunService;
import com.gmsr.scheduler.service.WeekService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 排班管理：上周 / 本周 / 下周，以及一键复制。 */
@RestController
@RequestMapping("/api/weeks")
public class WeekController {

    private final WeekService weekService;
    private final RunService runService;

    public WeekController(WeekService weekService, RunService runService) {
        this.weekService = weekService;
        this.runService = runService;
    }

    /** 全部周期列表 + 当前周期。 */
    @GetMapping
    public WeekListResponse weeks() {
        return new WeekListResponse(WeekService.currentKey(), weekService.listWeeks());
    }

    /** 上周 / 本周 / 下周 各 BOSS 的趟数（前端显示真实趟数用）。 */
    @GetMapping("/run-counts")
    public Map<String, Map<Long, Long>> runCounts() {
        WeekDetail detail = weekService.triples();
        return weekService.runsPerBoss(List.of(
                detail.lastWeek().weekKey(),
                detail.thisWeek().weekKey(),
                detail.nextWeek().weekKey()));
    }

    /** 上周 / 本周 / 下周。 */
    @GetMapping("/triples")
    public WeekDetail triples() {
        return weekService.triples();
    }

    /**
     * 按月刷新的 BOSS（老黑能量车）用的周期：上月 / 本月 / 下月。
     * 复用同一套结构，只是 key 变成「2026-10」这种月份格式。
     */
    @GetMapping("/month-triples")
    public WeekDetail monthTriples() {
        return weekService.monthTriples();
    }

    @GetMapping("/{weekKey}")
    public WeekView week(@PathVariable String weekKey) {
        return weekService.getWeek(weekKey);
    }

    /** 某个周期的车次。 */
    @GetMapping("/{weekKey}/runs")
    public List<RunView> runs(@PathVariable String weekKey) {
        return runService.listByWeek(weekKey);
    }

    /** 一键复制（单个 BOSS）：把它在源周期的车次与阵容复制到目标周期。 */
    @PostMapping("/copy-boss")
    public WeekView copyBoss(@Valid @RequestBody CopyBossWeekRequest request) {
        return weekService.copyBossRuns(
                request.fromWeekKey(),
                request.toWeekKey(),
                request.bossId(),
                request.tier(),
                Boolean.TRUE.equals(request.overwrite()));
    }

    /** 一键复制：把源周期的排班复制到目标周期，之后自行修改。 */
    @PostMapping("/copy")
    public WeekView copy(@Valid @RequestBody CopyWeekRequest request) {
        return weekService.copyWeek(
                request.fromWeekKey(),
                request.toWeekKey(),
                Boolean.TRUE.equals(request.overwrite()));
    }
}
