package com.ratelimiter.auth.exception;

/**
 * An error whose message is safe to return to the caller.
 *
 * <p>Everything else is reported generically. The handler used to echo
 * {@code ex.getMessage()} from any RuntimeException straight back, which leaked
 * internal detail such as "Tenant not found: &lt;uuid&gt;", "User not found" and
 * whatever text a persistence failure happened to carry.
 */
public class ClientVisibleException extends RuntimeException {
    public ClientVisibleException(String message) {
        super(message);
    }
}
