package com.evops.common.trace;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求入口链路初始化（最高优先级，先于 Shiro/业务拦截器）：
 * 从 {@value TraceContext#HEADER_TRACE_ID} 请求头读取 traceId（缺省/非法则生成），
 * 写入 MDC 贯穿本次请求全部日志；同名响应头原样回带给调用方，请求结束清理 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.initTrace(request.getHeader(TraceContext.HEADER_TRACE_ID));
        response.setHeader(TraceContext.HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
