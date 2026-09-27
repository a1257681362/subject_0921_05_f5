package com.evops.common.trace;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 定时任务链路兜底：@Scheduled 方法没有上游请求，每次触发生成新 traceId，
 * 执行结束清理——定时清理等后台日志同样带 traceId，可独立成串检索。
 */
@Aspect
@Component
public class ScheduledTraceAspect {

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object traceScheduled(ProceedingJoinPoint joinPoint) throws Throwable {
        TraceContext.ensureTrace();
        try {
            return joinPoint.proceed();
        } finally {
            TraceContext.clear();
        }
    }
}
