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
            /**
             * 游戏里**实际存在**的难度（canonical 事实，来自 BossTierCatalog）。
             * 前端据此禁用不存在的难度（如卡尔洛斯没有 Hard）；空列表表示未登记、不限制。
             */
            List<String> allowedTiers,
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
