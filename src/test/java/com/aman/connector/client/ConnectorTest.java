package com.aman.connector.client;

import com.aman.connector.config.ConnectorProperties;
import com.aman.connector.model.Order;
import com.aman.connector.model.Page;
import com.aman.connector.model.Product;
import com.aman.connector.ratelimit.RateLimiter;
import com.aman.connector.tools.ConnectorTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the connector. The HTTP transport is faked (a scripted
 * {@link HttpClient} subclass) instead of a stub socket server: this sandbox
 * intercepts Java TCP connections, so no real socket test can run here, and a
 * fake transport is deterministic everywhere else too.
 *
 * <p>Covered: API-key auth header, query forwarding, pagination via
 * {@code X-WP-TotalPages}, retry on 429/5xx (and not on 401), giving up after
 * {@code maxRetries}, and the agent-tool mappings.
 */
class ConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ client

    @Test
    void sendsBasicAuthWithConsumerKeyAndSecret() {
        TestClient tc = testClient(0);
        tc.fake.enqueue(200, "[]");

        tc.client.get("/products", Map.of());

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("ck_test:cs_test".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, tc.fake.requests.get(0).headers()
                .firstValue("Authorization").orElseThrow());
    }

    @Test
    void forwardsSearchQueryAndParsesProducts() {
        TestClient tc = testClient(0);
        tc.fake.enqueue(200, """
                [{"id":7,"name":"Blue Kurta","sku":"KUR-007","price":"1299.00",
                  "stock_quantity":4,"stock_status":"instock",
                  "permalink":"https://shop.example.com/blue-kurta"}]""");

        List<Product> products = new ConnectorTools(tc.client, MAPPER).searchProducts("kurta", 10);

        String query = tc.fake.requests.get(0).uri().getQuery();
        assertTrue(query.contains("search=kurta"), "search param forwarded, got: " + query);
        assertEquals(1, products.size());
        Product p = products.get(0);
        assertEquals(7, p.id());
        assertEquals("Blue Kurta", p.name());
        assertEquals(4, p.stockQuantity());
    }

    @Test
    void walksAllPagesUsingTotalPagesHeader() {
        TestClient tc = testClient(0);
        tc.fake.enqueue(200, """
                [{"id":1,"name":"P1","sku":"S1","price":"10","stock_quantity":9,
                  "stock_status":"instock","permalink":"x"}]""",
                Map.of("X-WP-TotalPages", List.of("2"), "X-WP-Total", List.of("2")));
        tc.fake.enqueue(200, """
                [{"id":2,"name":"P2","sku":"S2","price":"10","stock_quantity":9,
                  "stock_status":"instock","permalink":"x"}]""");

        List<JsonNode> all = tc.client.getAllPages("/products", Map.of());

        assertEquals(2, all.size());
        assertEquals(1, all.get(0).get("id").asLong());
        assertEquals(2, all.get(1).get("id").asLong());
        assertTrue(tc.fake.requests.get(0).uri().getQuery().contains("page=1"));
        assertTrue(tc.fake.requests.get(1).uri().getQuery().contains("page=2"));
    }

    @Test
    void retriesOnceOn429ThenSucceeds() {
        TestClient tc = testClient(2);
        tc.fake.enqueue(429, "{\"code\":\"rest_too_many_requests\"}");
        tc.fake.enqueue(200, "[]");

        assertDoesNotThrow(() -> tc.client.get("/orders", Map.of()));
        assertEquals(2, tc.fake.requests.size(), "retried after 429");
    }

    @Test
    void retriesOn500ThenSucceeds() {
        TestClient tc = testClient(2);
        tc.fake.enqueue(500, "boom");
        tc.fake.enqueue(200, "[]");

        assertDoesNotThrow(() -> tc.client.get("/orders", Map.of()));
        assertEquals(2, tc.fake.requests.size());
    }

    @Test
    void doesNotRetryOn401() {
        TestClient tc = testClient(2);
        tc.fake.enqueue(401, "{\"code\":\"rest_forbidden\"}");

        ApiException ex = assertThrows(ApiException.class, () -> tc.client.get("/orders", Map.of()));
        assertEquals(401, ex.getStatusCode());
        assertEquals(1, tc.fake.requests.size(), "401 must not be retried");
    }

    @Test
    void givesUpAfterMaxRetries() {
        TestClient tc = testClient(1);
        tc.fake.enqueue(503, "down");
        tc.fake.enqueue(503, "down");

        assertThrows(ApiException.class, () -> tc.client.get("/orders", Map.of()));
        assertEquals(2, tc.fake.requests.size(), "1 initial attempt + 1 retry");
    }

    // ------------------------------------------------------------------ tools

    @Test
    void listOrdersMapsPaginationHeadersIntoPage() {
        TestClient tc = testClient(0);
        tc.fake.enqueue(200, """
                [{"id":101,"number":"101","status":"pending","total":"2598.00",
                  "currency":"INR","date_created":"2026-10-01T10:00:00",
                  "billing":{"email":"buyer@example.com","first_name":"Asha","last_name":"K"},
                  "line_items":[{"id":1,"name":"Blue Kurta","quantity":2,"total":"2598.00"}]}]""",
                Map.of("X-WP-TotalPages", List.of("5"), "X-WP-Total", List.of("42")));

        Page<Order> page = new ConnectorTools(tc.client, MAPPER).listOrders("pending", 1, 20);

        assertEquals(5, page.totalPages());
        assertEquals(42, page.totalItems());
        assertEquals(1, page.items().size());
        Order order = page.items().get(0);
        assertEquals(101, order.id());
        assertEquals("pending", order.status());
        assertEquals("buyer@example.com", order.billing().email());
        assertEquals(1, order.lineItems().size());
    }

    @Test
    void lowStockFiltersClientSideByThreshold() {
        TestClient tc = testClient(0);
        tc.fake.enqueue(200, """
                [{"id":1,"name":"Plenty","sku":"A","price":"10","stock_quantity":50,"stock_status":"instock","permalink":"x"},
                 {"id":2,"name":"Almost gone","sku":"B","price":"10","stock_quantity":3,"stock_status":"instock","permalink":"x"},
                 {"id":3,"name":"Zero","sku":"C","price":"10","stock_quantity":0,"stock_status":"outofstock","permalink":"x"}]""",
                Map.of("X-WP-TotalPages", List.of("1")));

        List<Product> low = new ConnectorTools(tc.client, MAPPER).getLowStockProducts(5, 10);

        assertEquals(List.of(2L, 3L), low.stream().map(Product::id).toList());
    }

    @Test
    void mcpToolSpecLoadsFromClasspath() {
        TestClient tc = testClient(0);

        JsonNode spec = new ConnectorTools(tc.client, MAPPER).mcpToolSpec();

        assertEquals("woocommerce-merchant-connector", spec.get("name").asText());
        assertEquals(5, spec.get("tools").size());
    }

    // ------------------------------------------------------------------ harness

    private record TestClient(WooCommerceClient client, FakeHttpClient fake) {}

    private TestClient testClient(int maxRetries) {
        ConnectorProperties props = new ConnectorProperties();
        props.setBaseUrl("https://shop.example.com");
        props.setConsumerKey("ck_test");
        props.setConsumerSecret("cs_test");
        props.setMaxRetries(maxRetries);
        props.setRequestTimeoutSeconds(10);
        FakeHttpClient fake = new FakeHttpClient();
        WooCommerceClient client = new WooCommerceClient(props, MAPPER, fake, new RateLimiter(1000));
        return new TestClient(client, fake);
    }

    /** Scripted transport: returns canned responses in order, records requests. */
    static class FakeHttpClient extends HttpClient {
        record Scripted(int status, String body, Map<String, List<String>> headers) {}

        final Queue<Scripted> script = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        void enqueue(int status, String body) {
            script.add(new Scripted(status, body, Map.of()));
        }

        void enqueue(int status, String body, Map<String, List<String>> headers) {
            script.add(new Scripted(status, body, headers));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                       HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            Scripted s = script.poll();
            if (s == null) {
                throw new AssertionError("no scripted response for " + request.uri());
            }
            HttpHeaders headers = HttpHeaders.of(s.headers(), (k, v) -> true);
            return (HttpResponse<T>) new FakeHttpResponse(s.status(), s.body(), headers, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sync only in tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("sync only in tests");
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    /** Minimal {@link HttpResponse} carrying a canned status/body/headers. */
    static class FakeHttpResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private final HttpHeaders headers;
        private final HttpRequest request;

        FakeHttpResponse(int status, String body, HttpHeaders headers, HttpRequest request) {
            this.status = status;
            this.body = body;
            this.headers = headers;
            this.request = request;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return headers; }
        @Override public String body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
