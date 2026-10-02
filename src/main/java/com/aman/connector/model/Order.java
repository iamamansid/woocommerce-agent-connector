package com.aman.connector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Slim view of a WooCommerce order. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
        long id,
        String number,
        String status,
        String total,
        String currency,
        @JsonProperty("date_created") String dateCreated,
        Billing billing,
        @JsonProperty("line_items") List<LineItem> lineItems) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Billing(String email, @JsonProperty("first_name") String firstName,
                          @JsonProperty("last_name") String lastName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(long id, String name, int quantity, String total) {
    }
}
