package com.gmsr.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GMSR 国际服冒险岛 BOSS 组队排班系统 入口。
 */
@SpringBootApplication
@EnableScheduling
public class GmsrSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GmsrSchedulerApplication.class, args);
    }
}
