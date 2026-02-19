package com.kalshi.fix;

import com.kalshi.client.model.AmendOrderRequest;
import com.kalshi.client.model.CreateOrderRequest;
import com.kalshi.client.model.Order;
import com.kalshi.client.transport.OrderTransport;
import com.kalshi.client.transport.TransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fallback transport that tries FIX first, then falls back to REST if FIX is unavailable.
 */
public class FallbackOrderTransport implements OrderTransport {

    private static final Logger log = LoggerFactory.getLogger(FallbackOrderTransport.class);

    private final FixOrderTransport fixTransport;
    private final OrderTransport restTransport;

    public FallbackOrderTransport(FixOrderTransport fixTransport, OrderTransport restTransport) {
        this.fixTransport = fixTransport;
        this.restTransport = restTransport;
    }

    @Override
    public Order createOrder(CreateOrderRequest request) {
        if (fixTransport.isAvailable()) {
            return fixTransport.createOrder(request);
        }
        log.warn("FIX unavailable, falling back to REST for createOrder");
        return restTransport.createOrder(request);
    }

    @Override
    public Order cancelOrder(String orderId) {
        if (fixTransport.isAvailable()) {
            try {
                return fixTransport.cancelOrder(orderId);
            } catch (Exception e) {
                log.warn("FIX cancel failed ({}), falling back to REST", e.getMessage());
                return restTransport.cancelOrder(orderId);
            }
        }
        log.warn("FIX unavailable, falling back to REST for cancelOrder");
        return restTransport.cancelOrder(orderId);
    }

    @Override
    public void cancelOrders(List<String> orderIds) {
        if (fixTransport.isAvailable()) {
            try {
                fixTransport.cancelOrders(orderIds);
                return;
            } catch (Exception e) {
                log.warn("FIX batch cancel failed ({}), falling back to REST", e.getMessage());
            }
        } else {
            log.warn("FIX unavailable, falling back to REST for cancelOrders");
        }
        restTransport.cancelOrders(orderIds);
    }

    @Override
    public Order amendOrder(String orderId, AmendOrderRequest request) {
        if (fixTransport.isAvailable()) {
            try {
                return fixTransport.amendOrder(orderId, request);
            } catch (Exception e) {
                log.warn("FIX amend failed ({}), falling back to REST", e.getMessage());
                return restTransport.amendOrder(orderId, request);
            }
        }
        log.warn("FIX unavailable, falling back to REST for amendOrder");
        return restTransport.amendOrder(orderId, request);
    }

    @Override
    public boolean isAvailable() {
        return fixTransport.isAvailable() || restTransport.isAvailable();
    }

    @Override
    public TransportType getType() {
        return fixTransport.isAvailable() ? TransportType.FIX : TransportType.REST;
    }
}
