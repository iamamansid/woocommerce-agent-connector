package com.aman.connector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Slim view of a WooCommerce product — only what an agent needs to reason about inventory. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
        long id,
        String name,
        String sku,
        String price,
        @JsonProperty("stock_quantity") Integer stockQuantity,
        @JsonProperty("stock_status") String stockStatus,
        String permalink) {
}
