package com.evops.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪上下文（基于 SLF4J MDC）。
 * <p>
 * 全链路两个 key：{@link #MDC_TRACE_ID} 贯穿一次请求/任务（异步线程池、定时任务同样携带），
 * {@link #MDC_ORDER_NO} 为当前正在处理的加工单号（无单号的日志占位于 {@value #NONE}），
 * 由 logback pattern 统一输出，按单号即可 grep 聚合全部相关日志。
 * <p>
 * 入口约定：HTTP 请求由 {@link TraceIdFilter} 初始化；后台线程由
 * {@link TraceTaskDecorator}（线程池任务）/ ScheduledTraceAspect（定时任务）兜底，
 * 任何线程打日志前都保证 traceId 已就位。
 */
public final class TraceContext {

    /** 请求头/响应头名：外部可传入串联上下游，响应头原样回带 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_ORDER_NO = "orderNo";

    /** 无加工单号时的占位符：保证日志格式列对齐，grep 单号不受影响 */
    public static final String NONE = "-";

    /** 外部传入 traceId 的合法形态：防 CRLF 注入响应头、防伪造日志列 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9A-Za-z-]{1,64}$");

    private TraceContext() {
    }

    /**
     * 请求入口初始化：传入合法则沿用（串联上游），否则新生成；同时放单号占位。
     *
     * @return 实际生效的 traceId
     */
    public static String initTrace(String incoming) {
        String traceId = isValid(incoming) ? incoming.trim() : newTraceId();
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_ORDER_NO, NONE);
        return traceId;
    }

    /** 非请求入口（后台线程/定时任务/启动流程）兜底：无 traceId 则生成，缺占位则补齐 */
    public static void ensureTrace() {
        if (MDC.get(MDC_TRACE_ID) == null) {
            MDC.put(MDC_TRACE_ID, newTraceId());
        }
        if (MDC.get(MDC_ORDER_NO) == null) {
            MDC.put(MDC_ORDER_NO, NONE);
        }
    }

    public static String traceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    /** 记录当前正在处理的加工单号；null/空白复位为占位符（单据边界外不残留上一单号） */
    public static void putOrderNo(String orderNo) {
        MDC.put(MDC_ORDER_NO,
                orderNo == null || orderNo.trim().isEmpty() ? NONE : orderNo.trim());
    }

    /** 清理本工具管理的全部 key（线程归还前调用，防线程复用串号） */
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_ORDER_NO);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValid(String traceId) {
        return traceId != null && TRACE_ID_PATTERN.matcher(traceId.trim()).matches();
    }
}
