package com.gmsr.scheduler.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** BOSS 与分组相关 DTO。 */
public final class BossDtos {

    private BossDtos() {
    }

    public record GroupView(Long id, String name, int sortOrder, String note, long bossCount) {
    }

    public record GroupRequest(
            @NotBlank(message = "分组名称不能为空") String name,
            Integer sortOrder,
            String note) {
    }

    public record BossView(
            Long id,
            Long groupId,
            String groupName,
            String name,
            String iconKey,
            String tier,
            String tierLabel,
            /** 这个 BOSS 实际存在的难度（列表），排班页据此生成难度选项 */
            List<String> tiers,
            int capacity,
            int sortOrder,
            boolean enabled,
            String note,
            /** 融合车标记：同 key 的 BOSS 合成一个车次一起打；为空表示单独排班 */
            String comboKey,
            /** 融合车显示名（为空表示不是融合车成员） */
            String comboName,
            /** 融合车固定难度 */
            String comboTier,
            /** 是否按月刷新（如老黑能量车）：排班周期用 上月/本月/下月 */
            boolean monthly,
            long runCount,
            int assignedCount) {
    }

    public record BossRequest(
            Long groupId,
            @NotBlank(message = "BOSS 名称不能为空") String name,
            String iconKey,
            String tier,
            /** 这个 BOSS 实际存在的难度，逗号分隔或数组均可 */
            List<String> tiers,
            @Min(value = 1, message = "队伍人数至少 1 人") Integer capacity,
            Integer sortOrder,
            Boolean enabled,
            String note) {
    }
}
