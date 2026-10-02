package com.aman.connector.model;

import java.util.List;

/** One page of a list result, as returned to the agent. */
public record Page<T>(
        List<T> items,
        int page,
        int totalPages,
        long totalItems) {
}
