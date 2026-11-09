package com.gmsr.scheduler.service;

/**
 * 功能未开通 / 未配置（例如语音功能没配 api-key、没开开关）。
 *
 * 与 {@link BusinessException} 的区别：这不是用户操作错误（400），
 * 而是「当前环境不具备这个能力」，映射成 503 更准确，前端可以据此提示「暂未开放」。
 *
 * 单独定义类型而不是复用 {@code IllegalStateException}：
 * 后者在框架内部大量使用，全局捕获会把真正的服务端 bug 也说成「暂时不可用」。
 */
public class FeatureUnavailableException extends RuntimeException {

    public FeatureUnavailableException(String message) {
        super(message);
    }
}
