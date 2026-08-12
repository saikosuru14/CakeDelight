package com.cakedelight.notification.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes one structured log entry per request (Requirement 12.5).
 *
 * <p>Reuses an inbound {@code X-Request-Id} when the caller supplies one, otherwise generates one, so
 * a request can be followed across services. The identifier is placed in the MDC under
 * {@code requestId}, which is the key the logging pattern in {@code application.yml} reads, and is
 * echoed back on the response. The entry carries the request identifier, HTTP method, path, response
 * status, and elapsed milliseconds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** Header carrying the correlation identifier, inbound and outbound. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key; must match the {@code %X{requestId}} entry in the logging pattern. */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("requestId={} method={} path={} status={} elapsedMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMs);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }
}
