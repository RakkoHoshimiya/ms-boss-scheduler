package com.gmsr.scheduler.service;

/**
 * 未登录 / 登录态无效。
 *
 * 单独一个类型而不是复用 {@link BusinessException}：
 * 前者必须映射成 401，前端据此触发「用 refresh token 无感续期」；
 * 业务校验失败是 400，两者混在一起会让前端把「参数不对」当成「掉线」。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
