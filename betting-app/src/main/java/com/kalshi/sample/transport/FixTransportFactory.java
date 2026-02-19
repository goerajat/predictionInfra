package com.kalshi.sample.transport;

import com.kalshi.client.KalshiApi;
import com.kalshi.client.config.StrategyConfig;
import com.kalshi.client.config.StrategyLauncher;
import com.kalshi.client.manager.OrderManager;
import com.kalshi.client.transport.OrderTransport;
import com.kalshi.client.transport.RestOrderTransport;
import com.kalshi.fix.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Factory for creating and wiring the FIX order transport into the application.
 * Called from application startup when transport.mode is set to "fix" or "fix-with-rest-fallback".
 */
public class FixTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(FixTransportFactory.class);

    /**
     * Configure the FIX transport based on strategy config.
     * Sets the appropriate OrderTransport on the API's OrderService.
     *
     * @param config       Strategy configuration (contains transport.mode and raw properties)
     * @param api          KalshiApi instance
     * @param launcher     StrategyLauncher (for lifecycle management)
     * @param orderManager OrderManager for real-time order update injection (optional)
     */
    public static void configure(StrategyConfig config, KalshiApi api,
                                  StrategyLauncher launcher, OrderManager orderManager) {
        String mode = config.getTransportMode();

        if ("rest".equalsIgnoreCase(mode)) {
            log.info("Transport mode: REST (default)");
            return;
        }

        Properties props = config.getRawProperties();
        if (props == null) {
            log.warn("No raw properties available for FIX config, falling back to REST");
            return;
        }

        KalshiFixConfig fixConfig = KalshiFixConfig.fromProperties(props);
        log.info("FIX transport config: {}", fixConfig);

        // Validate required FIX config
        if (fixConfig.getSenderCompId() == null || fixConfig.getSenderCompId().isEmpty()) {
            log.error("fix.senderCompId not set. FIX transport requires a Kalshi FIX API key UUID. Using REST.");
            return;
        }

        // Create session manager
        KalshiFixSessionManager sessionManager = new KalshiFixSessionManager(fixConfig);

        // Create tracker and FIX transport
        FixOrderStateTracker tracker = new FixOrderStateTracker(fixConfig.getOrderTimeoutSeconds());
        sessionManager.addMessageListener(tracker);

        // Wire OrderManager for real-time FIX ER-based order updates
        if (orderManager != null) {
            tracker.setOrderUpdateCallback(orderManager::injectOrderUpdate);
            log.info("OrderManager wired for real-time FIX order updates");
        }

        FixOrderTransport fixTransport = new FixOrderTransport(
                sessionManager, tracker, fixConfig.getOrderTimeoutSeconds());

        // Determine the final transport based on mode
        OrderTransport transport;
        if ("fix".equalsIgnoreCase(mode)) {
            transport = fixTransport;
            log.info("Transport mode: FIX only");
        } else {
            // fix-with-rest-fallback (default for non-REST)
            RestOrderTransport restTransport = new RestOrderTransport(api.getClient());
            transport = new FallbackOrderTransport(fixTransport, restTransport);
            log.info("Transport mode: FIX with REST fallback");
        }

        // Wire transport into the order service
        api.orders().setOrderTransport(transport);

        // Register lifecycle for shutdown
        launcher.setTransportLifecycle(() -> {
            log.info("Shutting down FIX transport...");
            sessionManager.stop();
        });

        // Start the FIX session
        sessionManager.start();

        // Wait briefly for logon
        if (sessionManager.awaitLogon(10)) {
            log.info("FIX session logged on successfully");
        } else {
            log.warn("FIX session logon timeout after 10s. " +
                    (mode.contains("fallback") ? "Will use REST fallback." : "FIX orders will fail until connected."));
        }
    }
}
