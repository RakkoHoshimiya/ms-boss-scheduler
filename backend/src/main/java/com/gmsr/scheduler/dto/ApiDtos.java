package com.gmsr.scheduler.dto;

import jakarta.validation.constraints.NotBlank;

/** 认证与用户相关 DTO。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record UserView(
            Long id,
            String username,
            String displayName,
            String role,
            Long playerId,
            String playerNickname,
            String playerQq,
            boolean mustChangePassword,
            /** 是否有 boss排班 权限 */
            boolean canSchedule) {
    }

    public record LoginResponse(
            /** 访问令牌（SA-Token 签发，2 小时有效），请求接口时放在 gmsr-token 请求头里 */
            String token,
            /** 刷新令牌（7 天滑动），access token 过期时用它换新的 */
            String refreshToken,
            /** access token 剩余有效期（秒） */
            long expiresInSeconds,
            UserView user,
            boolean mustChangePassword) {
    }

    /** 重置结果只返回账号与提示，不回显密码本身。 */
    public record ResetPasswordResponse(String username, String message) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    /**
     * 新建账号。
     *
     * 与 {@link UserUpdateRequest} 拆开是有原因的：以前只有一个 UserUpsertRequest，
     * 「编辑账号」时前端不传 playerId，服务端按「null = 解绑」处理，
     * 于是管理员只想改个显示名，就把账号绑定的群友静默解绑了。
     * 现在「绑定关系」只在建号时给出，编辑走单独的记录，语义不再靠 null 猜。
     */
    public record UserUpsertRequest(
            @NotBlank(message = "用户名不能为空") String username,
            String password,
            @NotBlank(message = "显示名不能为空")
            @jakarta.validation.constraints.Size(max = 64, message = "显示名最长 64 个字") String displayName,
            String role,
            Boolean enabled,
            Boolean canSchedule,
            Long playerId) {
    }

    /**
     * 编辑账号。
     *
     * 刻意**不提供** playerId 字段：账号与群友的绑定关系不允许在这里改，
     * 否则「没传」和「传 null」无法区分，正是之前静默解绑的根源。
     * 需要调整绑定时请走专门的管理操作。
     */
    public record UserUpdateRequest(
            @NotBlank(message = "显示名不能为空")
            @jakarta.validation.constraints.Size(max = 64, message = "显示名最长 64 个字") String displayName,
            String role,
            Boolean enabled,
            Boolean canSchedule,
            /** 留空表示不改密码 */
            String password) {
    }

    /** 群友绑定/解绑自己账号时用。 */
    /** 用户改自己的显示名称。 */
    public record DisplayNameRequest(
            @jakarta.validation.constraints.NotBlank(message = "显示名称不能为空")
            @jakarta.validation.constraints.Size(max = 64, message = "显示名称最长 64 个字")
            String displayName) {
    }


    public record ApiError(String code, String message) {
    }
}
