package com.aman.connector.client;

/** Thrown when the WooCommerce API answers with an error status or an unreadable body. */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** True for 429 and 5xx — the only statuses worth retrying. */
    public boolean isRetryable() {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }
}
