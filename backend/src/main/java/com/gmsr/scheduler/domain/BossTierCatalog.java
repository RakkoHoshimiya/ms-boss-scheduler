package com.gmsr.scheduler.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个 BOSS 在游戏里**实际存在**哪些难度（canonical 事实）。
 *
 * 为什么单独建这个类：
 *   排班里的「难度」其实是两件事，混在一起就会出问题 ——
 *     ① 游戏事实：这个 BOSS 有哪几个难度可选（本类）
 *     ② 我们的选择：这周打它的哪几个难度（Boss.tiers）
 *
 *   以前只有 ②，编辑界面就把 5 个难度全列出来让你勾。
 *   于是「监视者卡尔洛斯没有 Hard」这种**游戏事实**在界面上完全看不出来，
 *   还能被误勾上，存进数据库后才变成脏数据。
 *
 *   现在 ② 必须落在 ① 之内：
 *     - 前端把不在 ① 里的难度禁用（不可勾选）
 *     - 后端保存时再校验一次，越界直接拒绝
 *
 * 约定：不在表里的 BOSS 视为「未登记」，此时不做限制（返回空列表）。
 *   这样游戏出新 BOSS 时，不用改这里也能先排班，之后再补登记。
 */
public final class BossTierCatalog {

    private BossTierCatalog() {
    }

    /** 难度固定顺序（与 BossTier 枚举一致） */
    public static final List<String> TIER_ORDER = List.of("E", "N", "H", "C", "EX");

    /**
     * BOSS 名 -> 游戏内存在的难度。
     *
     * 顺序沿用 TIER_ORDER 的固定次序，方便前端展示与比对。
     */
    private static final Map<String, List<String>> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("神选者塞伦", List.of("N", "H", "EX"));
        // 卡尔洛斯只有 Easy / Normal / Chaos / Extreme，游戏里**没有** Hard
        ALLOWED.put("监视者卡尔洛斯", List.of("E", "N", "C", "EX"));
        ALLOWED.put("最初的对抗者", List.of("E", "N", "H", "EX"));
        ALLOWED.put("卡琳", List.of("E", "N", "H", "EX"));
        ALLOWED.put("灿烂的凶星", List.of("N", "H", "C"));
        ALLOWED.put("林波", List.of("N", "H"));
        ALLOWED.put("斯乌", List.of("N", "H", "C", "EX"));
        ALLOWED.put("戴米安", List.of("N", "H"));
        ALLOWED.put("绿水灵王", List.of("N", "H", "C"));
        ALLOWED.put("老黑能量车", List.of("H", "EX"));
        ALLOWED.put("路西德", List.of("E", "N", "H"));
        ALLOWED.put("威尔", List.of("E", "N", "H"));
        ALLOWED.put("至暗魔晶", List.of("N", "C", "H"));
        ALLOWED.put("觉醒希拉", List.of("N", "H"));
        ALLOWED.put("敦凯尔", List.of("N", "H"));
    }

    /** 这个 BOSS 存在的难度；未登记返回空列表（表示不限制）。 */
    public static List<String> allowedTiersOf(String bossName) {
        if (bossName == null) {
            return List.of();
        }
        return ALLOWED.getOrDefault(bossName.trim(), List.of());
    }

    /** 该 BOSS 在游戏里是否有这个难度；未登记的 BOSS 一律放行。 */
    public static boolean allows(String bossName, String tier) {
        List<String> allowed = allowedTiersOf(bossName);
        return allowed.isEmpty() || allowed.contains(tier);
    }

    /** 难度中文名，用于报错信息。 */
    public static String labelOf(String tier) {
        if (tier == null) {
            return "";
        }
        return switch (tier) {
            case "E" -> "Easy";
            case "N" -> "Normal";
            case "H" -> "Hard";
            case "C" -> "Chaos";
            case "EX" -> "Extreme";
            default -> tier;
        };
    }

    /** 全量表（供初始化脚本使用）。 */
    public static Map<String, List<String>> all() {
        return ALLOWED;
    }
}
