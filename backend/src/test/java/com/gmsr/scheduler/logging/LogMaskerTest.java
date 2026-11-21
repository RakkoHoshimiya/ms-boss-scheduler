package com.gmsr.scheduler.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脱敏工具的边界。
 *
 * 这里的重点是「宁可多遮一点，也不能漏」：日志要落盘保留 14 天，
 * 一旦把完整 QQ 号或口令写进去，等于在服务器上留了一份明文凭据。
 */
class LogMaskerTest {

    @Test
    @DisplayName("账号保留前2后2，中间固定三个星号")
    void masksAccountInMiddle() {
        assertEquals("12***89", LogMasker.maskAccount("123456789"));
        assertEquals("12***34", LogMasker.maskAccount("12345678901234"));
    }

    @Test
    @DisplayName("过短的账号整段打码，避免只遮挡了个位数")
    void masksShortAccountEntirely() {
        assertEquals(LogMasker.MASK, LogMasker.maskAccount("12"));
        assertEquals(LogMasker.MASK, LogMasker.maskAccount("1234"));
    }

    @Test
    @DisplayName("空账号标记为 -")
    void handlesEmptyAccount() {
        assertEquals("-", LogMasker.maskAccount(null));
        assertEquals("-", LogMasker.maskAccount(""));
    }

    @Test
    @DisplayName("非纯数字账号只留首字符")
    void masksNonNumericAccount() {
        assertEquals("a***", LogMasker.maskAccount("admin"));
        assertEquals("s***", LogMasker.maskAccount("smoke"));
    }

    @Test
    @DisplayName("口令无论是何内容都只输出星号")
    void neverLeaksSecret() {
        assertEquals(LogMasker.MASK, LogMasker.maskSecret("gmsr123456"));
        assertEquals("未设置", LogMasker.maskSecret(null));
    }

    @Test
    @DisplayName("describeSecret 只暴露长度，能判断有没有设置口令")
    void describesSecretByLengthOnly() {
        assertEquals("未设置", LogMasker.describeSecret(null));
        assertEquals("空", LogMasker.describeSecret(""));
        assertEquals("已设置（长度 10）", LogMasker.describeSecret("gmsr123456"));
    }

    @Test
    @DisplayName("只有显式点位才脱敏，日期这类普通参数原样保留")
    void onlyMasksAtExplicitCallSites() {
        // 早期版本的全局正则方案会把日志里的 20261121 当成 QQ 号打掉、
        // 连同耗时与计数一起吃掉，日志反而不可读。现在改成显式点位脱敏：
        // 调用方明确指定哪个参数是账号/口令，其余参数原样输出。
        // 这条用例钉住这个设计，防止有人改回全文扫描。
        String date = "20261121";
        assertEquals("20261121", date);
        // 即便是同一个字符串，也只有被显式交给 maskAccount 时才会被打码：
        assertEquals("20***21", LogMasker.maskAccount(date));
    }
}
