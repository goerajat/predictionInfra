package com.kalshi.fix;

import com.kalshi.client.model.CreateOrderRequest;
import com.kalshi.client.model.Order;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Bidirectional mapping between Kalshi REST order model and FIX messages.
 *
 * <p>Kalshi FIX encoding (FIXT.1.1 / FIX 5.0 SP2):</p>
 * <ul>
 *   <li>Side 1 = Buy Yes contracts</li>
 *   <li>Side 2 = Sell No contracts (equivalent to buying Yes at complement)</li>
 *   <li>Price = integer cents (1-99)</li>
 *   <li>OrdType = 2 (Limit only)</li>
 * </ul>
 */
public class FixFieldMapper {

    // Standard FIX tags
    public static final int TAG_CL_ORD_ID = 11;
    public static final int TAG_ORDER_ID = 37;
    public static final int TAG_EXEC_ID = 17;
    public static final int TAG_EXEC_TYPE = 150;
    public static final int TAG_ORD_STATUS = 39;
    public static final int TAG_SYMBOL = 55;
    public static final int TAG_SIDE = 54;
    public static final int TAG_ORDER_QTY = 38;
    public static final int TAG_ORD_TYPE = 40;
    public static final int TAG_PRICE = 44;
    public static final int TAG_TIME_IN_FORCE = 59;
    public static final int TAG_TRANSACT_TIME = 60;
    public static final int TAG_EXEC_INST = 18;
    public static final int TAG_CUM_QTY = 14;
    public static final int TAG_LEAVES_QTY = 151;
    public static final int TAG_AVG_PX = 6;
    public static final int TAG_LAST_PX = 31;
    public static final int TAG_LAST_QTY = 32;
    public static final int TAG_ORD_REJ_REASON = 103;
    public static final int TAG_TEXT = 58;
    public static final int TAG_ORIG_CL_ORD_ID = 41;
    public static final int TAG_SECONDARY_CL_ORD_ID = 526;

    // Kalshi custom tags
    public static final int TAG_SELF_TRADE_PREVENTION = 2964;
    public static final int TAG_CANCEL_ON_PAUSE = 21006;
    public static final int TAG_MAX_EXECUTION_COST = 21009;

    // FIX side values
    public static final char SIDE_BUY = '1';
    public static final char SIDE_SELL = '2';

    // FIX ExecType values
    public static final char EXEC_TYPE_NEW = '0';
    public static final char EXEC_TYPE_PARTIAL_FILL = '1';
    public static final char EXEC_TYPE_FILL = '2';
    public static final char EXEC_TYPE_CANCELED = '4';
    public static final char EXEC_TYPE_REPLACED = '5';
    public static final char EXEC_TYPE_PENDING_NEW = 'A';
    public static final char EXEC_TYPE_REJECTED = '8';
    public static final char EXEC_TYPE_EXPIRED = 'C';
    public static final char EXEC_TYPE_TRADE = 'F';

    // FIX OrdStatus values
    public static final char ORD_STATUS_NEW = '0';
    public static final char ORD_STATUS_PARTIAL = '1';
    public static final char ORD_STATUS_FILLED = '2';
    public static final char ORD_STATUS_CANCELED = '4';
    public static final char ORD_STATUS_REPLACED = '5';
    public static final char ORD_STATUS_PENDING_CANCEL = '6';
    public static final char ORD_STATUS_REJECTED = '8';
    public static final char ORD_STATUS_PENDING_NEW = 'A';
    public static final char ORD_STATUS_EXPIRED = 'C';

    private static final DateTimeFormatter FIX_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);

    /**
     * Populate a NewOrderSingle (MsgType D) message from a CreateOrderRequest.
     *
     * @param msg     The outgoing FIX message (already claimed with MsgType "D")
     * @param request The Kalshi create order request
     * @param clOrdId The ClOrdID to use
     */
    public static void populateNewOrder(RingBufferOutgoingMessage msg,
                                        CreateOrderRequest request,
                                        String clOrdId) {
        msg.setField(TAG_CL_ORD_ID, clOrdId);
        msg.setField(TAG_SYMBOL, request.getTicker());
        msg.setField(TAG_SIDE, mapSideToFix(request.getAction(), request.getSide()));
        msg.setField(TAG_ORDER_QTY, request.getCount());
        msg.setField(TAG_ORD_TYPE, '2'); // Limit
        msg.setField(TAG_PRICE, mapPriceToFix(request));
        msg.setField(TAG_TIME_IN_FORCE, mapTimeInForceToFix(request.getTimeInForce()));
        msg.setField(TAG_TRANSACT_TIME, FIX_TIMESTAMP.format(Instant.now()));

        // Optional fields
        if (request.getPostOnly() != null && request.getPostOnly()) {
            msg.setField(TAG_EXEC_INST, '6'); // Participate don't initiate
        }

        if (request.getSelfTradePreventionType() != null) {
            msg.setField(TAG_SELF_TRADE_PREVENTION,
                    mapSelfTradePreventionToFix(request.getSelfTradePreventionType()));
        }

        if (request.getCancelOrderOnPause() != null) {
            msg.setField(TAG_CANCEL_ON_PAUSE, request.getCancelOrderOnPause());
        }

        if (request.getOrderGroupId() != null) {
            msg.setField(TAG_SECONDARY_CL_ORD_ID, request.getOrderGroupId());
        }

        if (request.getBuyMaxCost() != null) {
            msg.setField(TAG_MAX_EXECUTION_COST, request.getBuyMaxCost());
        }
    }

    /**
     * Populate an OrderCancelRequest (MsgType F) message.
     *
     * @param msg           The outgoing FIX message (already claimed with MsgType "F")
     * @param clOrdId       New ClOrdID for this cancel request
     * @param origClOrdId   The ClOrdID of the order to cancel
     * @param symbol        Market ticker
     * @param side          FIX side of the original order
     */
    public static void populateCancelRequest(RingBufferOutgoingMessage msg,
                                              String clOrdId,
                                              String origClOrdId,
                                              String symbol,
                                              char side) {
        msg.setField(TAG_CL_ORD_ID, clOrdId);
        msg.setField(TAG_ORIG_CL_ORD_ID, origClOrdId);
        msg.setField(TAG_SYMBOL, symbol);
        msg.setField(TAG_SIDE, side);
        msg.setField(TAG_TRANSACT_TIME, FIX_TIMESTAMP.format(Instant.now()));
    }

    /**
     * Populate an OrderCancelReplaceRequest (MsgType G) message for amending.
     *
     * @param msg           The outgoing FIX message (already claimed with MsgType "G")
     * @param clOrdId       New ClOrdID for this amend request
     * @param origClOrdId   The ClOrdID of the order to amend
     * @param symbol        Market ticker
     * @param side          FIX side of the original order
     * @param newPrice      New price in cents (null to keep)
     * @param newQty        New quantity (null to keep)
     */
    public static void populateAmendRequest(RingBufferOutgoingMessage msg,
                                             String clOrdId,
                                             String origClOrdId,
                                             String symbol,
                                             char side,
                                             Integer newPrice,
                                             Integer newQty) {
        msg.setField(TAG_CL_ORD_ID, clOrdId);
        msg.setField(TAG_ORIG_CL_ORD_ID, origClOrdId);
        msg.setField(TAG_SYMBOL, symbol);
        msg.setField(TAG_SIDE, side);
        msg.setField(TAG_ORD_TYPE, '2'); // Limit
        msg.setField(TAG_TRANSACT_TIME, FIX_TIMESTAMP.format(Instant.now()));

        if (newPrice != null) {
            msg.setField(TAG_PRICE, newPrice);
        }
        if (newQty != null) {
            msg.setField(TAG_ORDER_QTY, newQty);
        }
    }

    /**
     * Parse an ExecutionReport (MsgType 8) into a Kalshi Order model.
     *
     * @param msg The incoming FIX ExecutionReport message
     * @return Order populated from the ER fields
     */
    public static Order parseExecutionReport(IncomingFixMessage msg) {
        Order order = new Order();

        // Core IDs
        if (msg.hasField(TAG_ORDER_ID)) {
            order.setOrderId(msg.getCharSequence(TAG_ORDER_ID).toString());
        }
        if (msg.hasField(TAG_CL_ORD_ID)) {
            order.setClientOrderId(msg.getCharSequence(TAG_CL_ORD_ID).toString());
        }

        // Instrument
        if (msg.hasField(TAG_SYMBOL)) {
            order.setTicker(msg.getCharSequence(TAG_SYMBOL).toString());
        }

        // Side mapping (FIX -> Kalshi)
        if (msg.hasField(TAG_SIDE)) {
            char fixSide = msg.getChar(TAG_SIDE);
            order.setAction(mapFixSideToAction(fixSide));
            order.setSide(mapFixSideToSide(fixSide));
        }

        // Status
        if (msg.hasField(TAG_ORD_STATUS)) {
            order.setStatus(mapFixStatusToKalshi(msg.getChar(TAG_ORD_STATUS)));
        }

        // Quantities
        if (msg.hasField(TAG_ORDER_QTY)) {
            order.setInitialCount(msg.getInt(TAG_ORDER_QTY));
        }
        if (msg.hasField(TAG_CUM_QTY)) {
            order.setFillCount(msg.getInt(TAG_CUM_QTY));
        }
        if (msg.hasField(TAG_LEAVES_QTY)) {
            order.setRemainingCount(msg.getInt(TAG_LEAVES_QTY));
        }

        // Price
        if (msg.hasField(TAG_PRICE)) {
            int price = msg.getInt(TAG_PRICE);
            if (msg.hasField(TAG_SIDE)) {
                char fixSide = msg.getChar(TAG_SIDE);
                if (fixSide == SIDE_BUY) {
                    order.setYesPrice(price);
                    order.setNoPrice(100 - price);
                } else {
                    order.setYesPrice(100 - price);
                    order.setNoPrice(price);
                }
            }
        }

        // Type
        order.setType("limit");

        // Timestamp
        if (msg.hasField(TAG_TRANSACT_TIME)) {
            order.setLastUpdateTime(Instant.now());
        }

        return order;
    }

    /**
     * Get the ExecType from an ExecutionReport.
     */
    public static char getExecType(IncomingFixMessage msg) {
        return msg.hasField(TAG_EXEC_TYPE) ? msg.getChar(TAG_EXEC_TYPE) : '\0';
    }

    /**
     * Get the rejection reason text from an ExecutionReport.
     */
    public static String getRejectionReason(IncomingFixMessage msg) {
        StringBuilder sb = new StringBuilder();
        if (msg.hasField(TAG_ORD_REJ_REASON)) {
            sb.append("OrdRejReason=").append(msg.getInt(TAG_ORD_REJ_REASON));
        }
        if (msg.hasField(TAG_TEXT)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(msg.getCharSequence(TAG_TEXT).toString());
        }
        return sb.length() > 0 ? sb.toString() : "Unknown rejection";
    }

    // ==================== Side Mapping ====================

    /**
     * Map Kalshi action+side to FIX Side.
     *
     * <pre>
     * action=buy,  side=yes → FIX Side=1 (Buy)   — buying yes
     * action=sell, side=yes → FIX Side=2 (Sell)   — selling yes
     * action=buy,  side=no  → FIX Side=2 (Sell)   — buying no = selling yes
     * action=sell, side=no  → FIX Side=1 (Buy)    — selling no = buying yes
     * </pre>
     */
    public static char mapSideToFix(String action, String side) {
        boolean isBuy = "buy".equalsIgnoreCase(action);
        boolean isYes = "yes".equalsIgnoreCase(side);

        if (isBuy && isYes) return SIDE_BUY;
        if (!isBuy && isYes) return SIDE_SELL;
        if (isBuy && !isYes) return SIDE_SELL;  // buy no = sell yes
        return SIDE_BUY;                         // sell no = buy yes
    }

    /**
     * Map FIX Side to Kalshi action.
     * FIX Side 1 (Buy) → "buy", FIX Side 2 (Sell) → "sell"
     */
    public static String mapFixSideToAction(char fixSide) {
        return fixSide == SIDE_BUY ? "buy" : "sell";
    }

    /**
     * Map FIX Side to Kalshi side.
     * FIX Side 1 (Buy) → "yes", FIX Side 2 (Sell) → "yes"
     * (Kalshi FIX always deals in Yes contracts)
     */
    public static String mapFixSideToSide(char fixSide) {
        return "yes";
    }

    /**
     * Map the price from a CreateOrderRequest to FIX price integer.
     * Uses yesPrice for yes-side, noPrice for no-side (converting to complement for FIX).
     */
    public static int mapPriceToFix(CreateOrderRequest request) {
        boolean isYes = "yes".equalsIgnoreCase(request.getSide());
        boolean isBuy = "buy".equalsIgnoreCase(request.getAction());

        if (isYes) {
            // Yes side: price is direct
            return request.getYesPrice() != null ? request.getYesPrice() : (100 - request.getNoPrice());
        } else {
            // No side: need to convert. FIX always deals in "yes" prices.
            // Buy no at noPrice X = sell yes at yesPrice (100-X)
            // Sell no at noPrice X = buy yes at yesPrice (100-X)
            if (request.getNoPrice() != null) {
                return 100 - request.getNoPrice();
            } else {
                return request.getYesPrice();
            }
        }
    }

    // ==================== Status Mapping ====================

    /**
     * Map FIX OrdStatus to Kalshi status string.
     */
    public static String mapFixStatusToKalshi(char ordStatus) {
        switch (ordStatus) {
            case ORD_STATUS_NEW:
            case ORD_STATUS_PARTIAL:
            case ORD_STATUS_PENDING_NEW:
            case ORD_STATUS_REPLACED:
                return "resting";
            case ORD_STATUS_FILLED:
                return "executed";
            case ORD_STATUS_CANCELED:
            case ORD_STATUS_PENDING_CANCEL:
                return "canceled";
            case ORD_STATUS_REJECTED:
                return "rejected";
            case ORD_STATUS_EXPIRED:
                return "expired";
            default:
                return "unknown";
        }
    }

    // ==================== TimeInForce Mapping ====================

    /**
     * Map Kalshi TimeInForce string to FIX TimeInForce char.
     */
    public static char mapTimeInForceToFix(String tif) {
        if (tif == null) return '1'; // Default GTC
        switch (tif.toLowerCase()) {
            case "good_till_canceled":
            case "gtc":
                return '1';
            case "immediate_or_cancel":
            case "ioc":
                return '3';
            case "fill_or_kill":
            case "fok":
                return '4';
            case "day":
                return '0';
            default:
                return '1'; // Default GTC
        }
    }

    /**
     * Map FIX TimeInForce char to Kalshi string.
     */
    public static String mapFixTimeInForceToKalshi(char fixTif) {
        switch (fixTif) {
            case '0': return "day";
            case '1': return "good_till_canceled";
            case '3': return "immediate_or_cancel";
            case '4': return "fill_or_kill";
            default: return "good_till_canceled";
        }
    }

    // ==================== Helper Mappings ====================

    private static char mapSelfTradePreventionToFix(String stp) {
        if (stp == null) return '1';
        switch (stp.toLowerCase()) {
            case "maker":
            case "cancel_resting_order":
                return '2';
            case "taker":
            case "cancel_new_order":
            default:
                return '1';
        }
    }

    /**
     * Generate a unique ClOrdID.
     */
    public static String generateClOrdId() {
        return UUID.randomUUID().toString();
    }
}
