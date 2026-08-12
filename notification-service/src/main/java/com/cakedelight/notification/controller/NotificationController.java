package com.cakedelight.notification.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cakedelight.notification.dto.NotificationResponse;
import com.cakedelight.notification.service.NotificationService;

/**
 * Notification lookup endpoint for one order (Requirement 8.5).
 *
 * <p>Holds no business logic and exposes DTOs only: the service returns entities, and this class maps
 * them onto {@link NotificationResponse} so the JPA entity never crosses the HTTP boundary.
 */
@RestController
@RequestMapping("/api/notifications/orders/{orderId}")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Lists every stored delivery attempt for the order (Requirement 8.5).
     *
     * <p>Always a list, never a single record, because FAILED attempts accumulate for one order
     * (Requirement 8.3). An order with no attempt answers 200 with an empty list rather than 404: the
     * read succeeded, there is simply nothing recorded yet.
     */
    @GetMapping
    public List<NotificationResponse> list(@PathVariable UUID orderId) {
        return notificationService.findByOrderId(orderId).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
