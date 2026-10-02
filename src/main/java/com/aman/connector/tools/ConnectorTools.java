package com.aman.connector.tools;

import com.aman.connector.client.WooCommerceClient;
import com.aman.connector.model.Order;
import com.aman.connector.model.Page;
import com.aman.connector.model.Product;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent-facing primitives over the WooCommerce store.
 *
 * <p>Each method mirrors one entry in {@code mcp-tools.json}: small,
 * side-effect-free operations an AI agent can call to answer merchant
 * questions like "which products are running low?" or "show me this
 * week's pending orders". All results are JSON-serializable.
 */
@Component
public class ConnectorTools {

    private final WooCommerceClient client;
    private final ObjectMapper objectMapper;

    public ConnectorTools(WooCommerceClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /** Full-text search over product name/SKU/content. */
    public List<Product> searchProducts(String query, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search", query);
        params.put("per_page", String.valueOf(Math.min(Math.max(limit, 1), 100)));
        return toProducts(client.get("/products", params).body());
    }

    /** Fetch one product by id. */
    public Product getProduct(long id) {
        return toProduct(client.get("/products/" + id, Map.of()).body());
    }

    /**
     * List orders, newest first.
     *
     * @param status  WooCommerce order status (pending, processing, on-hold,
     *                completed, cancelled, refunded, failed) or null for all
     */
    public Page<Order> listOrders(String status, int page, int perPage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(Math.max(page, 1)));
        params.put("per_page", String.valueOf(Math.min(Math.max(perPage, 1), 100)));
        params.put("orderby", "date");
        params.put("order", "desc");
        if (status != null && !status.isBlank()) {
            params.put("status", status.trim());
        }
        WooCommerceClient.ApiResponse response = client.get("/orders", params);
        List<Order> orders = toOrders(response.body());
        return new Page<>(orders, Math.max(page, 1),
                totalPages(response.headers()), totalItems(response.headers()));
    }

    /** Fetch one order by id. */
    public Order getOrder(long id) {
        return toOrder(client.get("/orders/" + id, Map.of()).body());
    }

    /**
     * Products whose stock is at or below {@code threshold}.
     * WooCommerce has no server-side low-stock filter, so this pages through
     * stock-managed products and filters client-side.
     */
    public List<Product> getLowStockProducts(int threshold, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stock_status", "instock");
        params.put("manage_stock", "true");
        List<JsonNode> all = client.getAllPages("/products", params);
        return all.stream()
                .map(this::toProduct)
                .filter(p -> p.stockQuantity() != null && p.stockQuantity() <= threshold)
                .limit(Math.max(limit, 1))
                .collect(Collectors.toList());
    }

    /** The machine-readable tool catalogue for MCP-style agent runtimes. */
    public JsonNode mcpToolSpec() {
        try (var in = getClass().getResourceAsStream("/mcp-tools.json")) {
            if (in == null) {
                throw new IllegalStateException("mcp-tools.json not found on classpath");
            }
            return objectMapper.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load mcp-tools.json", e);
        }
    }

    // ------------------------------------------------------------------ mapping

    private List<Product> toProducts(JsonNode body) {
        return objectMapper.convertValue(body, new TypeReference<List<Product>>() {});
    }

    private Product toProduct(JsonNode body) {
        return objectMapper.convertValue(body, Product.class);
    }

    private List<Order> toOrders(JsonNode body) {
        return objectMapper.convertValue(body, new TypeReference<List<Order>>() {});
    }

    private Order toOrder(JsonNode body) {
        return objectMapper.convertValue(body, Order.class);
    }

    private static int totalPages(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("X-WP-TotalPages"))
                .flatMap(e -> e.getValue().stream())
                .findFirst()
                .map(v -> {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException nfe) {
                        return 1;
                    }
                })
                .orElse(1);
    }

    private static long totalItems(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("X-WP-Total"))
                .flatMap(e -> e.getValue().stream())
                .findFirst()
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim());
                    } catch (NumberFormatException nfe) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }
}
