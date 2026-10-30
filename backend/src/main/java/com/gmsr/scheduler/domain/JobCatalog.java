package com.gmsr.scheduler.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职业名归一化。
 *
 * 为什么要做：同一个职业在不同人嘴里有不同叫法 ——
 *   Kanna / 阴阳师 / 陰陽師 / 칸나 / Onmyoji 指的是**同一个职业**。
 * 不统一就会把同一个角色录成两条：
 *   群友G 就同时有「Kanna 160M」和「阴阳师 160M」，其实是同一个角色。
 *
 * 约定：以 **GMSR 官方职业名 Kanna** 为标准写法，其余别名统一折算过去。
 * 前端的新增职业下拉也只保留标准名，避免再产生别名数据。
 */
public final class JobCatalog {

    private JobCatalog() {
    }

    /**
     * 别名 -> 标准名。
     *
     * 只放**确定是同一个职业**的写法，拿不准的宁可不动 ——
     * 误合并比不合并且难查。
     */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        // Kanna：阴阳师是其中文名
        ALIASES.put("阴阳师", "Kanna");
        ALIASES.put("陰陽師", "Kanna");
        ALIASES.put("칸나", "Kanna");
        ALIASES.put("Onmyoji", "Kanna");
        ALIASES.put("onmyoji", "Kanna");
        ALIASES.put("KANNA", "Kanna");
        ALIASES.put("kanna", "Kanna");
    }

    /** 把职业名折算成标准写法；无法识别时原样返回（去首尾空格）。 */
    public static String canonical(String job) {
        if (job == null) {
            return "";
        }
        String trimmed = job.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return ALIASES.getOrDefault(trimmed, trimmed);
    }

    /** 两个写法是不是同一个职业。 */
    public static boolean sameJob(String a, String b) {
        return canonical(a).equalsIgnoreCase(canonical(b));
    }
}
