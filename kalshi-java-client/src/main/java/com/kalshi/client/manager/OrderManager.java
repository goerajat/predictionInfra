package com.kalshi.client.manager;

import com.kalshi.client.KalshiApi;
import com.kalshi.client.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages open orders by polling the Kalshi API at regular intervals.
 * Maintains orders organized by market ticker and provides change notifications.
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * OrderManager manager = new OrderManager(kalshiApi);
 * manager.addOrderChangeListener(event -> {
 *     System.out.println("Order change: " + event.getType() + " - " + event.getOrder());
 * });
 * manager.start();
 *
 * // Get orders for a specific ticker
 * List<Order> orders = manager.getOrdersByTicker("TICKER");
 *
 * // Stop when done
 * manager.stop();
 * }</pre>
 */
public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    private static final long DEFAULT_POLL_INTERVAL_MS = 5000; // 5 seconds

    private final KalshiApi api;
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Orders organized by ticker
    private final ConcurrentMap<String, List<Order>> ordersByTicker = new ConcurrentHashMap<>();

    // All orders by order ID for quick lookup
    private final ConcurrentMap<String, Order> ordersById = new ConcurrentHashMap<>();

    // Listeners for order changes
    private final List<Consumer<OrderChangeEvent>> listeners = new CopyOnWriteArrayList<>();

    private ScheduledFuture<?> pollTask;

    /**
     * Create an OrderManager with default 5-second polling interval.
     *
     * @param api KalshiApi instance (must be authenticated)
     */
    public OrderManager(KalshiApi api) {
        this(api, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Create an OrderManager with custom polling interval.
     *
     * @param api KalshiApi instance (must be authenticated)
     * @param pollIntervalMs Polling interval in milliseconds
     */
    public OrderManager(KalshiApi api, long pollIntervalMs) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.pollIntervalMs = pollIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OrderManager-Poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start polling for open orders.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting OrderManager with {}ms poll interval", pollIntervalMs);

            // Initial poll immediately
            pollTask = scheduler.scheduleAtFixedRate(
                    this::pollOrders,
                    0,
                    pollIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Stop polling for open orders.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping OrderManager");
            if (pollTask != null) {
                pollTask.cancel(false);
                pollTask = null;
            }
        }
    }

    /**
     * Shutdown the OrderManager and release resources.
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check if the OrderManager is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get all open orders.
     *
     * @return Unmodifiable list of all open orders
     */
    public List<Order> getAllOrders() {
        return Collections.unmodifiableList(new ArrayList<>(ordersById.values()));
    }

    /**
     * Get open orders for a specific ticker.
     *
     * @param ticker Market ticker
     * @return Unmodifiable list of orders for the ticker (empty if none)
     */
    public List<Order> getOrdersByTicker(String ticker) {
        List<Order> orders = ordersByTicker.get(ticker);
        return orders != null ? Collections.unmodifiableList(new ArrayList<>(orders)) : Collections.emptyList();
    }

    /**
     * Get an order by its ID.
     *
     * @param orderId Order ID
     * @return Order or null if not found
     */
    public Order getOrderById(String orderId) {
        return ordersById.get(orderId);
    }

    /**
     * Get all tickers that have open orders.
     *
     * @return Set of tickers with open orders
     */
    public Set<String> getTickersWithOrders() {
        return Collections.unmodifiableSet(new HashSet<>(ordersByTicker.keySet()));
    }

    /**
     * Get total count of open orders.
     */
    public int getOrderCount() {
        return ordersById.size();
    }

    /**
     * Get count of open orders for a specific ticker.
     */
    public int getOrderCount(String ticker) {
        List<Order> orders = ordersByTicker.get(ticker);
        return orders != null ? orders.size() : 0;
    }

    /**
     * Add a listener for order changes.
     *
     * @param listener Consumer to receive OrderChangeEvents
     */
    public void addOrderChangeListener(Consumer<OrderChangeEvent> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Remove an order change listener.
     *
     * @param listener Listener to remove
     */
    public void removeOrderChangeListener(Consumer<OrderChangeEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Inject an externally-provided order state change (e.g., from a FIX ExecutionReport).
     * Updates internal maps and fires OrderChangeEvent listeners immediately,
     * providing sub-millisecond callbacks instead of waiting for the next poll cycle.
     *
     * @param order The updated order state
     */
    public void injectOrderUpdate(Order order) {
        if (order == null || order.getOrderId() == null) return;

        String orderId = order.getOrderId();
        Order existing = ordersById.get(orderId);

        if (existing == null) {
            // New order
            ordersById.put(orderId, order);
            if (order.getTicker() != null) {
                ordersByTicker.computeIfAbsent(order.getTicker(), k -> new CopyOnWriteArrayList<>())
                        .add(order);
            }
            notifyListeners(new OrderChangeEvent(OrderChangeType.ADDED, order, null));
            log.debug("Order injected (new): {}", orderId);
        } else if (isTerminalStatus(order.getStatus())) {
            // Order completed/canceled/rejected — remove from tracking
            ordersById.remove(orderId);
            if (order.getTicker() != null) {
                List<Order> tickerOrders = ordersByTicker.get(order.getTicker());
                if (tickerOrders != null) {
                    tickerOrders.removeIf(o -> orderId.equals(o.getOrderId()));
                    if (tickerOrders.isEmpty()) {
                        ordersByTicker.remove(order.getTicker());
                    }
                }
            }
            notifyListeners(new OrderChangeEvent(OrderChangeType.REMOVED, order, null));
            log.debug("Order injected (removed): {} status={}", orderId, order.getStatus());
        } else {
            // Modified
            ordersById.put(orderId, order);
            if (order.getTicker() != null) {
                List<Order> tickerOrders = ordersByTicker.get(order.getTicker());
                if (tickerOrders != null) {
                    tickerOrders.replaceAll(o -> orderId.equals(o.getOrderId()) ? order : o);
                }
            }
            notifyListeners(new OrderChangeEvent(OrderChangeType.MODIFIED, order, null));
            log.debug("Order injected (modified): {}", orderId);
        }
    }

    private boolean isTerminalStatus(String status) {
        return "executed".equals(status) || "canceled".equals(status)
                || "expired".equals(status) || "rejected".equals(status);
    }

    /**
     * Force an immediate poll (outside the regular schedule).
     */
    public void pollNow() {
        if (running.get()) {
            scheduler.execute(this::pollOrders);
        }
    }

    private void pollOrders() {
        try {
            List<Order> openOrders = api.orders().getOpenOrders();
            processOrderUpdate(openOrders);
        } catch (Exception e) {
            log.error("Failed to poll orders: {}", e.getMessage(), e);
            notifyListeners(new OrderChangeEvent(OrderChangeType.ERROR, null, e.getMessage()));
        }
    }

    private void processOrderUpdate(List<Order> newOrders) {
        // Build map of new orders by ID
        Map<String, Order> newOrdersById = new HashMap<>();
        for (Order order : newOrders) {
            newOrdersById.put(order.getOrderId(), order);
        }

        // Find removed orders (in old but not in new)
        Set<String> removedIds = new HashSet<>(ordersById.keySet());
        removedIds.removeAll(newOrdersById.keySet());

        // Find added orders (in new but not in old)
        Set<String> addedIds = new HashSet<>(newOrdersById.keySet());
        addedIds.removeAll(ordersById.keySet());

        // Find modified orders (in both, but changed)
        List<Order> modifiedOrders = new ArrayList<>();
        for (Order newOrder : newOrders) {
            Order oldOrder = ordersById.get(newOrder.getOrderId());
            if (oldOrder != null && hasOrderChanged(oldOrder, newOrder)) {
                modifiedOrders.add(newOrder);
            }
        }

        // Notify about removed orders
        for (String removedId : removedIds) {
            Order removedOrder = ordersById.get(removedId);
            notifyListeners(new OrderChangeEvent(OrderChangeType.REMOVED, removedOrder, null));
        }

        // Notify about added orders
        for (String addedId : addedIds) {
            Order addedOrder = newOrdersById.get(addedId);
            notifyListeners(new OrderChangeEvent(OrderChangeType.ADDED, addedOrder, null));
        }

        // Notify about modified orders
        for (Order modifiedOrder : modifiedOrders) {
            notifyListeners(new OrderChangeEvent(OrderChangeType.MODIFIED, modifiedOrder, null));
        }

        // Update internal state
        rebuildOrderMaps(newOrders);

        // Log summary if there were changes
        if (!removedIds.isEmpty() || !addedIds.isEmpty() || !modifiedOrders.isEmpty()) {
            log.debug("Order update: {} added, {} removed, {} modified, {} total",
                    addedIds.size(), removedIds.size(), modifiedOrders.size(), ordersById.size());
        }
    }

    private boolean hasOrderChanged(Order oldOrder, Order newOrder) {
        // Check if any important fields changed
        return !Objects.equals(oldOrder.getRemainingCount(), newOrder.getRemainingCount())
                || !Objects.equals(oldOrder.getFillCount(), newOrder.getFillCount())
                || !Objects.equals(oldOrder.getQueuePosition(), newOrder.getQueuePosition())
                || !Objects.equals(oldOrder.getYesPrice(), newOrder.getYesPrice())
                || !Objects.equals(oldOrder.getNoPrice(), newOrder.getNoPrice())
                || !Objects.equals(oldOrder.getStatus(), newOrder.getStatus());
    }

    private void rebuildOrderMaps(List<Order> orders) {
        // Clear and rebuild
        ordersById.clear();
        ordersByTicker.clear();

        for (Order order : orders) {
            ordersById.put(order.getOrderId(), order);
            ordersByTicker.computeIfAbsent(order.getTicker(), k -> new CopyOnWriteArrayList<>())
                    .add(order);
        }
    }

    private void notifyListeners(OrderChangeEvent event) {
        for (Consumer<OrderChangeEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in order change listener: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Types of order changes.
     */
    public enum OrderChangeType {
        ADDED,      // New order appeared
        REMOVED,    // Order was removed (filled, canceled, or expired)
        MODIFIED,   // Order was modified (price, quantity, queue position changed)
        ERROR       // Error occurred during polling
    }

    /**
     * Event representing a change in orders.
     */
    public static class OrderChangeEvent {
        private final OrderChangeType type;
        private final Order order;
        private final String errorMessage;

        public OrderChangeEvent(OrderChangeType type, Order order, String errorMessage) {
            this.type = type;
            this.order = order;
            this.errorMessage = errorMessage;
        }

        public OrderChangeType getType() {
            return type;
        }

        public Order getOrder() {
            return order;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            if (type == OrderChangeType.ERROR) {
                return "OrderChangeEvent{type=ERROR, message='" + errorMessage + "'}";
            }
            return "OrderChangeEvent{type=" + type + ", order=" + order + "}";
        }
    }
}
