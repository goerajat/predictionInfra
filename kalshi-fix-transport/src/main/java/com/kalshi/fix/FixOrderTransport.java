package com.kalshi.fix;

import com.kalshi.client.exception.KalshiApiException;
import com.kalshi.client.exception.OrderException;
import com.kalshi.client.model.AmendOrderRequest;
import com.kalshi.client.model.CreateOrderRequest;
import com.kalshi.client.model.Order;
import com.kalshi.client.transport.OrderTransport;
import com.kalshi.client.transport.TransportType;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FIX-based order transport. Sends orders as NewOrderSingle (D) messages
 * and blocks until the corresponding ExecutionReport is received.
 */
public class FixOrderTransport implements OrderTransport {

    private static final Logger log = LoggerFactory.getLogger(FixOrderTransport.class);

    private final KalshiFixSessionManager sessionManager;
    private final FixOrderStateTracker tracker;
    private final int orderTimeoutSeconds;

    public FixOrderTransport(KalshiFixSessionManager sessionManager,
                              FixOrderStateTracker tracker,
                              int orderTimeoutSeconds) {
        this.sessionManager = sessionManager;
        this.tracker = tracker;
        this.orderTimeoutSeconds = orderTimeoutSeconds;
    }

    @Override
    public Order createOrder(CreateOrderRequest request) {
        String clOrdId = request.getClientOrderId() != null
                ? request.getClientOrderId()
                : FixFieldMapper.generateClOrdId();

        // Register pending order
        PendingOrder pending = tracker.registerPending(clOrdId);
        pending.setFixSide(FixFieldMapper.mapSideToFix(request.getAction(), request.getSide()));
        pending.setSymbol(request.getTicker());

        // Build and send NewOrderSingle
        FixSession session = sessionManager.getSession();
        if (session == null) {
            throw new KalshiApiException("FIX session not available");
        }

        RingBufferOutgoingMessage msg = session.tryClaimMessage("D");
        if (msg == null) {
            throw new KalshiApiException("Failed to claim FIX message buffer for NewOrderSingle");
        }

        try {
            FixFieldMapper.populateNewOrder(msg, request, clOrdId);
            session.commitMessage(msg);
            log.info("NewOrderSingle sent: ClOrdID={}, ticker={}, side={}, qty={}, price={}",
                    clOrdId, request.getTicker(), request.getSide(), request.getCount(),
                    FixFieldMapper.mapPriceToFix(request));
        } catch (Exception e) {
            session.abortMessage(msg);
            throw new KalshiApiException("Failed to send NewOrderSingle: " + e.getMessage(), e);
        }

        // Block until ExecutionReport
        return awaitResponse(pending, "create");
    }

    @Override
    public Order cancelOrder(String orderId) {
        String origClOrdId = tracker.getClOrdIdForOrderId(orderId);
        if (origClOrdId == null) {
            throw new OrderException("Unknown order ID for cancel: " + orderId +
                    ". Order may not have been placed via FIX.");
        }

        PendingOrder origPending = tracker.getPending(origClOrdId);
        String symbol = origPending != null ? origPending.getSymbol() : "";
        char side = origPending != null ? origPending.getFixSide() : '1';

        String cancelClOrdId = FixFieldMapper.generateClOrdId();
        PendingOrder pending = tracker.registerPending(cancelClOrdId);

        FixSession session = sessionManager.getSession();
        if (session == null) {
            throw new KalshiApiException("FIX session not available");
        }

        RingBufferOutgoingMessage msg = session.tryClaimMessage("F");
        if (msg == null) {
            throw new KalshiApiException("Failed to claim FIX message buffer for OrderCancelRequest");
        }

        try {
            FixFieldMapper.populateCancelRequest(msg, cancelClOrdId, origClOrdId, symbol, side);
            session.commitMessage(msg);
            log.info("OrderCancelRequest sent: ClOrdID={}, OrigClOrdID={}, OrderID={}",
                    cancelClOrdId, origClOrdId, orderId);
        } catch (Exception e) {
            session.abortMessage(msg);
            throw new KalshiApiException("Failed to send OrderCancelRequest: " + e.getMessage(), e);
        }

        return awaitResponse(pending, "cancel");
    }

    @Override
    public void cancelOrders(List<String> orderIds) {
        if (orderIds.isEmpty()) return;

        // Use MassCancelRequest (MsgType "q") for session-wide cancel, or loop for specific orders
        for (String orderId : orderIds) {
            try {
                cancelOrder(orderId);
            } catch (Exception e) {
                log.error("Failed to cancel order {}: {}", orderId, e.getMessage());
            }
        }
    }

    @Override
    public Order amendOrder(String orderId, AmendOrderRequest request) {
        String origClOrdId = tracker.getClOrdIdForOrderId(orderId);
        if (origClOrdId == null) {
            throw new OrderException("Unknown order ID for amend: " + orderId +
                    ". Order may not have been placed via FIX.");
        }

        PendingOrder origPending = tracker.getPending(origClOrdId);
        String symbol = origPending != null ? origPending.getSymbol() : "";
        char side = origPending != null ? origPending.getFixSide() : '1';

        // Determine the new price based on which side
        Integer newPrice = null;
        if (request.getYesPrice() != null) {
            newPrice = (side == FixFieldMapper.SIDE_BUY) ? request.getYesPrice() : (100 - request.getYesPrice());
        } else if (request.getNoPrice() != null) {
            newPrice = (side == FixFieldMapper.SIDE_BUY) ? (100 - request.getNoPrice()) : request.getNoPrice();
        }

        String amendClOrdId = FixFieldMapper.generateClOrdId();
        PendingOrder pending = tracker.registerPending(amendClOrdId);

        FixSession session = sessionManager.getSession();
        if (session == null) {
            throw new KalshiApiException("FIX session not available");
        }

        RingBufferOutgoingMessage msg = session.tryClaimMessage("G");
        if (msg == null) {
            throw new KalshiApiException("Failed to claim FIX message buffer for OrderCancelReplaceRequest");
        }

        try {
            FixFieldMapper.populateAmendRequest(msg, amendClOrdId, origClOrdId, symbol, side,
                    newPrice, request.getCount());
            session.commitMessage(msg);
            log.info("OrderCancelReplaceRequest sent: ClOrdID={}, OrigClOrdID={}, OrderID={}",
                    amendClOrdId, origClOrdId, orderId);
        } catch (Exception e) {
            session.abortMessage(msg);
            throw new KalshiApiException("Failed to send OrderCancelReplaceRequest: " + e.getMessage(), e);
        }

        return awaitResponse(pending, "amend");
    }

    @Override
    public boolean isAvailable() {
        return sessionManager.isLoggedOn();
    }

    @Override
    public TransportType getType() {
        return TransportType.FIX;
    }

    private Order awaitResponse(PendingOrder pending, String operation) {
        try {
            return pending.getFuture().get(orderTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new KalshiApiException("FIX " + operation + " timeout after " +
                    orderTimeoutSeconds + "s for ClOrdID: " + pending.getClOrdId());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OrderException) {
                throw (OrderException) cause;
            }
            if (cause instanceof KalshiApiException) {
                throw (KalshiApiException) cause;
            }
            throw new KalshiApiException("FIX " + operation + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KalshiApiException("FIX " + operation + " interrupted");
        }
    }
}
