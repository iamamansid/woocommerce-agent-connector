# WooCommerce Merchant Connector

A private connector that lets an AI agent (e.g. Razorpay Agent Studio) **read**
a WooCommerce store's orders, products and inventory over the WooCommerce REST
API — with API-key authentication, client-side rate limiting, retry/backoff,
and a machine-readable [MCP tool specification](src/main/resources/mcp-tools.json).

Built for the Razorpay Forward-Deployed Engineer (Agent Studio) assignment —
option 3: *"Build a private connector for a merchant tool"* (WooCommerce).

## What it does

Agent-facing primitives (`ConnectorTools`), each mirroring one entry in
`mcp-tools.json`:

| Tool | Description |
|---|---|
| `search_products(query, limit)` | Full-text search over the product catalogue |
| `get_product(id)` | One product: price, SKU, stock quantity |
| `list_orders(status, page, per_page)` | Orders newest-first, filterable by status |
| `get_order(id)` | One order: line items, totals, billing contact |
| `get_low_stock_products(threshold, limit)` | Products at/below a stock threshold |

## Prerequisites

- Java 21, Maven 3.9+
- A WooCommerce store with REST API keys (**read-only scope is enough** —
  everything here is a GET). Create them at
  *WooCommerce → Settings → Advanced → REST API*.

## Setup & run

```bash
# 1. Configure (env vars so secrets never touch a committed file)
export WC_BASE_URL="https://shop.example.com"   # no trailing slash
export WC_CONSUMER_KEY="ck_..."
export WC_CONSUMER_SECRET="cs_..."

# 2. Build and run the tests (spins up a stub WooCommerce server; no real store needed)
mvn test

# 3. Run the end-to-end demo against your store
mvn -Dconnector.demo.enabled=true spring-boot:run
```

The demo prints the MCP tool catalogue, then exercises `search_products`,
`list_orders` and `get_low_stock_products` against the configured store.

Optional tuning (env or `application.yml`):

| Property | Default | Meaning |
|---|---|---|
| `WC_REQUESTS_PER_SECOND` → `connector.woocommerce.requests-per-second` | `5` | Client-side request budget |
| `connector.woocommerce.max-retries` | `3` | Retries on HTTP 429 / 5xx |
| `connector.woocommerce.request-timeout-seconds` | `15` | Per-request timeout |

## Design notes

- **Auth** — consumer key/secret as HTTP Basic credentials, the documented
  WooCommerce scheme for API-key auth over HTTPS. Keys stay in env vars;
  the client only ever logs status codes, never credentials.
- **Rate limiting** — token-bucket limiter gates every request; 429/5xx
  responses back off exponentially and honour a server-sent `Retry-After`
  when present. Only 429/5xx are retried; 4xx (bad key, bad id) fails fast.
- **Pagination** — list calls follow WooCommerce's `X-WP-TotalPages` header;
  `getAllPages` walks every page (`per_page` capped at 100 by the API).
- **Low stock** — WooCommerce has no server-side low-stock filter, so the
  connector pages stock-managed products and filters client-side.
- **HTTP** — plain `java.net.http.HttpClient`, Jackson for JSON, Java records
  for the slim product/order views.

## Assumptions

- WooCommerce REST API v3 (`/wp-json/wc/v3`) is enabled on the store.
- API keys have at least **read** scope; HTTPS in front of the store
  (Basic auth must never travel over plain HTTP in production).
- Catalogue/order volumes fit a paged pull model (no bulk-export streaming).

## Limitations

- **Read-only.** No order/product create or update — the agent cannot mutate
  store state. (Write tools would need write-scoped keys plus an approval
  gate; deliberately out of scope.)
- Keyword search only (`search` param) — no semantic/product-embedding search.
- Low-stock detection is client-side paging; very large catalogues are slow.
- No support for HPOS custom tables, multisite, or non-standard auth plugins.
- Currency/tax math is taken as reported by the API, not recomputed.

## What the agent can and cannot do

**Can:** answer "do you have blue kurtas in stock?", "show me this week's
pending orders", "what needs restocking?", "what did customer X order?" —
anything answerable by reading products, orders and inventory.

**Cannot:** place or modify orders, change prices/stock, issue refunds,
access customer passwords or payment instruments, or reach data outside the
WooCommerce REST API's read surface.
