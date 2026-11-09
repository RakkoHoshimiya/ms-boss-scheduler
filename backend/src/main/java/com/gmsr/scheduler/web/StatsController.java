package com.gmsr.scheduler.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查（原统计总览接口已按需求移除）。 */
@RestController
@RequestMapping("/api")
public class StatsController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "gmsr-scheduler");
    }
}
