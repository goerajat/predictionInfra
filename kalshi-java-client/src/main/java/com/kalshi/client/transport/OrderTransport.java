package com.kalshi.client.transport;

import com.kalshi.client.model.AmendOrderRequest;
import com.kalshi.client.model.CreateOrderRequest;
import com.kalshi.client.model.Order;

import java.util.List;

/**
 * Abstraction for order transport layer.
 * Implementations route order operations over different protocols (REST, FIX).
 */
public interface OrderTransport {

    /**
     * Create a new order.
     *
     * @param request Order creation request
     * @return Created Order object
     */
    Order createOrder(CreateOrderRequest request);

    /**
     * Cancel an order by ID.
     *
     * @param orderId Order ID to cancel
     * @return Canceled Order object
     */
    Order cancelOrder(String orderId);

    /**
     * Cancel multiple orders at once.
     *
     * @param orderIds List of order IDs to cancel
     */
    void cancelOrders(List<String> orderIds);

    /**
     * Amend an existing order.
     *
     * @param orderId Order ID to amend
     * @param request Amendment request
     * @return Amended Order object
     */
    Order amendOrder(String orderId, AmendOrderRequest request);

    /**
     * Check if this transport is currently available.
     *
     * @return true if the transport can accept orders
     */
    boolean isAvailable();

    /**
     * Get the transport type.
     *
     * @return TransportType
     */
    TransportType getType();
}
