package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppSetting;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.repository.AppSettingRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import java.time.DayOfWeek;
import java.time.Duration;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    /**
     * 用编程式事务而不是 @Transactional：
     * 定时任务和启动补跑都是**同一个对象内部**调 resetIfDue()，
     * 走不到 Spring 的事务代理，@Transactional 会静默失效 ——
     * 于是「改状态」和「写重置标记」不在一个事务里，
     * 万一改完状态、写标记失败，下次补跑会再重置一次，把期间用户新标的完成状态抹掉。
     */
    private final TransactionTemplate transactionTemplate;
    /** 多实例互斥：同一时刻只让一个实例执行重置 */
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "gmsr:lock:status-reset";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    public WeeklyStatusResetService(BossRunRepository runRepository,
                                    AppSettingRepository settingRepository,
                                    TransactionTemplate transactionTemplate,
                                    StringRedisTemplate redis) {
        this.runRepository = runRepository;
        this.settingRepository = settingRepository;
        this.transactionTemplate = transactionTemplate;
        this.redis = redis;
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
     * 到点了就重置。整个「判断 + 改状态 + 写标记」在一个事务里，并加分布式锁。
     *
     * @return 本次重置了多少趟车（0 表示这次不需要重置）
     */
    public int resetIfDue() {
        if (!tryLock()) {
            log.info("状态重置：另一个实例正在执行，本次跳过");
            return 0;
        }
        try {
            Integer changed = transactionTemplate.execute(status -> doResetIfDue());
            return changed == null ? 0 : changed;
        } finally {
            unlock();
        }
    }

    /** 手动重置（管理员用，也方便验证）。 */
    public int resetNow() {
        if (!tryLock()) {
            throw new BusinessException("另一个实例正在执行重置，请稍后重试");
        }
        try {
            Integer changed = transactionTemplate.execute(status -> {
                int count = doReset();
                saveMarker(Instant.now());
                log.info("状态重置：管理员手动重置，{} 趟车次改回「未完成」", count);
                return count;
            });
            return changed == null ? 0 : changed;
        } finally {
            unlock();
        }
    }

    /** 事务内：判断是否到点，到点则重置并写标记。 */
    private int doResetIfDue() {
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

    /**
     * 抢锁。Redis 不可用时按「单实例」继续执行 ——
     * 宁可重复重置（结果仍是全部未完成，无副作用），也不要因为 Redis 抖动漏掉每周重置。
     */
    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL));
        } catch (Exception ex) {
            log.warn("获取重置锁失败（按单实例继续）：{}", ex.getMessage());
            return true;
        }
    }

    private void unlock() {
        try {
            redis.delete(LOCK_KEY);
        } catch (Exception ignored) {
            // 锁带 TTL，删不掉也会自动过期
        }
    }

    /** 上次重置的时间点（没有记录时返回 null）。 */
    public Instant lastResetAt() {
        return readMarker();
    }

    /** 下一次会自动重置的时间点。 */
    public Instant nextResetAt() {
        return nextScheduledInstant(ZonedDateTime.now(ZONE));
    }

    /** 下一次「周四 08:00」；如果现在正好是这个时刻（或已过），取下一周。 */
    static Instant nextScheduledInstant(ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate()
                .with(TemporalAdjusters.nextOrSame(RESET_DAY))
                .atTime(RESET_TIME)
                .atZone(now.getZone());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
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
