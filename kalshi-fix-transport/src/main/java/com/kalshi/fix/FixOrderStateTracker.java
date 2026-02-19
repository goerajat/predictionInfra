package com.kalshi.fix;

import com.kalshi.client.exception.OrderException;
import com.kalshi.client.model.Order;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.IncomingFixMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks in-flight FIX order requests and processes ExecutionReport responses.
 * Routes responses to PendingOrder CompletableFutures.
 */
public class FixOrderStateTracker implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(FixOrderStateTracker.class);

    // Pending orders keyed by ClOrdID
    private final ConcurrentHashMap<String, PendingOrder> pendingByClOrdId = new ConcurrentHashMap<>();

    // ClOrdID -> OrderID mapping for cancel/amend correlation
    private final ConcurrentHashMap<String, String> clOrdIdToOrderId = new ConcurrentHashMap<>();

    // OrderID -> ClOrdID reverse mapping
    private final ConcurrentHashMap<String, String> orderIdToClOrdId = new ConcurrentHashMap<>();

    // Callback for order state updates on already-acknowledged orders
    private volatile Consumer<Order> orderUpdateCallback;

    private final int orderTimeoutSeconds;

    public FixOrderStateTracker(int orderTimeoutSeconds) {
        this.orderTimeoutSeconds = orderTimeoutSeconds;
    }

    /**
     * Set callback for order updates on already-acknowledged orders.
     * Used by OrderManager integration (Phase 6).
     */
    public void setOrderUpdateCallback(Consumer<Order> callback) {
        this.orderUpdateCallback = callback;
    }

    /**
     * Register a pending order request.
     */
    public PendingOrder registerPending(String clOrdId) {
        PendingOrder pending = new PendingOrder(clOrdId);
        pendingByClOrdId.put(clOrdId, pending);
        return pending;
    }

    /**
     * Get ClOrdID for an OrderID (for cancel/amend).
     */
    public String getClOrdIdForOrderId(String orderId) {
        return orderIdToClOrdId.get(orderId);
    }

    /**
     * Get the PendingOrder for a ClOrdID (for side/symbol info on cancel/amend).
     */
    public PendingOrder getPending(String clOrdId) {
        return pendingByClOrdId.get(clOrdId);
    }

    // ==================== MessageListener ====================

    @Override
    public void onMessage(FixSession session, IncomingFixMessage msg) {
        String msgType = msg.getMsgType().toString();

        if ("8".equals(msgType)) {
            handleExecutionReport(msg);
        } else if ("9".equals(msgType)) {
            handleOrderCancelReject(msg);
        }
    }

    @Override
    public void onReject(FixSession session, int refSeqNum, String refMsgType,
                          int rejectReason, String text) {
        log.warn("FIX session-level reject: refSeqNum={}, refMsgType={}, reason={}, text={}",
                refSeqNum, refMsgType, rejectReason, text);
    }

    @Override
    public void onBusinessReject(FixSession session, int refSeqNum,
                                  int businessRejectReason, String text) {
        log.warn("FIX business reject: refSeqNum={}, reason={}, text={}",
                refSeqNum, businessRejectReason, text);
    }

    private void handleExecutionReport(IncomingFixMessage msg) {
        char execType = FixFieldMapper.getExecType(msg);
        String clOrdId = msg.hasField(FixFieldMapper.TAG_CL_ORD_ID)
                ? msg.getCharSequence(FixFieldMapper.TAG_CL_ORD_ID).toString() : null;
        String orderId = msg.hasField(FixFieldMapper.TAG_ORDER_ID)
                ? msg.getCharSequence(FixFieldMapper.TAG_ORDER_ID).toString() : null;

        log.debug("ExecutionReport: ExecType={}, ClOrdID={}, OrderID={}", execType, clOrdId, orderId);

        // Store ClOrdID <-> OrderID mapping
        if (clOrdId != null && orderId != null) {
            clOrdIdToOrderId.put(clOrdId, orderId);
            orderIdToClOrdId.put(orderId, clOrdId);
        }

        // Parse the full Order model from the ER
        Order order = FixFieldMapper.parseExecutionReport(msg);

        // Find the pending request
        PendingOrder pending = clOrdId != null ? pendingByClOrdId.get(clOrdId) : null;

        // Also check OrigClOrdID for cancel/replace responses
        if (pending == null && msg.hasField(FixFieldMapper.TAG_ORIG_CL_ORD_ID)) {
            String origClOrdId = msg.getCharSequence(FixFieldMapper.TAG_ORIG_CL_ORD_ID).toString();
            pending = pendingByClOrdId.get(origClOrdId);
        }

        switch (execType) {
            case FixFieldMapper.EXEC_TYPE_NEW:
            case FixFieldMapper.EXEC_TYPE_PENDING_NEW:
                // Order acknowledged
                if (pending != null && !pending.getFuture().isDone()) {
                    if (orderId != null) pending.setOrderId(orderId);
                    pending.getFuture().complete(order);
                    log.info("Order acknowledged: ClOrdID={}, OrderID={}", clOrdId, orderId);
                }
                break;

            case FixFieldMapper.EXEC_TYPE_REJECTED:
                // Order rejected
                String reason = FixFieldMapper.getRejectionReason(msg);
                if (pending != null && !pending.getFuture().isDone()) {
                    pending.getFuture().completeExceptionally(
                            new OrderException("Order rejected: " + reason));
                    pendingByClOrdId.remove(clOrdId);
                    log.warn("Order rejected: ClOrdID={}, reason={}", clOrdId, reason);
                }
                break;

            case FixFieldMapper.EXEC_TYPE_TRADE:
            case FixFieldMapper.EXEC_TYPE_FILL:
            case FixFieldMapper.EXEC_TYPE_PARTIAL_FILL:
                // Trade/fill
                if (pending != null && !pending.getFuture().isDone()) {
                    pending.getFuture().complete(order);
                } else {
                    // Already acknowledged — fire update callback
                    fireOrderUpdate(order);
                }
                log.info("Fill: ClOrdID={}, OrderID={}, cumQty={}",
                        clOrdId, orderId, order.getFillCount());
                break;

            case FixFieldMapper.EXEC_TYPE_CANCELED:
                // Cancel confirmed
                if (pending != null && !pending.getFuture().isDone()) {
                    pending.getFuture().complete(order);
                    pendingByClOrdId.remove(clOrdId);
                } else {
                    fireOrderUpdate(order);
                }
                log.info("Order canceled: ClOrdID={}, OrderID={}", clOrdId, orderId);
                break;

            case FixFieldMapper.EXEC_TYPE_REPLACED:
                // Amend confirmed
                if (pending != null && !pending.getFuture().isDone()) {
                    pending.getFuture().complete(order);
                    pendingByClOrdId.remove(clOrdId);
                } else {
                    fireOrderUpdate(order);
                }
                log.info("Order amended: ClOrdID={}, OrderID={}", clOrdId, orderId);
                break;

            case FixFieldMapper.EXEC_TYPE_EXPIRED:
                if (pending != null && !pending.getFuture().isDone()) {
                    pending.getFuture().complete(order);
                    pendingByClOrdId.remove(clOrdId);
                } else {
                    fireOrderUpdate(order);
                }
                log.info("Order expired: ClOrdID={}, OrderID={}", clOrdId, orderId);
                break;

            default:
                log.debug("Unhandled ExecType: {} for ClOrdID={}", execType, clOrdId);
                break;
        }
    }

    private void handleOrderCancelReject(IncomingFixMessage msg) {
        String clOrdId = msg.hasField(FixFieldMapper.TAG_CL_ORD_ID)
                ? msg.getCharSequence(FixFieldMapper.TAG_CL_ORD_ID).toString() : null;
        String text = msg.hasField(FixFieldMapper.TAG_TEXT)
                ? msg.getCharSequence(FixFieldMapper.TAG_TEXT).toString() : "Cancel rejected";

        log.warn("OrderCancelReject: ClOrdID={}, text={}", clOrdId, text);

        PendingOrder pending = clOrdId != null ? pendingByClOrdId.remove(clOrdId) : null;
        if (pending != null && !pending.getFuture().isDone()) {
            pending.getFuture().completeExceptionally(
                    new OrderException("Cancel/amend rejected: " + text));
        }
    }

    private void fireOrderUpdate(Order order) {
        Consumer<Order> callback = orderUpdateCallback;
        if (callback != null) {
            try {
                callback.accept(order);
            } catch (Exception e) {
                log.error("Error in order update callback: {}", e.getMessage());
            }
        }
    }

    /**
     * Clean up stale pending orders that have timed out.
     */
    public void cleanupStale() {
        pendingByClOrdId.entrySet().removeIf(entry -> {
            PendingOrder pending = entry.getValue();
            if (pending.isExpired(orderTimeoutSeconds) && !pending.getFuture().isDone()) {
                pending.getFuture().completeExceptionally(
                        new OrderException("FIX order timeout for ClOrdID: " + pending.getClOrdId()));
                log.warn("Timed out pending order: ClOrdID={}", pending.getClOrdId());
                return true;
            }
            return false;
        });
    }

    /**
     * Get count of pending orders.
     */
    public int getPendingCount() {
        return pendingByClOrdId.size();
    }

    /**
     * Get all tracked order ID mappings.
     */
    public Map<String, String> getOrderIdMappings() {
        return Map.copyOf(clOrdIdToOrderId);
    }
}
