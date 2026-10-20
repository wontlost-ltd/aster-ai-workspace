package com.wontlost.aster.aiw.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 允许 aster-cloud 以 iframe 嵌入本服务。
 *
 * <p>保证最终响应只带 {@code Content-Security-Policy: frame-ancestors <cloudOrigin>}，
 * 且不出现 {@code X-Frame-Options}。下游（Vaadin 等）可能晚于本过滤器写入这两个头，
 * 因此用响应包装器在写入时拦截，并在链结束后再确认一次。
 */
public class FrameAncestorsFilter extends OncePerRequestFilter {
    private static final String CSP = "Content-Security-Policy";
    private static final String XFO = "X-Frame-Options";

    private final String policy;

    public FrameAncestorsFilter(String cloudOrigin) {
        this.policy = "frame-ancestors " + cloudOrigin;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var wrapped = new Guarded(response, policy);
        wrapped.setHeader(CSP, policy);
        chain.doFilter(request, wrapped);
        if (!response.isCommitted()) {
            response.setHeader(CSP, policy);
            response.setHeader(XFO, null);
        }
    }

    /** 丢弃 X-Frame-Options，并把任何 CSP 写入固化为本服务的策略。 */
    private static final class Guarded extends HttpServletResponseWrapper {
        private final String policy;

        Guarded(HttpServletResponse response, String policy) {
            super(response);
            this.policy = policy;
        }

        @Override
        public void setHeader(String name, String value) {
            if (XFO.equalsIgnoreCase(name)) {
                return;
            }
            super.setHeader(name, CSP.equalsIgnoreCase(name) ? policy : value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (XFO.equalsIgnoreCase(name)) {
                return;
            }
            if (CSP.equalsIgnoreCase(name)) {
                super.setHeader(name, policy);
                return;
            }
            super.addHeader(name, value);
        }
    }
}
