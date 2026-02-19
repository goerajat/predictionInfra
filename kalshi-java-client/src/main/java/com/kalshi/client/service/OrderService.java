package com.kalshi.client.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.kalshi.client.KalshiClient;
import com.kalshi.client.exception.KalshiApiException;
import com.kalshi.client.exception.OrderException;
import com.kalshi.client.model.*;
import com.kalshi.client.risk.RiskChecker;
import com.kalshi.client.risk.RiskConfig;
import com.kalshi.client.transport.OrderTransport;
import com.kalshi.client.transport.RestOrderTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing orders on Kalshi.
 * Supports creating, canceling, and amending orders.
 * Supports cursor-based pagination for list endpoints.
 * Includes optional risk checks on order creation and amendment.
 *
 * <p>To enable risk checks:</p>
 * <pre>{@code
 * OrderService orderService = api.orders();
 * orderService.setRiskChecker(new RiskChecker(RiskConfig.builder()
 *     .maxOrderQuantity(100)
 *     .maxOrderNotional(5000)
 *     .build()));
 * }</pre>
 */
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_FILLS_LIMIT = 200;

    private final KalshiClient client;
    private final RestOrderTransport restTransport;
    private RiskChecker riskChecker;
    private OrderTransport orderTransport;

    public OrderService(KalshiClient client) {
        this.client = client;
        this.restTransport = new RestOrderTransport(client);
    }

    /**
     * Set an alternative order transport (e.g., FIX).
     * When set and available, order operations will be routed through this transport.
     * Risk checking still happens in OrderService before delegation.
     *
     * @param transport The order transport to use
     */
    public void setOrderTransport(OrderTransport transport) {
        this.orderTransport = transport;
        log.info("Order transport configured: {}", transport.getType());
    }

    /**
     * Get the current order transport, or null if using default REST.
     */
    public OrderTransport getOrderTransport() {
        return orderTransport;
    }

    /**
     * Set the risk checker for order validation.
     * When set, all order creations and amendments will be validated against risk limits.
     *
     * @param riskChecker The risk checker to use
     */
    public void setRiskChecker(RiskChecker riskChecker) {
        this.riskChecker = riskChecker;
        log.info("Risk checker configured for OrderService");
    }

    /**
     * Get the current risk checker.
     *
     * @return The risk checker or null if not set
     */
    public RiskChecker getRiskChecker() {
        return riskChecker;
    }

    /**
     * Check if risk checks are enabled.
     *
     * @return true if a risk checker is set and enabled
     */
    public boolean isRiskCheckEnabled() {
        return riskChecker != null && riskChecker.isEnabled();
    }

    /**
     * Create a new order.
     * If a risk checker is configured, the order will be validated before submission.
     *
     * @param request Order creation request
     * @return Created Order object
     * @throws com.kalshi.client.risk.RiskCheckException if risk check fails
     */
    public Order createOrder(CreateOrderRequest request) {
        // Perform risk checks if enabled
        if (riskChecker != null) {
            riskChecker.checkOrder(request);
        }

        return getActiveTransport().createOrder(request);
    }

    /**
     * Create a limit order to buy yes contracts.
     *
     * @param ticker Market ticker
     * @param count Number of contracts
     * @param priceInCents Price in cents (1-99)
     * @return Created Order object
     */
    public Order buyYes(String ticker, int count, int priceInCents) {
        return createOrder(CreateOrderRequest.builder()
                .ticker(ticker)
                .side(OrderSide.YES)
                .action(OrderAction.BUY)
                .count(count)
                .yesPrice(priceInCents)
                .build());
    }

    /**
     * Create a limit order to buy no contracts.
     *
     * @param ticker Market ticker
     * @param count Number of contracts
     * @param priceInCents Price in cents (1-99)
     * @return Created Order object
     */
    public Order buyNo(String ticker, int count, int priceInCents) {
        return createOrder(CreateOrderRequest.builder()
                .ticker(ticker)
                .side(OrderSide.NO)
                .action(OrderAction.BUY)
                .count(count)
                .noPrice(priceInCents)
                .build());
    }

    /**
     * Create a limit order to sell yes contracts.
     *
     * @param ticker Market ticker
     * @param count Number of contracts
     * @param priceInCents Price in cents (1-99)
     * @return Created Order object
     */
    public Order sellYes(String ticker, int count, int priceInCents) {
        return createOrder(CreateOrderRequest.builder()
                .ticker(ticker)
                .side(OrderSide.YES)
                .action(OrderAction.SELL)
                .count(count)
                .yesPrice(priceInCents)
                .build());
    }

    /**
     * Create a limit order to sell no contracts.
     *
     * @param ticker Market ticker
     * @param count Number of contracts
     * @param priceInCents Price in cents (1-99)
     * @return Created Order object
     */
    public Order sellNo(String ticker, int count, int priceInCents) {
        return createOrder(CreateOrderRequest.builder()
                .ticker(ticker)
                .side(OrderSide.NO)
                .action(OrderAction.SELL)
                .count(count)
                .noPrice(priceInCents)
                .build());
    }

    /**
     * Cancel an order by ID.
     *
     * @param orderId Order ID to cancel
     * @return Canceled Order object (with remaining_count = 0)
     */
    public Order cancelOrder(String orderId) {
        return getActiveTransport().cancelOrder(orderId);
    }

    /**
     * Cancel multiple orders at once (up to 20).
     *
     * @param orderIds List of order IDs to cancel
     */
    public void cancelOrders(List<String> orderIds) {
        getActiveTransport().cancelOrders(orderIds);
    }

    /**
     * Cancel multiple orders at once.
     *
     * @param orderIds Order IDs to cancel
     */
    public void cancelOrders(String... orderIds) {
        cancelOrders(List.of(orderIds));
    }

    /**
     * Amend an existing order (change price and/or quantity).
     * If a risk checker is configured, the amendment will be validated before submission.
     *
     * @param orderId Order ID to amend
     * @param request Amendment request
     * @return Amended Order object
     * @throws com.kalshi.client.risk.RiskCheckException if risk check fails
     */
    public Order amendOrder(String orderId, AmendOrderRequest request) {
        // Perform risk checks if enabled
        if (riskChecker != null) {
            Order currentOrder = getOrder(orderId);
            riskChecker.checkAmendment(orderId, currentOrder, request);
        }

        return getActiveTransport().amendOrder(orderId, request);
    }

    /**
     * Amend the price of an existing order.
     *
     * @param orderId Order ID to amend
     * @param newPriceInCents New price in cents
     * @param isYesSide True for yes price, false for no price
     * @return Amended Order object
     */
    public Order amendOrderPrice(String orderId, int newPriceInCents, boolean isYesSide) {
        AmendOrderRequest.Builder builder = AmendOrderRequest.builder();
        if (isYesSide) {
            builder.yesPrice(newPriceInCents);
        } else {
            builder.noPrice(newPriceInCents);
        }
        return amendOrder(orderId, builder.build());
    }

    /**
     * Amend the quantity of an existing order.
     *
     * @param orderId Order ID to amend
     * @param newCount New total count (remaining + filled)
     * @return Amended Order object
     */
    public Order amendOrderQuantity(String orderId, int newCount) {
        return amendOrder(orderId, AmendOrderRequest.builder().count(newCount).build());
    }

    /**
     * Get a specific order by ID.
     *
     * @param orderId Order ID
     * @return Order object
     */
    public Order getOrder(String orderId) {
        return client.get("/portfolio/orders/" + orderId, "order", Order.class);
    }

    /**
     * Get all open orders (first page).
     *
     * @return List of open Order objects
     */
    public List<Order> getOpenOrders() {
        return getOrders(OrderQuery.builder().status("resting").build());
    }

    /**
     * Get all open orders (auto-pagination).
     *
     * @return List of all open Order objects
     */
    public List<Order> getAllOpenOrders() {
        return getAllOrders(OrderQuery.builder().status("resting").build());
    }

    /**
     * Get orders with custom query parameters (first page).
     *
     * @param query Order query parameters
     * @return List of Order objects
     */
    public List<Order> getOrders(OrderQuery query) {
        return getOrdersPaginated(query).getData();
    }

    /**
     * Get orders with pagination info.
     *
     * @param query Order query parameters
     * @return PaginatedResponse containing orders and cursor
     */
    public PaginatedResponse<Order> getOrdersPaginated(OrderQuery query) {
        String path = "/portfolio/orders" + query.toQueryString();
        String response = client.get(path, String.class);
        return parseOrdersPaginated(response);
    }

    /**
     * Get all orders matching the query by automatically paginating.
     *
     * @param baseQuery Base query parameters (cursor will be overwritten)
     * @return List of all Order objects
     */
    public List<Order> getAllOrders(OrderQuery baseQuery) {
        List<Order> allOrders = new ArrayList<>();
        String cursor = null;
        int pageNumber = 0;

        log.info("Fetching all orders (auto-pagination){}...",
                baseQuery.status != null ? " with status " + baseQuery.status : "");

        do {
            pageNumber++;
            OrderQuery query = OrderQuery.builder()
                    .limit(baseQuery.limit != null ? baseQuery.limit : DEFAULT_LIMIT)
                    .cursor(cursor)
                    .ticker(baseQuery.ticker)
                    .eventTicker(baseQuery.eventTicker)
                    .status(baseQuery.status)
                    .build();

            PaginatedResponse<Order> page = getOrdersPaginated(query);
            allOrders.addAll(page.getData());
            cursor = page.getCursor();

            log.info("Orders page {} loaded: {} items, total so far: {}, hasMore: {}",
                    pageNumber, page.size(), allOrders.size(), page.hasMore());
        } while (cursor != null && !cursor.isEmpty());

        log.info("Finished loading all orders: {} total", allOrders.size());
        return allOrders;
    }

    /**
     * Get orders for a specific market (first page).
     *
     * @param ticker Market ticker
     * @return List of Order objects
     */
    public List<Order> getOrdersByMarket(String ticker) {
        return getOrders(OrderQuery.builder().ticker(ticker).build());
    }

    /**
     * Get all orders for a specific market (auto-pagination).
     *
     * @param ticker Market ticker
     * @return List of all Order objects for the market
     */
    public List<Order> getAllOrdersByMarket(String ticker) {
        return getAllOrders(OrderQuery.builder().ticker(ticker).build());
    }

    /**
     * Get user's fills (executed trades) - first page.
     *
     * @return List of Trade (fill) objects
     */
    public List<Trade> getFills() {
        return getFills(new FillQuery());
    }

    /**
     * Get user's fills with custom query parameters (first page).
     *
     * @param query Fill query parameters
     * @return List of Trade (fill) objects
     */
    public List<Trade> getFills(FillQuery query) {
        return getFillsPaginated(query).getData();
    }

    /**
     * Get fills with pagination info.
     *
     * @param query Fill query parameters
     * @return PaginatedResponse containing fills and cursor
     */
    public PaginatedResponse<Trade> getFillsPaginated(FillQuery query) {
        String path = "/portfolio/fills" + query.toQueryString();
        String response = client.get(path, String.class);
        return parseFillsPaginated(response);
    }

    /**
     * Get all fills by automatically paginating through all pages.
     *
     * @return List of all Trade (fill) objects
     */
    public List<Trade> getAllFills() {
        return getAllFills(new FillQuery());
    }

    /**
     * Get all fills matching the query by automatically paginating.
     *
     * @param baseQuery Base query parameters (cursor will be overwritten)
     * @return List of all Trade (fill) objects
     */
    public List<Trade> getAllFills(FillQuery baseQuery) {
        List<Trade> allFills = new ArrayList<>();
        String cursor = null;
        int pageNumber = 0;

        log.info("Fetching all fills (auto-pagination)...");

        do {
            pageNumber++;
            FillQuery query = FillQuery.builder()
                    .limit(baseQuery.limit != null ? baseQuery.limit : DEFAULT_LIMIT)
                    .cursor(cursor)
                    .ticker(baseQuery.ticker)
                    .orderId(baseQuery.orderId)
                    .minTs(baseQuery.minTs)
                    .maxTs(baseQuery.maxTs)
                    .build();

            PaginatedResponse<Trade> page = getFillsPaginated(query);
            allFills.addAll(page.getData());
            cursor = page.getCursor();

            log.info("Fills page {} loaded: {} items, total so far: {}, hasMore: {}",
                    pageNumber, page.size(), allFills.size(), page.hasMore());
        } while (cursor != null && !cursor.isEmpty());

        log.info("Finished loading all fills: {} total", allFills.size());
        return allFills;
    }

    /**
     * Get fills for a specific market (first page).
     *
     * @param ticker Market ticker
     * @return List of Trade (fill) objects
     */
    public List<Trade> getFillsByMarket(String ticker) {
        return getFills(FillQuery.builder().ticker(ticker).build());
    }

    /**
     * Get all fills for a specific market (auto-pagination).
     *
     * @param ticker Market ticker
     * @return List of all Trade (fill) objects for the market
     */
    public List<Trade> getAllFillsByMarket(String ticker) {
        return getAllFills(FillQuery.builder().ticker(ticker).build());
    }

    /**
     * Get the active transport. Uses the configured transport if available, otherwise REST.
     */
    private OrderTransport getActiveTransport() {
        if (orderTransport != null && orderTransport.isAvailable()) {
            return orderTransport;
        }
        return restTransport;
    }

    private PaginatedResponse<Order> parseOrdersPaginated(String json) {
        try {
            JsonNode root = client.getObjectMapper().readTree(json);

            // Extract cursor
            String cursor = null;
            JsonNode cursorNode = root.get("cursor");
            if (cursorNode != null && !cursorNode.isNull()) {
                cursor = cursorNode.asText();
                if (cursor.isEmpty()) {
                    cursor = null;
                }
            }

            // Extract orders
            JsonNode ordersNode = root.get("orders");
            List<Order> orders;
            if (ordersNode == null || !ordersNode.isArray()) {
                orders = new ArrayList<>();
            } else {
                orders = client.getObjectMapper().convertValue(ordersNode,
                        new TypeReference<List<Order>>() {});
            }

            return new PaginatedResponse<>(orders, cursor);
        } catch (Exception e) {
            throw new KalshiApiException("Failed to parse orders response", e);
        }
    }

    private PaginatedResponse<Trade> parseFillsPaginated(String json) {
        try {
            JsonNode root = client.getObjectMapper().readTree(json);

            // Extract cursor
            String cursor = null;
            JsonNode cursorNode = root.get("cursor");
            if (cursorNode != null && !cursorNode.isNull()) {
                cursor = cursorNode.asText();
                if (cursor.isEmpty()) {
                    cursor = null;
                }
            }

            // Extract fills
            JsonNode fillsNode = root.get("fills");
            List<Trade> fills;
            if (fillsNode == null || !fillsNode.isArray()) {
                fills = new ArrayList<>();
            } else {
                fills = client.getObjectMapper().convertValue(fillsNode,
                        new TypeReference<List<Trade>>() {});
            }

            return new PaginatedResponse<>(fills, cursor);
        } catch (Exception e) {
            throw new KalshiApiException("Failed to parse fills response", e);
        }
    }

    /**
     * Query builder for orders endpoint.
     */
    public static class OrderQuery {
        private Integer limit;
        private String cursor;
        private String ticker;
        private String eventTicker;
        private String status;

        public static Builder builder() {
            return new Builder();
        }

        public String toQueryString() {
            List<String> params = new ArrayList<>();

            if (limit != null) params.add("limit=" + limit);
            if (cursor != null) params.add("cursor=" + cursor);
            if (ticker != null) params.add("ticker=" + ticker);
            if (eventTicker != null) params.add("event_ticker=" + eventTicker);
            if (status != null) params.add("status=" + status);

            return params.isEmpty() ? "" : "?" + String.join("&", params);
        }

        public static class Builder {
            private final OrderQuery query = new OrderQuery();

            public Builder limit(int limit) {
                query.limit = limit;
                return this;
            }

            public Builder cursor(String cursor) {
                query.cursor = cursor;
                return this;
            }

            public Builder ticker(String ticker) {
                query.ticker = ticker;
                return this;
            }

            public Builder eventTicker(String eventTicker) {
                query.eventTicker = eventTicker;
                return this;
            }

            public Builder status(String status) {
                query.status = status;
                return this;
            }

            public OrderQuery build() {
                return query;
            }
        }
    }

    /**
     * Query builder for fills endpoint.
     */
    public static class FillQuery {
        private Integer limit;
        private String cursor;
        private String ticker;
        private String orderId;
        private Long minTs;
        private Long maxTs;

        public static Builder builder() {
            return new Builder();
        }

        public String toQueryString() {
            List<String> params = new ArrayList<>();

            if (limit != null) params.add("limit=" + limit);
            if (cursor != null) params.add("cursor=" + cursor);
            if (ticker != null) params.add("ticker=" + ticker);
            if (orderId != null) params.add("order_id=" + orderId);
            if (minTs != null) params.add("min_ts=" + minTs);
            if (maxTs != null) params.add("max_ts=" + maxTs);

            return params.isEmpty() ? "" : "?" + String.join("&", params);
        }

        public static class Builder {
            private final FillQuery query = new FillQuery();

            public Builder limit(int limit) {
                query.limit = Math.min(limit, MAX_FILLS_LIMIT);
                return this;
            }

            public Builder cursor(String cursor) {
                query.cursor = cursor;
                return this;
            }

            public Builder ticker(String ticker) {
                query.ticker = ticker;
                return this;
            }

            public Builder orderId(String orderId) {
                query.orderId = orderId;
                return this;
            }

            public Builder minTs(Long minTs) {
                query.minTs = minTs;
                return this;
            }

            public Builder maxTs(Long maxTs) {
                query.maxTs = maxTs;
                return this;
            }

            public FillQuery build() {
                return query;
            }
        }
    }
}
