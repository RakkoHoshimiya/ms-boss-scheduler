package com.gmsr.scheduler.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 群友与角色相关 DTO。 */
public final class PlayerDtos {

    private PlayerDtos() {
    }

    public record CharacterView(
            Long id,
            Long playerId,
            String playerNickname,
            String job,
            int power,
            boolean mainChar,
            String note,
            int sortOrder) {
    }

    public record PlayerView(
            Long id,
            /** 显示名称 */
            String nickname,
            /** 登录用的 QQ 号 */
            String qq,
            String remark,
            boolean active,
            int sortOrder,
            int characterCount,
            List<CharacterView> characters) {
    }

    public record PlayerRequest(
            @NotBlank(message = "显示名称不能为空") String nickname,
            String qq,
            String remark,
            Boolean active,
            Integer sortOrder) {
    }

    /** 群友本人在「我的角色」页提交的内容：不需要也不能指定 playerId。 */
    public record MyCharacterRequest(
            @NotBlank(message = "职业不能为空") String job,
            @NotNull(message = "战力不能为空") @Min(value = 0, message = "战力不能为负数") Integer power,
            Boolean mainChar,
            String note) {
    }

    public record CharacterRequest(
            @NotNull(message = "群友不能为空") Long playerId,
            @NotBlank(message = "职业不能为空") String job,
            @NotNull(message = "战力不能为空") @Min(value = 0, message = "战力不能为负数") Integer power,
            Boolean mainChar,
            String note,
            Integer sortOrder) {
    }
}
