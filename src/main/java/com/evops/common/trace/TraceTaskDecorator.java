package com.evops.common.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步线程 MDC 传递：任务提交时捕获提交线程的 MDC（traceId 等），
 * 工作线程执行前恢复、执行后清理——批量后台任务的日志与提交它的请求
 * 共用同一 traceId，跨线程也能一次 grep 串起。
 * 提交线程本身无 trace（如启动恢复派发）时，由 {@link TraceContext#ensureTrace()}
 * 为本次后台执行生成新 traceId，保证任何线程日志都带 traceId 列。
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            if (captured != null && !captured.isEmpty()) {
                MDC.setContextMap(captured);
            }
            TraceContext.ensureTrace();
            try {
                runnable.run();
            } finally {
                TraceContext.clear();
            }
        };
    }
}
