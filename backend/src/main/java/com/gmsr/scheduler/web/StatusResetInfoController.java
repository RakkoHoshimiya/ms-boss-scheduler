package com.gmsr.scheduler.web;

import com.gmsr.scheduler.service.WeeklyStatusResetService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「完成状态」重置时间的只读信息（游客也能看）。
 *
 * 用途：页面上显示「上次重置 / 下次重置」，
 * 这样当大家某天打开发现全变成「未完成」时，能一眼看到是不是刚过了重置点，
 * 而不是以为数据丢了。
 */
@RestController
public class StatusResetInfoController {

    private final WeeklyStatusResetService resetService;

    public StatusResetInfoController(WeeklyStatusResetService resetService) {
        this.resetService = resetService;
    }

    @GetMapping("/api/status-reset")
    public Map<String, Object> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("lastResetAt", resetService.lastResetAt() == null ? null : resetService.lastResetAt().toString());
        result.put("nextResetAt", resetService.nextResetAt().toString());
        return result;
    }
}
