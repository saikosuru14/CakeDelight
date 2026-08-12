package com.cakedelight.notification.dto;

import java.time.Instant;

/**
 * The single error response shape used by every Cake Delight component (Requirement 12.2).
 *
 * <p>Field set is fixed: an error {@code code}, a human readable {@code message}, the
 * {@code timestamp} the failure was rendered, and the request {@code path} that produced it. Each
 * service keeps its own copy of this record; there is no shared library between services.
 *
 * @param code      stable machine readable error code, for example {@code VALIDATION_ERROR}
 * @param message   human readable explanation, safe to show a caller
 * @param timestamp when the error response was produced
 * @param path      the request path that failed
 */
public record ErrorResponse(String code, String message, Instant timestamp, String path) {

    /** Builds a response stamped with the current instant. */
    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, Instant.now(), path);
    }
}
