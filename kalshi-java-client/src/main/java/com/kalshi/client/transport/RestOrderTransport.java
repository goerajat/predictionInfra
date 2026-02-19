package com.kalshi.client.transport;

import com.kalshi.client.KalshiClient;
import com.kalshi.client.exception.OrderException;
import com.kalshi.client.model.*;

import java.util.List;

/**
 * REST-based order transport using the Kalshi HTTP API.
 * Wraps existing KalshiClient HTTP calls.
 */
public class RestOrderTransport implements OrderTransport {

    private final KalshiClient client;

    public RestOrderTransport(KalshiClient client) {
        this.client = client;
    }

    @Override
    public Order createOrder(CreateOrderRequest request) {
        return client.post("/portfolio/orders", request, "order", Order.class);
    }

    @Override
    public Order cancelOrder(String orderId) {
        return client.delete("/portfolio/orders/" + orderId, "order", Order.class);
    }

    @Override
    public void cancelOrders(List<String> orderIds) {
        if (orderIds.size() > 20) {
            throw new OrderException("Cannot cancel more than 20 orders at once");
        }
        BatchCancelRequest request = new BatchCancelRequest(orderIds);
        client.delete("/portfolio/orders/batched", request, String.class);
    }

    @Override
    public Order amendOrder(String orderId, AmendOrderRequest request) {
        return client.post("/portfolio/orders/" + orderId + "/amend", request, "order", Order.class);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public TransportType getType() {
        return TransportType.REST;
    }
}
