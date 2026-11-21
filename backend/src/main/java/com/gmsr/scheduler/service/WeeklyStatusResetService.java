package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppSetting;
import com.gmsr.scheduler.repository.AppSettingRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把车次的完成状态改回「未完成」。
 *
 * 背景：排班本身是**固定**的（不再分周），但游戏里 BOSS 会刷新，
 * 所以「打没打完」这层状态必须跟着刷新周期归零 —— 否则上次的「已完成」会一直挂着。
 *
 * 游戏里其实有两种刷新节奏，这里分别处理：
 *   1. **每周刷新**：绝大多数 BOSS，每周四 08:00 归零；
 *   2. **每月刷新**：如「老黑能量车」（黑魔法师），每月 1 号 08:00 才刷一次，
 *      它**不跟随**每周重置 —— 否则一周就把它清一次，与游戏实际不符。
 *
 * 三件事（两种节奏各自独立）：
 *   1. 定时：每周四 08:00 / 每月 1 号 08:00（显式指定东八区，不依赖服务器时区）
 *   2. 补跑：服务停机错过了时间点，启动时发现「上次重置早于最近一次时间点」就补一次
 *   3. 首次部署只记录时间点、**不清数据** —— 否则第一次上线就把当周的完成标记全抹了
 *
 * 重置范围：对应车次的 status → PLANNED（含之前标成 CANCELED 的，新周期重新决定）。
 */
@Service
public class WeeklyStatusResetService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyStatusResetService.class);

    /** app_setting 里的键：最近一次「每周重置」已经重置到哪个时间点（ISO-8601） */
    private static final String MARKER_KEY = "run-status-reset-at";

    /** app_setting 里的键：最近一次「每月重置」已经重置到哪个时间点（ISO-8601） */
    private static final String MONTHLY_MARKER_KEY = "run-status-reset-month-at";

    /** 重置时刻：每周四 08:00（游戏日常刷新时间） */
    private static final DayOfWeek RESET_DAY = DayOfWeek.THURSDAY;
    private static final LocalTime RESET_TIME = LocalTime.of(8, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每月重置的时刻：每月 1 号 08:00。 */
    private static final int MONTHLY_RESET_DAY = 1;

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
    /**
     * 多实例互斥改用 **PostgreSQL 事务级咨询锁**（pg_try_advisory_xact_lock）。
     *
     * 为什么不用 Redis 锁：
     *   - 需要自己维护 owner 标识 + 原子比较删除，锁超时后可能误删别人的锁；
     *   - Redis 不可用时还得选「继续（可能重复重置）」还是「放弃（可能漏重置）」。
     * 事务级咨询锁天然满足需求：**随事务结束自动释放**，不会有过期与误删问题，
     * 也不依赖第二个中间件（重置本来就要写数据库）。
     */
    private final JdbcTemplate jdbcTemplate;

    /** 咨询锁的键（任意固定值，只要全局唯一即可） */
    private static final long ADVISORY_LOCK_ID = 20261031L;

    public WeeklyStatusResetService(BossRunRepository runRepository,
                                    AppSettingRepository settingRepository,
                                    TransactionTemplate transactionTemplate,
                                    JdbcTemplate jdbcTemplate) {
        this.runRepository = runRepository;
        this.settingRepository = settingRepository;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 每隔一段时间检查一次「是否已经过了最近一个重置点」，过了就重置。
     *
     * 为什么不用 @Scheduled：
     *   线上出现过「周四 08:00 什么都没发生」—— 进程从上周一直活着、
     *   注解也编译进了 class，但 JVM 里根本没有 Spring 的调度线程，任务从未被触发。
     *   与其依赖框架的调度器（还受虚拟线程等配置影响），不如自己起一个守护线程：
     *   行为完全可控，线程名固定（gmsr-reset-timer）也便于确认它在跑。
     *
     * 每 10 分钟检查一次，有以下好处：
     *   - 不依赖精确的 8:00 触发，误差最多 10 分钟
     *   - 停机错过了也能在恢复后 10 分钟内补上（不需要专门的「启动补跑」逻辑）
     *   - 本身是幂等的：标记没到点就直接返回，只有一条 SELECT 的开销
     */
    private static final long CHECK_INTERVAL_SECONDS = 600;
    private ScheduledExecutorService timer;

    @PostConstruct
    void startTimer() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gmsr-reset-timer");
            t.setDaemon(true);
            return t;
        });
        // 启动 20 秒后先检查一次：既覆盖「停机错过」，也避开启动初期的资源争抢
        timer.scheduleWithFixedDelay(this::tick, 20, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("状态重置：定时检查已启动（每 {} 分钟一次），上次每周重置 {}，下次每周 {}，下次每月 {}",
                CHECK_INTERVAL_SECONDS / 60, lastResetAt(), nextResetAt(), nextMonthlyResetAt());
    }

    @PreDestroy
    void stopTimer() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** 定时线程体：任何异常都要吞掉，否则一次失败会让后续检查全部停止。 */
    private void tick() {
        try {
            resetIfDue();
            resetMonthlyIfDue();
        } catch (Exception ex) {
            log.error("状态重置检查失败（下一轮会继续）", ex);
        }
    }

    /**
     * 到点了就重置每周刷新的那些车次。
     *
     * 整个「抢锁 + 判断 + 改状态 + 写标记」在**同一个事务**里：
     * 咨询锁与事务同生共死，不会出现「执行超时后锁过期、原实例又删了新实例的锁」这种问题。
     *
     * @return 本次重置了多少趟车（0 表示这次不需要重置，或别的实例正在做）
     */
    public int resetIfDue() {
        return executeLocked(() -> doResetIfDue(false));
    }

    /** 到点了就重置按月刷新（monthly=true）的那些车次。 */
    public int resetMonthlyIfDue() {
        return executeLocked(() -> doResetIfDue(true));
    }

    /**
     * 手动重置（管理员用，也方便验证）。
     *
     * **手动重置是「全部清空」，不区分每周 / 每月 BOSS** ——
     * 管理员点这个按钮的意图是「现在把完成状态都归零」，如果只清一半会更让人困惑。
     * 两个标记一起更新，避免紧接着的定时任务又补跑一次。
     */
    public int resetNow() {
        Integer changed = transactionTemplate.execute(status -> {
            if (!tryAdvisoryLock()) {
                throw new BusinessException("另一个实例正在执行重置，请稍后重试");
            }
            int count = runRepository.resetStatuses(null, Instant.now());
            saveMarker(MARKER_KEY, Instant.now());
            saveMarker(MONTHLY_MARKER_KEY, Instant.now());
            log.info("状态重置：管理员手动重置，{} 趟车次改回「未完成」", count);
            return count;
        });
        return changed == null ? 0 : changed;
    }

    private int executeLocked(java.util.function.IntSupplier body) {
        Integer changed = transactionTemplate.execute(status -> {
            if (!tryAdvisoryLock()) {
                log.info("状态重置：另一个实例正在执行，本次跳过");
                return 0;
            }
            return body.getAsInt();
        });
        return changed == null ? 0 : changed;
    }

    /**
     * 抢事务级咨询锁：拿不到说明别的实例已经在做。
     * 锁在事务提交/回滚时由 PostgreSQL 自动释放，无需 TTL，也没有「误删别人锁」的风险。
     */
    private boolean tryAdvisoryLock() {
        Boolean locked = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_ID);
        return Boolean.TRUE.equals(locked);
    }

    /** 事务内：判断是否到点，到点则重置并写标记。monthly=true 走每月节奏。 */
    private int doResetIfDue(boolean monthly) {
        String markerKey = monthly ? MONTHLY_MARKER_KEY : MARKER_KEY;
        Instant due = monthly
                ? lastMonthlyResetInstant(ZonedDateTime.now(ZONE))
                : lastScheduledInstant(ZonedDateTime.now(ZONE));
        Instant last = readMarker(markerKey);

        if (last == null) {
            // 首次部署：只记下时间点，不动现有数据
            saveMarker(markerKey, due);
            log.info("状态重置（{}）：首次初始化，记录基准时间 {}（本次不清空完成标记）",
                    monthly ? "每月" : "每周", due);
            return 0;
        }
        if (!last.isBefore(due)) {
            return 0; // 已经重置过这个点了
        }
        int changed = runRepository.resetStatuses(monthly, Instant.now());
        saveMarker(markerKey, due);
        log.info("状态重置（{}）：已把 {} 趟车次改回「未完成」（重置点 {}）",
                monthly ? "每月" : "每周", changed, due);
        return changed;
    }

    /** 上次「每周重置」的时间点（没有记录时返回 null）。 */
    public Instant lastResetAt() {
        return readMarker(MARKER_KEY);
    }

    /** 下一次「每周重置」的时间点。 */
    public Instant nextResetAt() {
        return nextScheduledInstant(ZonedDateTime.now(ZONE));
    }

    /** 上次「每月重置」的时间点（没有记录时返回 null）。 */
    public Instant lastMonthlyResetAt() {
        return readMarker(MONTHLY_MARKER_KEY);
    }

    /** 下一次「每月重置」的时间点（每月 1 号 08:00）。 */
    public Instant nextMonthlyResetAt() {
        return nextMonthlyResetInstant(ZonedDateTime.now(ZONE));
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

    /** 下一次「1 号 08:00」；如果现在正好是这个时刻（或已过），取下个月 1 号。 */
    static Instant nextMonthlyResetInstant(ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate()
                .withDayOfMonth(MONTHLY_RESET_DAY)
                .atTime(RESET_TIME)
                .atZone(now.getZone());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusMonths(1);
        }
        return candidate.toInstant();
    }

    /** 最近一次「1 号 08:00」；如果本月 1 号还没到 8 点，则取上个月 1 号。 */
    static Instant lastMonthlyResetInstant(ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate()
                .withDayOfMonth(MONTHLY_RESET_DAY)
                .atTime(RESET_TIME)
                .atZone(now.getZone());
        if (candidate.isAfter(now)) {
            candidate = candidate.minusMonths(1);
        }
        return candidate.toInstant();
    }

    private Instant readMarker(String key) {
        return settingRepository.findById(key)
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

    private void saveMarker(String key, Instant instant) {
        AppSetting setting = settingRepository.findById(key)
                .orElseGet(() -> new AppSetting(key, instant.toString()));
        setting.setValue(instant.toString());
        setting.setUpdatedAt(Instant.now());
        settingRepository.save(setting);
    }
}
