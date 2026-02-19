package com.kalshi.client.config;

import com.betting.marketdata.api.MarketDataManager;
import com.kalshi.client.KalshiApi;
import com.kalshi.client.filter.EventFilter;
import com.kalshi.client.filter.EventFilterCriteria;
import com.kalshi.client.filter.EventInterestList;
import com.kalshi.client.manager.EventManager;
import com.kalshi.client.manager.MarketManager;
import com.kalshi.client.manager.OrderManager;
import com.kalshi.client.manager.PositionManager;
import com.kalshi.client.model.Event;
import com.kalshi.client.risk.RiskChecker;
import com.kalshi.client.risk.RiskConfig;
import com.kalshi.client.strategy.EventStrategy;
import com.kalshi.client.strategy.StrategyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Launcher component that executes the EventFilter -> StrategyManager workflow
 * based on configuration from a properties file.
 *
 * <p>Supports both synchronous and asynchronous launching:</p>
 *
 * <p>Async Usage (recommended):</p>
 * <pre>{@code
 * StrategyConfig config = StrategyLauncher.loadConfig("strategy.properties");
 * StrategyLauncher launcher = new StrategyLauncher(config, api, orderManager, positionManager, marketManager);
 *
 * // Launch asynchronously - strategies are created as events load
 * launcher.launchAsync(result -> {
 *     if (result.isSuccess()) {
 *         System.out.println("Launched " + result.getStrategiesCreated() + " strategies");
 *     }
 * });
 * }</pre>
 *
 * <p>Sync Usage:</p>
 * <pre>{@code
 * LaunchResult result = launcher.launch();
 * if (result.isSuccess()) {
 *     StrategyManager manager = result.getStrategyManager();
 * }
 * }</pre>
 */
public class StrategyLauncher {

    private static final Logger log = LoggerFactory.getLogger(StrategyLauncher.class);

    private final StrategyConfig config;
    private final KalshiApi api;
    private final OrderManager orderManager;
    private final PositionManager positionManager;
    private final MarketManager marketManager;
    private final EventManager eventManager;
    private final MarketDataManager marketDataManager;  // External market data (E*TRADE, etc.)

    private StrategyManager strategyManager;
    private RiskChecker riskChecker;
    private EventFilter eventFilter;
    private EventInterestList interestList;

    private final List<Consumer<String>> progressListeners = new ArrayList<>();
    private volatile boolean launched = false;
    private volatile boolean launching = false;
    private AutoCloseable transportLifecycle;

    /**
     * Create a StrategyLauncher with existing services.
     *
     * @param config StrategyConfig to use
     * @param api KalshiApi instance
     * @param orderManager OrderManager instance
     * @param positionManager PositionManager instance
     * @param marketManager MarketManager instance
     */
    public StrategyLauncher(StrategyConfig config, KalshiApi api,
                            OrderManager orderManager, PositionManager positionManager,
                            MarketManager marketManager) {
        this(config, api, orderManager, positionManager, marketManager, null, null);
    }

    /**
     * Create a StrategyLauncher with existing services and EventManager.
     *
     * @param config StrategyConfig to use
     * @param api KalshiApi instance
     * @param orderManager OrderManager instance
     * @param positionManager PositionManager instance
     * @param marketManager MarketManager instance
     * @param eventManager EventManager instance (optional, will create if null)
     */
    public StrategyLauncher(StrategyConfig config, KalshiApi api,
                            OrderManager orderManager, PositionManager positionManager,
                            MarketManager marketManager, EventManager eventManager) {
        this(config, api, orderManager, positionManager, marketManager, eventManager, null);
    }

    /**
     * Create a StrategyLauncher with existing services, EventManager, and MarketDataManager.
     *
     * @param config StrategyConfig to use
     * @param api KalshiApi instance
     * @param orderManager OrderManager instance
     * @param positionManager PositionManager instance
     * @param marketManager MarketManager instance
     * @param eventManager EventManager instance (optional, will create if null)
     * @param marketDataManager External market data manager (optional)
     */
    public StrategyLauncher(StrategyConfig config, KalshiApi api,
                            OrderManager orderManager, PositionManager positionManager,
                            MarketManager marketManager, EventManager eventManager,
                            MarketDataManager marketDataManager) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.orderManager = Objects.requireNonNull(orderManager, "orderManager must not be null");
        this.positionManager = Objects.requireNonNull(positionManager, "positionManager must not be null");
        this.marketManager = Objects.requireNonNull(marketManager, "marketManager must not be null");
        this.eventManager = eventManager != null ? eventManager : new EventManager(api);
        this.marketDataManager = marketDataManager;  // can be null
    }

    // ==================== Configuration Loading ====================

    /**
     * Load configuration from a properties file.
     *
     * @param filePath Path to the properties file
     * @return Loaded StrategyConfig
     * @throws IOException if file cannot be read
     */
    public static StrategyConfig loadConfig(String filePath) throws IOException {
        return loadConfig(Path.of(filePath));
    }

    /**
     * Load configuration from a properties file.
     *
     * @param path Path to the properties file
     * @return Loaded StrategyConfig
     * @throws IOException if file cannot be read
     */
    public static StrategyConfig loadConfig(Path path) throws IOException {
        log.info("Loading strategy configuration from: {}", path.toAbsolutePath());

        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }

        Properties props = new Properties();
        try (InputStream is = new FileInputStream(path.toFile())) {
            props.load(is);
        }

        StrategyConfig config = StrategyConfig.fromProperties(props);
        log.info("Configuration loaded: {}", config);

        return config;
    }

    /**
     * Load configuration from classpath resource.
     *
     * @param resourceName Resource name (e.g., "strategy.properties")
     * @return Loaded StrategyConfig
     * @throws IOException if resource cannot be read
     */
    public static StrategyConfig loadConfigFromResource(String resourceName) throws IOException {
        log.info("Loading strategy configuration from resource: {}", resourceName);

        try (InputStream is = StrategyLauncher.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IOException("Configuration resource not found: " + resourceName);
            }

            Properties props = new Properties();
            props.load(is);

            return StrategyConfig.fromProperties(props);
        }
    }

    // ==================== Async Launch Workflow ====================

    /**
     * Launch strategies asynchronously.
     * Strategies are created as events are loaded via callbacks.
     *
     * @param onComplete Callback when launch completes
     */
    public void launchAsync(Consumer<LaunchResult> onComplete) {
        launchAsync(null, onComplete);
    }

    /**
     * Launch strategies asynchronously with per-strategy callback.
     * The onStrategyCreated callback is invoked for each strategy as it's created.
     *
     * @param onStrategyCreated Callback for each strategy created (optional)
     * @param onComplete Callback when launch completes
     */
    public void launchAsync(Consumer<EventStrategy> onStrategyCreated, Consumer<LaunchResult> onComplete) {
        if (launched || launching) {
            if (onComplete != null) {
                onComplete.accept(LaunchResult.failure("Already launched or launching"));
            }
            return;
        }
        launching = true;

        try {
            // Validate configuration
            progress("Validating configuration...");
            List<String> errors = config.getValidationErrors();
            if (!errors.isEmpty()) {
                String errorMsg = "Configuration validation failed:\n" + String.join("\n", errors);
                log.error(errorMsg);
                launching = false;
                if (onComplete != null) {
                    onComplete.accept(LaunchResult.failure(errorMsg));
                }
                return;
            }

            // Configure risk checker
            progress("Configuring risk checker...");
            configureRiskChecker();

            // Create event filter with EventManager
            progress("Creating event filter...");
            eventFilter = createEventFilter();

            // Build filter criteria
            progress("Building filter criteria...");
            EventFilterCriteria criteria = buildFilterCriteria();
            progress("Filter criteria: " + criteria);

            // Create strategy manager
            progress("Creating strategy manager...");
            strategyManager = new StrategyManager(api, orderManager, positionManager, marketManager, marketDataManager);

            // Track created strategies
            AtomicInteger createdCount = new AtomicInteger(0);
            List<Event> matchedEvents = Collections.synchronizedList(new ArrayList<>());

            // Get strategy constructor
            Constructor<?> constructor;
            try {
                constructor = getStrategyConstructor();
            } catch (Exception e) {
                launching = false;
                if (onComplete != null) {
                    onComplete.accept(LaunchResult.failure("Failed to get strategy constructor: " + e.getMessage(), e));
                }
                return;
            }

            progress("Starting async event filtering from series: " + config.getSeriesTickers());

            // Filter events asynchronously - create strategies as events match
            eventFilter.filterAsync(
                    config.getSeriesTickers(),
                    criteria,
                    // Per-event callback
                    event -> {
                        matchedEvents.add(event);

                        // Check max strategies limit
                        if (createdCount.get() >= config.getMaxStrategies()) {
                            log.debug("Max strategies reached ({}), skipping event: {}",
                                    config.getMaxStrategies(), event.getEventTicker());
                            return;
                        }

                        // Create strategy for this event
                        try {
                            EventStrategy strategy = createStrategyForEvent(constructor, event);
                            if (strategy != null) {
                                strategyManager.addStrategy(strategy);
                                createdCount.incrementAndGet();

                                progress("Created strategy for: " + event.getEventTicker() +
                                        " (" + createdCount.get() + "/" + config.getMaxStrategies() + ")");

                                if (onStrategyCreated != null) {
                                    onStrategyCreated.accept(strategy);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to create strategy for event {}: {}",
                                    event.getEventTicker(), e.getMessage());
                        }
                    },
                    // Completion callback
                    interestListResult -> {
                        this.interestList = interestListResult;
                        int totalMatched = matchedEvents.size();
                        int strategiesCreated = createdCount.get();

                        progress("Event filtering complete: " + totalMatched + " events matched");

                        if (strategiesCreated == 0) {
                            progress("No strategies created");
                            launched = true;
                            launching = false;
                            if (onComplete != null) {
                                onComplete.accept(LaunchResult.success(strategyManager, interestListResult, 0,
                                        "No strategies created - " + totalMatched + " events matched criteria"));
                            }
                            return;
                        }

                        // Initialize all strategies
                        progress("Initializing " + strategiesCreated + " strategies...");
                        try {
                            strategyManager.initializeAll();
                            progress("All strategies initialized");

                            launched = true;
                            launching = false;

                            if (onComplete != null) {
                                onComplete.accept(LaunchResult.success(strategyManager, interestListResult,
                                        strategiesCreated, null));
                            }
                        } catch (Exception e) {
                            log.error("Failed to initialize strategies: {}", e.getMessage());
                            launching = false;
                            if (onComplete != null) {
                                onComplete.accept(LaunchResult.failure("Strategy initialization failed: " + e.getMessage(), e));
                            }
                        }
                    }
            );

        } catch (Exception e) {
            log.error("Strategy launch failed: {}", e.getMessage(), e);
            launching = false;
            if (onComplete != null) {
                onComplete.accept(LaunchResult.failure("Launch failed: " + e.getMessage(), e));
            }
        }
    }

    // ==================== Synchronous Launch Workflow ====================

    /**
     * Execute the full workflow synchronously: filter events, create strategies, initialize.
     *
     * @return LaunchResult with success status and details
     */
    public LaunchResult launch() {
        if (launched) {
            return LaunchResult.failure("Already launched");
        }

        try {
            // Validate configuration
            progress("Validating configuration...");
            List<String> errors = config.getValidationErrors();
            if (!errors.isEmpty()) {
                String errorMsg = "Configuration validation failed:\n" + String.join("\n", errors);
                log.error(errorMsg);
                return LaunchResult.failure(errorMsg);
            }

            // Configure risk checker
            progress("Configuring risk checker...");
            configureRiskChecker();

            // Create event filter
            progress("Creating event filter...");
            eventFilter = createEventFilter();

            // Build filter criteria
            progress("Building filter criteria...");
            EventFilterCriteria criteria = buildFilterCriteria();
            progress("Filter criteria: " + criteria);

            // Filter events (synchronous)
            progress("Filtering events from series: " + config.getSeriesTickers());
            interestList = eventFilter.filter(config.getSeriesTickers(), criteria);
            progress("Found " + interestList.size() + " events with " + interestList.getTotalMarketCount() + " markets");

            if (interestList.isEmpty()) {
                progress("No events match the filter criteria");
                return LaunchResult.success(null, interestList, 0, "No events matched criteria");
            }

            // Log found events
            for (Event event : interestList.getEventsSortedByStrikeDate()) {
                progress("  - " + event.getEventTicker() + ": " + event.getTitle() +
                        " (" + (event.getMarkets() != null ? event.getMarkets().size() : 0) + " markets)");
            }

            // Limit number of strategies
            EventInterestList limitedList = interestList;
            if (interestList.size() > config.getMaxStrategies()) {
                progress("Limiting to " + config.getMaxStrategies() + " strategies (configured max)");
                limitedList = interestList.limit(config.getMaxStrategies());
            }

            // Create strategy manager
            progress("Creating strategy manager...");
            strategyManager = new StrategyManager(api, orderManager, positionManager, marketManager, marketDataManager);

            // Create strategies
            progress("Creating strategies using class: " + config.getStrategyClassName());
            int created = createStrategies(limitedList);
            progress("Created " + created + " strategies");

            if (created == 0) {
                return LaunchResult.success(strategyManager, limitedList, 0, "No strategies created");
            }

            // Initialize strategies
            progress("Initializing strategies...");
            strategyManager.initializeAll();
            progress("All strategies initialized");

            launched = true;
            return LaunchResult.success(strategyManager, limitedList, created, null);

        } catch (Exception e) {
            log.error("Strategy launch failed: {}", e.getMessage(), e);
            return LaunchResult.failure("Launch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Configure the risk checker based on config.
     */
    private void configureRiskChecker() {
        if (!config.isRiskEnabled()) {
            log.info("Risk checking disabled by configuration");
            return;
        }

        RiskConfig.Builder riskBuilder = RiskConfig.builder();

        // Global limits
        if (config.getMaxOrderQuantity() != null) {
            riskBuilder.maxOrderQuantity(config.getMaxOrderQuantity());
        }
        if (config.getMaxOrderNotional() != null) {
            riskBuilder.maxOrderNotional(config.getMaxOrderNotional());
        }
        if (config.getMaxPositionQuantity() != null) {
            riskBuilder.maxPositionQuantity(config.getMaxPositionQuantity());
        }
        if (config.getMaxPositionNotional() != null) {
            riskBuilder.maxPositionNotional(config.getMaxPositionNotional());
        }

        // Strategy-specific overrides
        Properties overrides = config.getStrategyRiskOverrides();
        Map<String, Map<String, Integer>> strategyLimits = parseStrategyRiskOverrides(overrides);

        for (Map.Entry<String, Map<String, Integer>> entry : strategyLimits.entrySet()) {
            String strategyName = entry.getKey();
            Map<String, Integer> limits = entry.getValue();

            RiskConfig.StrategyBuilder stratBuilder = riskBuilder.forStrategy(strategyName);
            if (limits.containsKey("maxOrderQuantity")) {
                stratBuilder.maxOrderQuantity(limits.get("maxOrderQuantity"));
            }
            if (limits.containsKey("maxOrderNotional")) {
                stratBuilder.maxOrderNotional(limits.get("maxOrderNotional"));
            }
            if (limits.containsKey("maxPositionQuantity")) {
                stratBuilder.maxPositionQuantity(limits.get("maxPositionQuantity"));
            }
            if (limits.containsKey("maxPositionNotional")) {
                stratBuilder.maxPositionNotional(limits.get("maxPositionNotional"));
            }
            stratBuilder.done();
        }

        RiskConfig riskConfig = riskBuilder.build();
        riskChecker = new RiskChecker(riskConfig);
        riskChecker.setPositionProvider(positionManager::getPosition);

        // Set on order service
        api.orders().setRiskChecker(riskChecker);

        log.info("Risk checker configured: {}", riskConfig);
    }

    /**
     * Parse strategy-specific risk overrides from properties.
     * Format: risk.strategy.StrategyName.limitName=value
     */
    private Map<String, Map<String, Integer>> parseStrategyRiskOverrides(Properties props) {
        Map<String, Map<String, Integer>> result = new HashMap<>();

        for (String key : props.stringPropertyNames()) {
            // risk.strategy.StrategyName.maxOrderQuantity
            if (key.startsWith("risk.strategy.")) {
                String remainder = key.substring("risk.strategy.".length());
                int dotIndex = remainder.indexOf('.');
                if (dotIndex > 0) {
                    String strategyName = remainder.substring(0, dotIndex);
                    String limitName = remainder.substring(dotIndex + 1);
                    String value = props.getProperty(key);

                    if (value != null && !value.isEmpty()) {
                        result.computeIfAbsent(strategyName, k -> new HashMap<>())
                                .put(limitName, Integer.parseInt(value));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Create the event filter with EventManager.
     */
    private EventFilter createEventFilter() {
        return EventFilter.builder(api)
                .eventManager(eventManager)
                .parallelThreads(config.getParallelThreads())
                .build();
    }

    /**
     * Build filter criteria from config.
     */
    private EventFilterCriteria buildFilterCriteria() {
        EventFilterCriteria.Builder builder = EventFilterCriteria.builder();

        if (config.getMinStrikeDateFromNow() != null) {
            builder.minStrikeDateFromNow(config.getMinStrikeDateFromNow());
        }
        if (config.getMaxStrikeDateFromNow() != null) {
            builder.maxStrikeDateFromNow(config.getMaxStrikeDateFromNow());
        }
        if (config.getCategory() != null) {
            builder.category(config.getCategory());
        }
        if (config.getCategories() != null && !config.getCategories().isEmpty()) {
            builder.categories(config.getCategories());
        }
        if (config.getTitleContains() != null) {
            builder.titleContains(config.getTitleContains());
        }
        if (config.getTitlePattern() != null) {
            builder.titlePattern(config.getTitlePattern());
        }
        if (config.getMutuallyExclusive() != null) {
            builder.mutuallyExclusive(config.getMutuallyExclusive());
        }

        return builder.build();
    }

    /**
     * Get the strategy constructor.
     * Tries to find a constructor with (String, StrategyConfig) first,
     * then falls back to (String) only.
     */
    private Constructor<?> getStrategyConstructor() throws Exception {
        String className = config.getStrategyClassName();
        Class<?> strategyClass = Class.forName(className);

        if (!EventStrategy.class.isAssignableFrom(strategyClass)) {
            throw new IllegalArgumentException("Strategy class must extend EventStrategy: " + className);
        }

        // Try constructor with config first
        try {
            return strategyClass.getDeclaredConstructor(String.class, StrategyConfig.class);
        } catch (NoSuchMethodException e) {
            // Fall back to simple constructor
            try {
                return strategyClass.getDeclaredConstructor(String.class);
            } catch (NoSuchMethodException e2) {
                throw new IllegalArgumentException(
                        "Strategy class must have a constructor that takes (String, StrategyConfig) or (String): " + className);
            }
        }
    }

    /**
     * Create a strategy for a single event.
     */
    private EventStrategy createStrategyForEvent(Constructor<?> constructor, Event event) throws Exception {
        EventStrategy strategy;

        // Check if constructor takes config
        if (constructor.getParameterCount() == 2) {
            strategy = (EventStrategy) constructor.newInstance(event.getEventTicker(), config);
        } else {
            strategy = (EventStrategy) constructor.newInstance(event.getEventTicker());
        }

        // Set current strategy for risk checker
        if (riskChecker != null) {
            riskChecker.setCurrentStrategy(strategy.getStrategyName());
        }

        return strategy;
    }

    /**
     * Create strategies for the interest list (synchronous).
     */
    private int createStrategies(EventInterestList interestList) throws Exception {
        Constructor<?> constructor = getStrategyConstructor();

        int created = 0;
        for (String eventTicker : interestList.getEventTickers()) {
            try {
                Event event = eventManager.getEvent(eventTicker);
                if (event == null) {
                    // Try to get from interest list
                    event = interestList.getEvents().stream()
                            .filter(e -> e.getEventTicker().equals(eventTicker))
                            .findFirst()
                            .orElse(null);
                }

                if (event != null) {
                    EventStrategy strategy = createStrategyForEvent(constructor, event);
                    strategyManager.addStrategy(strategy);
                    created++;
                }
            } catch (Exception e) {
                log.error("Failed to create strategy for event {}: {}", eventTicker, e.getMessage());
            }
        }

        return created;
    }

    // ==================== Progress Listeners ====================

    /**
     * Add a progress listener.
     *
     * @param listener Consumer to receive progress messages
     */
    public void addProgressListener(Consumer<String> listener) {
        progressListeners.add(listener);
    }

    /**
     * Remove a progress listener.
     */
    public void removeProgressListener(Consumer<String> listener) {
        progressListeners.remove(listener);
    }

    private void progress(String message) {
        log.info(message);
        for (Consumer<String> listener : progressListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.error("Error in progress listener: {}", e.getMessage());
            }
        }
    }

    // ==================== Access ====================

    /**
     * Get the strategy manager (after launch).
     */
    public StrategyManager getStrategyManager() {
        return strategyManager;
    }

    /**
     * Get the risk checker.
     */
    public RiskChecker getRiskChecker() {
        return riskChecker;
    }

    /**
     * Get the event manager.
     */
    public EventManager getEventManager() {
        return eventManager;
    }

    /**
     * Get the event filter.
     */
    public EventFilter getEventFilter() {
        return eventFilter;
    }

    /**
     * Get the interest list (after launch).
     */
    public EventInterestList getInterestList() {
        return interestList;
    }

    /**
     * Get the configuration.
     */
    public StrategyConfig getConfig() {
        return config;
    }

    /**
     * Check if strategies have been launched.
     */
    public boolean isLaunched() {
        return launched;
    }

    /**
     * Check if launch is in progress.
     */
    public boolean isLaunching() {
        return launching;
    }

    // ==================== Lifecycle ====================

    /**
     * Set a transport lifecycle resource (e.g., FIX session manager) to be closed on shutdown.
     */
    public void setTransportLifecycle(AutoCloseable lifecycle) {
        this.transportLifecycle = lifecycle;
    }

    /**
     * Shutdown all strategies and resources.
     */
    public void shutdown() {
        if (strategyManager != null) {
            strategyManager.shutdownAll();
        }
        if (eventFilter != null) {
            eventFilter.shutdown();
        }
        if (transportLifecycle != null) {
            try {
                transportLifecycle.close();
            } catch (Exception e) {
                log.error("Error stopping transport: {}", e.getMessage());
            }
        }
        launched = false;
        launching = false;
    }

    // ==================== Result Class ====================

    /**
     * Result of a strategy launch operation.
     */
    public static class LaunchResult {
        private final boolean success;
        private final StrategyManager strategyManager;
        private final EventInterestList interestList;
        private final int strategiesCreated;
        private final String message;
        private final Exception exception;

        private LaunchResult(boolean success, StrategyManager strategyManager,
                             EventInterestList interestList, int strategiesCreated,
                             String message, Exception exception) {
            this.success = success;
            this.strategyManager = strategyManager;
            this.interestList = interestList;
            this.strategiesCreated = strategiesCreated;
            this.message = message;
            this.exception = exception;
        }

        public static LaunchResult success(StrategyManager manager, EventInterestList interestList,
                                           int created, String message) {
            return new LaunchResult(true, manager, interestList, created, message, null);
        }

        public static LaunchResult failure(String message) {
            return new LaunchResult(false, null, null, 0, message, null);
        }

        public static LaunchResult failure(String message, Exception e) {
            return new LaunchResult(false, null, null, 0, message, e);
        }

        public boolean isSuccess() {
            return success;
        }

        public StrategyManager getStrategyManager() {
            return strategyManager;
        }

        public EventInterestList getInterestList() {
            return interestList;
        }

        public int getStrategiesCreated() {
            return strategiesCreated;
        }

        public String getMessage() {
            return message;
        }

        public Exception getException() {
            return exception;
        }

        @Override
        public String toString() {
            if (success) {
                return "LaunchResult{success=true, strategies=" + strategiesCreated +
                        ", events=" + (interestList != null ? interestList.size() : 0) + "}";
            } else {
                return "LaunchResult{success=false, message='" + message + "'}";
            }
        }
    }
}
