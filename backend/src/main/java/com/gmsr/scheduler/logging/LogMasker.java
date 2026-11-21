package com.gmsr.scheduler.logging;

/**
 * 日志脱敏工具。
 *
 * <p>为什么需要它：本系统的日志要落到磁盘并保留多天，一旦把口令、令牌、QQ 号原样写进去，
 * 就等于在服务器上留了一份明文凭据。改动前有两处是直接插值的：
 * {@code PlayerAccountInitializer} 把群友初始密码打进日志、
 * {@code DataMigrator} 把账号名（= QQ 号）打进日志。
 *
 * <p><b>设计取舍</b>：这里**不做**全局正则「全文扫描替换」。试过那种写法：
 * 用 {@code \d{5,11}} 匹配账号会把日期 {@code 20261121}、计数、耗时一起吃掉，
 * 日志反而变得不可读，也容易误伤。所以改成**显式点位脱敏** ——
 * 调用方明确知道「这个参数是账号 / 是口令」，再交给本类处理，
 * 每一处改动在代码评审时都是可见的。
 */
public final class LogMasker {

    /** 完全打码。 */
    public static final String MASK = "***";

    private LogMasker() {
    }

    /**
     * 账号（= QQ 号）脱敏：保留前 2 位与后 2 位，中间固定用 {@code ***}。
     *
     * <p>空值返回「-」，非纯数字（例如手工建的 {@code admin}）只保留首字符，
     * 避免「一眼看不出是谁」和「完整泄露」两个极端。
     *
     * <p>例：{@code 123456789 -> 12***89}、{@code admin -> a***}、{@code 12 -> ***}
     */
    public static String maskAccount(String account) {
        if (account == null || account.isEmpty()) {
            return "-";
        }
        if (account.length() <= 4) {
            return MASK;
        }
        boolean allDigits = account.chars().allMatch(Character::isDigit);
        if (!allDigits) {
            return account.charAt(0) + MASK;
        }
        return account.substring(0, 2) + MASK + account.substring(account.length() - 2);
    }

    /**
     * 口令 / 令牌类字段：不管内容是什么，一律只输出 {@link #MASK}。
     * 用于「这条日志想说『设置了密码』但绝不能说『密码是什么』」的场合。
     */
    public static String maskSecret(Object value) {
        if (value == null) {
            return "未设置";
        }
        return MASK;
    }

    /**
     * 只保留长度信息的口令提示，用于启动自检日志
     * （例如「初始密码已设置（长度 10）」）。让运维能判断是不是空口令，又拿不到内容。
     */
    public static String describeSecret(String value) {
        if (value == null) {
            return "未设置";
        }
        if (value.isEmpty()) {
            return "空";
        }
        return "已设置（长度 " + value.length() + "）";
    }
}
