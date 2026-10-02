package com.aman.connector.demo;

import com.aman.connector.tools.ConnectorTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runnable demo: exercises every agent tool once and prints the results.
 *
 * <p>Enable with {@code connector.demo.enabled=true} and point the connector at
 * a store (or the local stub used in tests). Useful as the "working end-to-end
 * demonstration" of the connector.
 */
@Component
@ConditionalOnProperty(prefix = "connector.demo", name = "enabled", havingValue = "true")
public class ConnectorDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectorDemo.class);

    private final ConnectorTools tools;
    private final ObjectMapper objectMapper;

    public ConnectorDemo(ConnectorTools tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Connector demo: MCP tool spec ===");
        log.info("\n{}", objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(tools.mcpToolSpec().get("tools")));

        log.info("=== search_products('shirt') ===");
        log.info("\n{}", pretty(tools.searchProducts("shirt", 3)));

        log.info("=== list_orders(status=pending) ===");
        log.info("\n{}", pretty(tools.listOrders("pending", 1, 3)));

        log.info("=== get_low_stock_products(threshold=5) ===");
        log.info("\n{}", pretty(tools.getLowStockProducts(5, 5)));

        log.info("=== demo complete ===");
    }

    private String pretty(Object value) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
