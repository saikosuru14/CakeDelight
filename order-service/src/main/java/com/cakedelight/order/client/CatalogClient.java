package com.cakedelight.order.client;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.cakedelight.order.service.exception.CakeNotFoundException;
import com.cakedelight.order.service.exception.CakeUnavailableException;
import com.cakedelight.order.service.exception.CatalogUnavailableException;

/**
 * Synchronous read of cake data owned by the Catalog Service (Requirement 10.2).
 *
 * <p>Cross-service reads live in {@code client/}; cross-service writes never happen. The single
 * public method either returns a snapshot of a purchasable cake or throws one of exactly three
 * exceptions, so callers never have to inspect HTTP status codes:
 *
 * <ul>
 *   <li>404 from the catalog -> {@link CakeNotFoundException} carrying the cake identifier
 *       (Requirement 3.5, HTTP 404)</li>
 *   <li>200 with {@code available == false} -> {@link CakeUnavailableException}
 *       (Requirement 3.6, HTTP 409)</li>
 *   <li>connect failure, read timeout, 5xx, or an uninterpretable response ->
 *       {@link CatalogUnavailableException} (Requirements 3.5, 10.2, HTTP 503)</li>
 * </ul>
 *
 * <p>The availability check deliberately lives here rather than in {@code BasketService}: it is a
 * property of the cake the catalog returned, not of the basket operation, so putting it in the
 * client guarantees every present and future caller applies the rule identically and no caller can
 * accidentally accept an unavailable cake.
 *
 * <h2>Fault tolerance</h2>
 *
 * <p>Timeouts come from the {@code catalogRestClient} bean (5 s connect, 5 s read). On top of them
 * {@link #fetchAvailableCake(UUID)} retries <em>only</em> {@link CatalogUnavailableException}, which
 * is exactly the transient set: connect failure, read timeout, 5xx, and an unreadable or empty body.
 * {@link CakeNotFoundException} and {@link CakeUnavailableException} are deterministic answers from a
 * healthy catalog, so retrying them would only add load and latency without ever changing the
 * outcome; they propagate on the first attempt.
 *
 * <p>The budget is deliberately small: 3 total attempts with exponential backoff of 200 ms, then
 * 400 ms, capped at 1 s, adding at most 600 ms of sleep before the caller gets its 503. Every value
 * is configurable under {@code catalog.service.retry.*}. When the last attempt fails,
 * {@link #recoverExhausted(CatalogUnavailableException, UUID)} rethrows
 * {@code CatalogUnavailableException} so the external contract is unchanged: the global exception
 * handler still maps it to HTTP 503 {@code CATALOG_UNAVAILABLE}.
 *
 * <p>{@link #rethrowNonRetryable(RuntimeException, UUID)} is the second half of that recovery
 * contract and exists because Spring Retry sends every terminal failure through the recovery path,
 * not only an exhausted budget. See its javadoc: without it the two deterministic answers were
 * replaced by {@code ExhaustedRetryException} and lost their HTTP status.
 *
 * <p>There is no circuit breaker and no bulkhead: a catalog that is down still costs every request
 * its full attempt budget.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private static final String CAKE_BY_ID_PATH = "/api/cakes/{cakeId}";

    private final RestClient catalogRestClient;

    /** Reported in the give-up log line; the interceptor enforces the value itself. */
    private final int maxAttempts;

    public CatalogClient(
            @Qualifier("catalogRestClient") RestClient catalogRestClient,
            @Value("${catalog.service.retry.max-attempts:3}") int maxAttempts) {
        this.catalogRestClient = catalogRestClient;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Fetches a cake that can be purchased right now, retrying transient catalog failures.
     *
     * @param cakeId the cake identifier to read from the Catalog Service
     * @return the snapshot of an available cake; its price becomes the captured unit price of a
     *         basket item (Requirement 3.2)
     * @throws CakeNotFoundException      the catalog does not store the identifier (Requirement 3.5);
     *                                    never retried
     * @throws CakeUnavailableException   the cake exists but is not purchasable (Requirement 3.6);
     *                                    never retried
     * @throws CatalogUnavailableException the catalog could not be reached or answered unusably on
     *                                    every attempt
     */
    @Retryable(
            retryFor = CatalogUnavailableException.class,
            maxAttemptsExpression = "${catalog.service.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${catalog.service.retry.initial-backoff-ms:200}",
                    multiplierExpression = "${catalog.service.retry.multiplier:2.0}",
                    maxDelayExpression = "${catalog.service.retry.max-backoff-ms:1000}"))
    public CakeSnapshot fetchAvailableCake(UUID cakeId) {
        CakeSnapshot snapshot;
        try {
            snapshot = fetch(cakeId);
        } catch (CatalogUnavailableException e) {
            // One line per failed attempt, so the retry behaviour is visible in the logs.
            log.warn("Catalog Service read for cake {} failed on attempt {} of {}: {}",
                    cakeId, currentAttempt(), maxAttempts, e.getReason());
            throw e;
        }

        if (!snapshot.available()) {
            throw new CakeUnavailableException(cakeId);
        }
        return snapshot;
    }

    /**
     * Called once the attempt budget is exhausted. Rethrows the last
     * {@link CatalogUnavailableException} so the response stays HTTP 503
     * {@code CATALOG_UNAVAILABLE} exactly as it was before retries existed.
     *
     * @param exception the failure from the final attempt
     * @param cakeId    the cake identifier that could not be read
     * @return never returns; the signature only has to match the retried method
     */
    @Recover
    public CakeSnapshot recoverExhausted(CatalogUnavailableException exception, UUID cakeId) {
        log.error("Giving up on the Catalog Service for cake {} after {} attempt(s)",
                cakeId, maxAttempts, exception);
        throw exception;
    }

    /**
     * Rethrows a deterministic catalog answer unchanged, so it keeps its own HTTP status.
     *
     * <p>WHY THIS METHOD EXISTS. Spring Retry routes <em>every</em> terminal failure of a
     * {@code @Retryable} method through the recovery path, not just an exhausted attempt budget.
     * {@code RetryTemplate.doExecute} leaves its loop the moment the policy classifies an exception
     * as non-retryable and calls {@code handleRetryExhausted}, which delegates to
     * {@code RecoverAnnotationRecoveryHandler}. That handler looks for a {@code @Recover} method
     * whose throwable parameter is assignable from the exception it was handed, and when it finds
     * none it throws {@code ExhaustedRetryException("Cannot locate recovery method")} carrying the
     * real exception only as a cause.
     *
     * <p>{@link CakeNotFoundException} and {@link CakeUnavailableException} are deliberately absent
     * from {@code retryFor}, so with {@link #recoverExhausted} as the only recovery method they
     * matched nothing and left this class as {@code ExhaustedRetryException} - a type no
     * {@code @ExceptionHandler} in the Order Service knows. The fallback handler therefore answered
     * HTTP 500 {@code INTERNAL_ERROR} instead of HTTP 404 {@code CAKE_NOT_FOUND} and HTTP 409
     * {@code CAKE_UNAVAILABLE} (Requirements 3.5, 3.6).
     *
     * <p>Declaring a recovery method for {@link RuntimeException} closes that gap for every
     * non-retryable exception at once. Nothing is translated or classified here: the original
     * exception is rethrown with its type, message, cause and stack trace intact, so the global
     * exception handler maps it exactly as it would if no retry proxy sat in front of this class.
     * {@code RecoverAnnotationRecoveryHandler} selects the closest match by inheritance distance, so
     * {@link #recoverExhausted} still wins for {@link CatalogUnavailableException} and keeps its
     * give-up log line.
     *
     * @param exception the non-retryable failure from the single attempt that ran
     * @param cakeId    the cake identifier that was being read; part of the signature so it matches
     *                  the retried method
     * @return never returns; the signature only has to match the retried method
     */
    @Recover
    public CakeSnapshot rethrowNonRetryable(RuntimeException exception, UUID cakeId) {
        throw exception;
    }

    private CakeSnapshot fetch(UUID cakeId) {
        CakeSnapshot snapshot;
        try {
            snapshot = catalogRestClient.get()
                    .uri(CAKE_BY_ID_PATH, cakeId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, response) -> {
                                throw new CakeNotFoundException(cakeId);
                            })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new CatalogUnavailableException(
                                cakeId, "responded with status " + response.getStatusCode().value());
                    })
                    .body(CakeSnapshot.class);
        } catch (ResourceAccessException e) {
            // Connect failure or read timeout: the RestClient timeouts fired.
            throw new CatalogUnavailableException(cakeId, "the call could not be completed", e);
        } catch (RestClientException e) {
            // Anything else the client layer could not turn into a usable response body.
            throw new CatalogUnavailableException(cakeId, "the response could not be read", e);
        }

        if (snapshot == null) {
            throw new CatalogUnavailableException(cakeId, "the response body was empty");
        }
        return snapshot;
    }

    /**
     * The 1-based number of the attempt currently running. The retry count is only incremented after
     * the failure is registered by the interceptor, which happens after this method returns, so the
     * running attempt is the recorded count plus one. Falls back to 1 when the method is called
     * outside the retry interceptor, for example directly in a unit test.
     */
    private static int currentAttempt() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? 1 : context.getRetryCount() + 1;
    }
}
