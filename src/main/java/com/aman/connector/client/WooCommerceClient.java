package com.aman.connector.client;

import com.aman.connector.config.ConnectorProperties;
import com.aman.connector.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level client for the WooCommerce REST API (v3).
 *
 * <ul>
 *   <li><b>Auth:</b> consumer key/secret sent as HTTP Basic credentials, the
 *   documented scheme for API-key auth over HTTPS. Keys with read-only scope
 *   are enough for everything this connector does.</li>
 *   <li><b>Politeness:</b> every request first takes a token from a client-side
 *   bucket ({@code requestsPerSecond}), so bursts never hammer the store.</li>
 *   <li><b>Resilience:</b> 429 and 5xx responses are retried with exponential
 *   backoff; a server-sent {@code Retry-After} header is honoured when present.</li>
 *   <li><b>Pagination:</b> list endpoints honour WooCommerce's
 *   {@code X-WP-TotalPages} header; {@link #getAllPages} walks every page.</li>
 * </ul>
 */
@Component
public class WooCommerceClient {

    private static final Logger log = LoggerFactory.getLogger(WooCommerceClient.class);
    private static final String API_PREFIX = "/wp-json/wc/v3";

    private final ConnectorProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final String basicAuthHeader;

    public WooCommerceClient(ConnectorProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    /** Package-visible for tests: allows injecting a stub-backed client. */
    WooCommerceClient(ConnectorProperties properties, ObjectMapper objectMapper,
                      HttpClient httpClient, RateLimiter rateLimiter) {
        properties.validate();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .build();
        this.rateLimiter = rateLimiter != null ? rateLimiter : new RateLimiter(properties.getRequestsPerSecond());
        String credentials = properties.getConsumerKey() + ":" + properties.getConsumerSecret();
        this.basicAuthHeader = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Raw response carrying both the parsed body and the response headers. */
    public record ApiResponse(JsonNode body, Map<String, List<String>> headers) {}

    /**
     * GETs a WooCommerce API path with query params, applying rate limiting and retries.
     *
     * @param path  e.g. {@code "/orders"} or {@code "/products/42"}
     * @param query query params, e.g. {@code search}, {@code page}, {@code per_page}
     */
    public ApiResponse get(String path, Map<String, String> query) {
        String url = buildUrl(path, query);
        int maxAttempts = properties.getMaxRetries() + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "Interrupted while waiting for rate-limiter token", e);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Authorization", basicAuthHeader)
                    .header("Accept", "application/json")
                    .header("User-Agent", "woocommerce-agent-connector/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Network blip — treat like a retryable 5xx if attempts remain.
                if (attempt < maxAttempts) {
                    sleepQuietly(backoffMillis(attempt, null));
                    continue;
                }
                throw new ApiException(-1, "I/O error calling WooCommerce API: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "Interrupted while calling WooCommerce API", e);
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    JsonNode body = objectMapper.readTree(response.body());
                    return new ApiResponse(body, response.headers().map());
                } catch (IOException e) {
                    throw new ApiException(status, "Could not parse WooCommerce JSON response", e);
                }
            }

            ApiException error = new ApiException(status,
                    "WooCommerce API error " + status + ": " + truncate(response.body(), 300));
            if (error.isRetryable() && attempt < maxAttempts) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                long waitMs = backoffMillis(attempt, retryAfter);
                log.warn("Retryable WooCommerce error (attempt {}/{}), backing off {} ms: {}",
                        attempt, maxAttempts, waitMs, error.getMessage());
                sleepQuietly(waitMs);
                continue;
            }
            throw error;
        }
        throw new IllegalStateException("Unreachable: retry loop always throws or returns");
    }

    /**
     * Fetches every page of a list endpoint and concatenates the items.
     * WooCommerce caps {@code per_page} at 100; this walks {@code X-WP-TotalPages}.
     */
    public List<JsonNode> getAllPages(String path, Map<String, String> query) {
        Map<String, String> params = new LinkedHashMap<>(query);
        params.putIfAbsent("per_page", "100");
        params.put("page", "1");

        List<JsonNode> all = new ArrayList<>();
        ApiResponse first = get(path, params);
        appendItems(first.body(), all);
        int totalPages = totalPages(first.headers());

        for (int page = 2; page <= totalPages; page++) {
            params.put("page", String.valueOf(page));
            appendItems(get(path, params).body(), all);
        }
        return all;
    }

    // ------------------------------------------------------------------ helpers

    private String buildUrl(String path, Map<String, String> query) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        StringBuilder sb = new StringBuilder(base).append(API_PREFIX).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            query.forEach((k, v) -> sb.append(encode(k)).append('=').append(encode(v)).append('&'));
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void appendItems(JsonNode body, List<JsonNode> out) {
        if (body != null && body.isArray()) {
            body.forEach(out::add);
        }
    }

    private static int totalPages(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("X-WP-TotalPages"))
                .flatMap(e -> e.getValue().stream())
                .findFirst()
                .map(v -> {
                    try {
                        return Math.max(1, Integer.parseInt(v.trim()));
                    } catch (NumberFormatException nfe) {
                        return 1;
                    }
                })
                .orElse(1);
    }

    private static long backoffMillis(int attempt, String retryAfterHeader) {
        if (retryAfterHeader != null) {
            try {
                return Math.min(Long.parseLong(retryAfterHeader.trim()) * 1000L, 60_000L);
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        // 500ms, 1s, 2s, ... with a small jitter-free deterministic curve (tests stay stable).
        return Math.min(500L << (attempt - 1), 8000L);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(-1, "Interrupted during retry backoff", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
