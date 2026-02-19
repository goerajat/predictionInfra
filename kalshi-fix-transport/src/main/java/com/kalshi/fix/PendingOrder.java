package com.kalshi.fix;

import com.kalshi.client.model.Order;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Tracks an in-flight order request waiting for an ExecutionReport response.
 */
public class PendingOrder {

    private final String clOrdId;
    private final CompletableFuture<Order> future;
    private final Instant createdAt;
    private volatile String orderId;
    private volatile char fixSide;
    private volatile String symbol;

    public PendingOrder(String clOrdId) {
        this.clOrdId = clOrdId;
        this.future = new CompletableFuture<>();
        this.createdAt = Instant.now();
    }

    public String getClOrdId() {
        return clOrdId;
    }

    public CompletableFuture<Order> getFuture() {
        return future;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public char getFixSide() {
        return fixSide;
    }

    public void setFixSide(char fixSide) {
        this.fixSide = fixSide;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public boolean isExpired(int timeoutSeconds) {
        return Instant.now().isAfter(createdAt.plusSeconds(timeoutSeconds));
    }
}
