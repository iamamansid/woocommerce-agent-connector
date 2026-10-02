package com.aman.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the WooCommerce store this connector talks to.
 *
 * <p>Binds {@code connector.woocommerce.*} properties. Credentials are expected
 * via environment variables ({@code WC_BASE_URL}, {@code WC_CONSUMER_KEY},
 * {@code WC_CONSUMER_SECRET}) so they never end up in a committed file.
 */
@ConfigurationProperties(prefix = "connector.woocommerce")
public class ConnectorProperties {

    /** Store base URL, e.g. https://shop.example.com (no trailing slash). */
    private String baseUrl;

    /** WooCommerce REST API consumer key (ck_...). */
    private String consumerKey;

    /** WooCommerce REST API consumer secret (cs_...). */
    private String consumerSecret;

    /** Client-side request budget; WooCommerce has no published limit, so we stay polite by default. */
    private double requestsPerSecond = 5.0;

    /** How many times a 429/5xx response is retried before surfacing an error. */
    private int maxRetries = 3;

    /** Per-request connect+read timeout in seconds. */
    private int requestTimeoutSeconds = 15;

    /** Fail-fast sanity check; called by the client before first use. */
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("connector.woocommerce.base-url is required");
        if (consumerKey == null || consumerKey.isBlank()) throw new IllegalStateException("connector.woocommerce.consumer-key is required");
        if (consumerSecret == null || consumerSecret.isBlank()) throw new IllegalStateException("connector.woocommerce.consumer-secret is required");
        if (requestsPerSecond <= 0) throw new IllegalStateException("requests-per-second must be positive");
        if (requestTimeoutSeconds <= 0) throw new IllegalStateException("request-timeout-seconds must be positive");
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public String getConsumerSecret() { return consumerSecret; }
    public void setConsumerSecret(String consumerSecret) { this.consumerSecret = consumerSecret; }

    public double getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(double requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
}
