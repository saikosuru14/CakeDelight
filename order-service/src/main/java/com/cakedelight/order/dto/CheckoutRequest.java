package com.cakedelight.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/orders} (Requirement 5.2).
 *
 * <p>Checkout carries no item list: the order is built from the basket already stored for
 * {@code customerId}, so the client cannot smuggle in a price or a quantity that the Catalog Service
 * never confirmed. The only thing checkout adds to what is already stored is the contact address.
 *
 * <p>Both messages name their field on purpose. Requirement 5.5 asks for a 400 whose validation
 * message names the email field, and it has to name it for the omitted case and the malformed case
 * alike, so {@code @NotBlank} and {@code @Email} each spell out {@code customerEmail}. Relying on
 * the default Bean Validation text would produce "must not be blank" with the field name only in the
 * {@code field} metadata.
 *
 * <p>{@code @Email} accepts the address format only; no mailbox exists check happens anywhere, which
 * keeps checkout independent of any mail server.
 *
 * @param customerId    the customer whose basket is being checked out
 * @param customerEmail the address the order confirmation is later sent to, carried into the order
 *                      and then into the order completed event
 */
public record CheckoutRequest(
        @NotBlank(message = "customerId is required") String customerId,
        @NotBlank(message = "customerEmail is required")
        @Email(message = "customerEmail must be a well-formed email address")
        String customerEmail) {
}
