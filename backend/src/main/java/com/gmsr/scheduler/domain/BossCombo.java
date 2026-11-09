package com.gmsr.scheduler.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合车（合并车次）定义。
 *
 * 背景：有些 BOSS 在实际打法里是「一队人连续打完」的，要打全打、要不打都不打，
 * 所以不应该各自单独排车次。融合车把这一组 BOSS 合成一个车次来管理：
 * <ul>
 *   <li>排班界面只显示一个融合条目（含全部 BOSS 图标与名称），难度固定 H；</li>
 *   <li>一辆融合车对应每个成员 BOSS 各一条 BossRun（这样按 BOSS 的统计、周期汇总都还能正常工作）；</li>
 *   <li>改阵容时所有成员 BOSS 的车次同步更新，保证「要打全打」。</li>
 * </ul>
 */
public final class BossCombo {

    /** 一个融合车定义。 */
    public record Combo(String key, String name, String description, String tier, List<String> bossNames) {
    }

    /** 神秘河「融合车」：这 7 个 BOSS 一起打。 */
    public static final Combo MYSTIC_7 = new Combo(
            "mystic-7",
            "融合车（7 连）",
            "戴米安 → 绿水灵王 → 路西德 → 威尔 → 至暗魔晶 → 觉醒希拉 → 敦凯尔，要打全打",
            "H",
            List.of("戴米安", "绿水灵王", "路西德", "威尔", "至暗魔晶", "觉醒希拉", "敦凯尔"));

    public static final List<Combo> ALL = List.of(MYSTIC_7);

    private static final Map<String, Combo> BY_KEY = new LinkedHashMap<>();

    static {
        for (Combo combo : ALL) {
            BY_KEY.put(combo.key(), combo);
        }
    }

    public static Combo byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    private BossCombo() {
    }
}
