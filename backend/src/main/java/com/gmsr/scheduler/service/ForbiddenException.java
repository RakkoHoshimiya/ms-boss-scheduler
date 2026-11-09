package com.gmsr.scheduler.service;

/**
 * 已登录但身份/归属不满足要求（只能动自己的资源、只能标记自己所在车次等）。
 *
 * 映射成 403：与「没登录」（401）区分开，前端不应据此触发 token 刷新。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
