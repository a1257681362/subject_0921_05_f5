package com.evops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 统一异常出口。所有异常落一行带 traceId/orderNo（MDC）的日志：
 * 业务异常 warn 单行不刷屏；未预期异常 error 带堆栈——质控按 traceId 即可定位现场。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException ex) {
        log.warn("业务异常 code={} message={}", ex.getBizCode().getCode(),
                LogMasks.singleLine(ex.getMessage()));
        return ApiResponse.fail(ex.getBizCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败：{}", LogMasks.singleLine(detail));
        return ApiResponse.fail(BizCode.VALIDATION_FAILED,
                BizCode.VALIDATION_FAILED.getMessage() + "：" + detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", LogMasks.singleLine(ex.getMessage()));
        return ApiResponse.fail(BizCode.VALIDATION_FAILED, "请求体格式不正确");
    }

    /**
     * 库层唯一约束兜底。只按约束/索引名判定：异常消息含整条 SQL，
     * 按表名列名匹配会误判（SQL 中所有列名同时出现）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toUpperCase();
        log.warn("唯一约束冲突：{}", truncate(LogMasks.singleLine(ex.getMessage())));
        if (msg.contains("UK_ORDER_REQUEST")) { // 请求号唯一（重复提交），先于单号判定
            return ApiResponse.fail(BizCode.REQUEST_DUPLICATED);
        }
        if (msg.contains("UK_BATCH_JOB_REQUEST")) {
            return ApiResponse.fail(BizCode.REQUEST_DUPLICATED);
        }
        if (msg.contains("UK_ORDER_NO")) {
            return ApiResponse.fail(BizCode.ORDER_NO_DUPLICATED);
        }
        if (msg.contains("UK_LENS_CODE")) {
            return ApiResponse.fail(BizCode.LENS_CODE_DUPLICATED);
        }
        if (msg.contains("UK_ORDER_RX_ACTIVE")) {
            return ApiResponse.fail(BizCode.RX_EYE_DUPLICATED);
        }
        return ApiResponse.fail(BizCode.DATA_DUPLICATED);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数：{}", LogMasks.singleLine(ex.getMessage()));
        return ApiResponse.fail(BizCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        // 未预期异常必须留堆栈：响应只有"系统处理失败"，现场全靠这行日志（带 traceId）
        log.error("系统处理失败", ex);
        return ApiResponse.fail(BizCode.SYSTEM_ERROR, "系统处理失败");
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    /** 异常消息可能含整条 SQL，日志保留前 300 字符足够定位约束名 */
    private static String truncate(String text) {
        if (text == null || text.length() <= 300) {
            return text;
        }
        return text.substring(0, 300) + "...";
    }
}
