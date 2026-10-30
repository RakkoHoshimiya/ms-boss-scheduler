package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppSetting;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.repository.AppSettingRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每周四 08:00 把所有车次的状态改回「未完成」。
 *
 * 背景：排班本身是**固定**的（不再分周），但游戏里 BOSS 是每周四刷新，
 * 所以「打没打完」这层状态必须每周归零 —— 否则上周的「已完成」会一直挂着。
 *
 * 三件事：
 *   1. 定时：每周四 08:00（显式指定东八区，不依赖服务器时区）
 *   2. 补跑：服务停机错过了 08:00，启动时发现「上次重置早于最近一次周四 08:00」就补一次
 *   3. 首次部署只记录时间点、**不清数据** —— 否则第一次上线就把当周的完成标记全抹了
 *
 * 重置范围：所有车次的 status → PLANNED（含之前标成 CANCELED 的，新的一周重新决定）。
 */
@Service
public class WeeklyStatusResetService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyStatusResetService.class);

    /** app_setting 里的键：最近一次已经重置到哪个时间点（ISO-8601） */
    private static final String MARKER_KEY = "run-status-reset-at";

    /** 重置时刻：每周四 08:00（游戏日常刷新时间） */
    private static final DayOfWeek RESET_DAY = DayOfWeek.THURSDAY;
    private static final LocalTime RESET_TIME = LocalTime.of(8, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BossRunRepository runRepository;
    private final AppSettingRepository settingRepository;

    public WeeklyStatusResetService(BossRunRepository runRepository, AppSettingRepository settingRepository) {
        this.runRepository = runRepository;
        this.settingRepository = settingRepository;
    }

    /** 每周四 08:00 触发。 */
    @Scheduled(cron = "0 0 8 * * THU", zone = "Asia/Shanghai")
    public void scheduledReset() {
        try {
            resetIfDue();
        } catch (Exception ex) {
            // 定时任务里抛异常会被吞掉，至少留下日志
            log.error("每周状态重置失败", ex);
        }
    }

    /** 启动时补跑一次（覆盖「周四 08:00 时服务没在跑」的情况）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        try {
            resetIfDue();
        } catch (Exception ex) {
            log.warn("启动时补跑状态重置失败：{}", ex.getMessage());
        }
    }

    /**
     * 到点了就重置。
     *
     * @return 本次重置了多少趟车（0 表示这次不需要重置）
     */
    @Transactional
    public int resetIfDue() {
        Instant due = lastScheduledInstant(ZonedDateTime.now(ZONE));
        Instant last = readMarker();

        if (last == null) {
            // 首次部署：只记下时间点，不动现有数据
            saveMarker(due);
            log.info("状态重置：首次初始化，记录基准时间 {}（本次不清空完成标记）", due);
            return 0;
        }
        if (!last.isBefore(due)) {
            return 0; // 已经重置过这一周的点了
        }
        int changed = doReset();
        saveMarker(due);
        log.info("状态重置：已把 {} 趟车次改回「未完成」（重置点 {}）", changed, due);
        return changed;
    }

    /** 手动重置（管理员用，也方便验证）。 */
    @Transactional
    public int resetNow() {
        int changed = doReset();
        saveMarker(Instant.now());
        log.info("状态重置：管理员手动重置，{} 趟车次改回「未完成」", changed);
        return changed;
    }

    /** 最近一次「周四 08:00」的时间点；如果今天就是周四但还没到 8 点，则取上周四。 */
    static Instant lastScheduledInstant(ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(RESET_DAY))
                .atTime(RESET_TIME)
                .atZone(now.getZone());
        if (candidate.isAfter(now)) {
            candidate = candidate.minusWeeks(1);
        }
        return candidate.toInstant();
    }

    private int doReset() {
        List<BossRun> runs = runRepository.findAllByOrderByBossSortOrderAscRunIndexAsc();
        int changed = 0;
        for (BossRun run : runs) {
            if (run.getStatus() != BossRun.Status.PLANNED) {
                run.setStatus(BossRun.Status.PLANNED);
                changed++;
            }
        }
        if (changed > 0) {
            runRepository.saveAll(runs);
            runRepository.flush();
        }
        return changed;
    }

    private Instant readMarker() {
        return settingRepository.findById(MARKER_KEY)
                .map(AppSetting::getValue)
                .map(value -> {
                    try {
                        return Instant.parse(value);
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private void saveMarker(Instant instant) {
        AppSetting setting = settingRepository.findById(MARKER_KEY)
                .orElseGet(() -> new AppSetting(MARKER_KEY, instant.toString()));
        setting.setValue(instant.toString());
        setting.setUpdatedAt(Instant.now());
        settingRepository.save(setting);
    }
}
