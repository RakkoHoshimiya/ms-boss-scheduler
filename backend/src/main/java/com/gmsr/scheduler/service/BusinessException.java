package com.gmsr.scheduler.service;

/** 业务校验失败。 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
