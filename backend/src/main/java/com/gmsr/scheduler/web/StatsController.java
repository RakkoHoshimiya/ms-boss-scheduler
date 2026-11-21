package com.gmsr.scheduler.web;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查（原统计总览接口已按需求移除）。 */
@RestController
@RequestMapping("/api")
public class StatsController {

    private final ApplicationAvailability availability;

    public StatsController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", ready ? "UP" : "DOWN", "service", "gmsr-scheduler"));
    }
}
