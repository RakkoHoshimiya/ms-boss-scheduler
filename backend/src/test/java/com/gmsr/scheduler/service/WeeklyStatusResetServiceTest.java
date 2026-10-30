package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * 每周重置的时间点计算。
 *
 * 这段逻辑最容易出错的地方是「本周四还没到 8 点」和「正好周四 8 点」两种边界：
 * 算错就会导致多重置一次（把刚打的标记清掉）或者漏重置。
 */
class WeeklyStatusResetServiceTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private static Instant last(ZonedDateTime now) {
        return WeeklyStatusResetService.lastScheduledInstant(now);
    }

    private static ZonedDateTime at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, SH);
    }

    @Test
    @DisplayName("周四 08:00 之前 → 取上一周的周四 08:00")
    void thursdayBeforeEight() {
        // 2026-10-29 是周四
        assertEquals(at(10, 22, 8, 0).toInstant(), last(at(10, 29, 7, 59)));
    }

    @Test
    @DisplayName("周四 08:00 整 → 就是这一刻")
    void thursdayExactlyEight() {
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(10, 29, 8, 0)));
    }

    @Test
    @DisplayName("周四 08:00 之后 → 取本周四 08:00")
    void thursdayAfterEight() {
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(10, 29, 13, 30)));
    }

    @Test
    @DisplayName("周五/周六/周日 → 都取最近的周四 08:00")
    void laterInWeek() {
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(10, 30, 19, 46))); // 周五
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(10, 31, 23, 59))); // 周六
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(11, 1, 0, 5)));    // 周日（跨月）
    }

    @Test
    @DisplayName("周一/周二/周三 → 取上周四 08:00")
    void earlierInWeek() {
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(11, 2, 9, 0)));  // 周一
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(11, 3, 12, 0))); // 周二
        assertEquals(at(10, 29, 8, 0).toInstant(), last(at(11, 4, 23, 59))); // 周三
    }

    @Test
    @DisplayName("下周四 08:00 之后 → 前进一周（跨月）")
    void nextThursday() {
        assertEquals(at(11, 5, 8, 0).toInstant(), last(at(11, 5, 8, 1)));
        assertEquals(at(11, 5, 8, 0).toInstant(), last(at(11, 8, 10, 0)));
    }

    @Test
    @DisplayName("跨年：1/1 周四 08:00 之后取当天")
    void acrossNewYear() {
        // 2026-12-31 是周四，2027-01-01 是周五
        assertEquals(at(12, 31, 8, 0).toInstant(), last(at(1, 1, 10, 0).withYear(2027)));
    }
    @Test
    @DisplayName("cron 表达式「0 0 8 * * THU」在下个周四 08:00 触发")
    void cronFiresOnThursdayEight() {
        CronExpression cron = CronExpression.parse("0 0 8 * * THU");
        // 2026-10-30 是周五，下一次应当是 11-05 周四 08:00
        java.time.ZonedDateTime next = cron.next(at(10, 30, 19, 0));
        assertEquals(at(11, 5, 8, 0).toInstant(), next.toInstant());
        // 从周四 08:00 之后算，下一次是下周四
        java.time.ZonedDateTime next2 = cron.next(at(11, 5, 8, 1));
        assertEquals(at(11, 12, 8, 0).toInstant(), next2.toInstant());
    }
    @Test
    @DisplayName("下次重置：周五 → 下周四 08:00")
    void nextFromFriday() {
        assertEquals(at(11, 5, 8, 0).toInstant(),
                WeeklyStatusResetService.nextScheduledInstant(at(10, 30, 19, 0)));
    }

    @Test
    @DisplayName("下次重置：周四 07:59 → 当天 08:00（还没到）")
    void nextBeforeEight() {
        assertEquals(at(10, 29, 8, 0).toInstant(),
                WeeklyStatusResetService.nextScheduledInstant(at(10, 29, 7, 59)));
    }

    @Test
    @DisplayName("下次重置：周四 08:00 整 → 下一周（这一刻已经在重置）")
    void nextExactlyEight() {
        assertEquals(at(11, 5, 8, 0).toInstant(),
                WeeklyStatusResetService.nextScheduledInstant(at(10, 29, 8, 0)));
    }
}
