package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.ApiDtos.ApiError;
import com.gmsr.scheduler.service.BusinessException;
import com.gmsr.scheduler.service.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("BUSINESS_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "参数不合法：" + ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "接口不存在"));
    }

    /**
     * 方法不对（例如把只接受 POST 的接口用 GET 调）。
     * 不单独处理的话会落到下面的兜底，返回 500，把客户端的错误说成服务端故障。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", "该接口不支持 " + ex.getMethod() + " 方法"));
    }

    /**
     * 会话/存储不可用（Redis 宕机、连接超时等）。
     *
     * 这类是**可重试**的临时故障，用 503 而不是 500 —— 客户端能据此显示「稍后再试」，
     * 也避免把「依赖挂了」误报成「服务器内部错误」。
     * 尤其重要：登录/刷新在签发令牌时如果写不进 Redis，必须明确失败，
     * 绝不能返回一个看似成功、实际没存下的会话。
     */
    @ExceptionHandler({
            org.springframework.data.redis.RedisConnectionFailureException.class,
            org.springframework.data.redis.RedisSystemException.class,
            org.springframework.dao.QueryTimeoutException.class,
    })
    public ResponseEntity<ApiError> handleStorageUnavailable(Exception ex) {
        log.error("存储服务不可用：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("STORAGE_UNAVAILABLE", "会话服务暂时不可用，请稍后重试"));
    }

    /**
     * 数据库约束被触发（容量、同一群友单角色、唯一键、外键等）。
     *
     * 按 SQLSTATE 分类给出用户能懂的提示：
     *   - 23514 check_violation：我们自己的触发器，消息本身就是中文，直接透传
     *   - 23503 外键：说明还有关联数据，提示先处理关联
     *   - 23505 唯一键：提示已存在
     *   - 其它：给通用提示，**不把原始 SQL 错误抛给前端**（里面有表名、约束名）
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(org.springframework.dao.DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String raw = cause == null ? null : cause.getMessage();
        String sqlState = null;
        if (cause instanceof java.sql.SQLException sqlEx) {
            sqlState = sqlEx.getSQLState();
        }

        String code;
        String message;
        if ("23514".equals(sqlState) && raw != null) {
            // 业务不变量触发器（容量、同人单角色…），消息是给用户看的
            code = "CONSTRAINT_VIOLATION";
            message = raw.split("\n")[0].replaceFirst("^ERROR:\s*", "");
        } else if ("23503".equals(sqlState)) {
            code = "REFERENCED_DATA";
            message = "这条数据还被其它记录引用，请先处理关联数据";
        } else if ("23505".equals(sqlState)) {
            code = "DUPLICATE";
            message = "已经存在相同的记录";
        } else {
            code = "CONSTRAINT_VIOLATION";
            message = "数据不满足约束，操作未完成";
        }
        log.warn("数据约束拒绝写入（{}）：{}", sqlState, raw);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOthers(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务器内部错误，请稍后重试"));
    }
}
