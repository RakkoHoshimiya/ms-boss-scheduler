package com.gmsr.scheduler.web;

import com.gmsr.scheduler.service.WeeklyStatusResetService;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护类接口（仅管理员）。
 *
 * 目前只有「手动把全部车次改回未完成」——定时任务是每周四 08:00 自动跑，
 * 这个入口用于：定时没跑成功时手动补救，或者测试。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminMaintenanceController {

    private final WeeklyStatusResetService resetService;

    public AdminMaintenanceController(WeeklyStatusResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/runs/reset-status")
    public Map<String, Object> resetRunStatus() {
        int changed = resetService.resetNow();
        return Map.of(
                "reset", changed,
                "at", Instant.now().toString());
    }
}
