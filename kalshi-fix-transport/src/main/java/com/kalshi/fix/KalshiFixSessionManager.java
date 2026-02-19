package com.kalshi.fix;

import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.EngineConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.engine.session.SessionStateListener;
import com.omnibridge.fix.message.FixVersion;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.network.SslConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the FIX session to Kalshi.
 * Owns the FixEngine and FixSession lifecycle, handles logon/logout and reconnection.
 */
public class KalshiFixSessionManager implements SessionStateListener {

    private static final Logger log = LoggerFactory.getLogger(KalshiFixSessionManager.class);

    private static final String SESSION_NAME = "KALSHI";
    private static final int MAX_TAG_NUMBER = 22000; // Kalshi uses custom tags up to 21009

    private final KalshiFixConfig config;
    private FixEngine engine;
    private FixSession session;
    private final AtomicReference<SessionState> currentState = new AtomicReference<>(SessionState.CREATED);
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<SessionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch logonLatch;

    public KalshiFixSessionManager(KalshiFixConfig config) {
        this.config = config;
    }

    /**
     * Start the FIX engine and initiate a session to Kalshi.
     */
    public void start() {
        log.info("Starting Kalshi FIX session manager: {}", config);

        // Build session config
        SessionConfig sessionConfig = SessionConfig.builder()
                .sessionName(SESSION_NAME)
                .fixVersion(FixVersion.FIX50SP2)
                .senderCompId(config.getSenderCompId())
                .targetCompId(config.getTargetCompId())
                .initiator()
                .host(config.getHost())
                .port(config.getPort())
                .heartbeatInterval(config.getHeartbeatInterval())
                .resetOnLogon(config.isResetOnLogon())
                .reconnectInterval(config.getReconnectInterval())
                .maxTagNumber(MAX_TAG_NUMBER)
                .sslConfig(buildSslConfig())
                .build();

        // Build engine config with temp persistence path
        String persistPath = Path.of(System.getProperty("java.io.tmpdir"), "kalshi-fix").toString();
        EngineConfig engineConfig = EngineConfig.builder()
                .persistencePath(persistPath)
                .addSession(sessionConfig)
                .build();

        // Create and start engine
        try {
            engine = new FixEngine(engineConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create FIX engine", e);
        }

        // Get the session and add listeners
        session = engine.getSession(SESSION_NAME);
        session.addStateListener(this);

        // Add registered message listeners
        for (MessageListener listener : messageListeners) {
            session.addMessageListener(listener);
        }

        logonLatch = new CountDownLatch(1);
        engine.start();

        log.info("FIX engine started, connecting to {}:{}", config.getHost(), config.getPort());
    }

    /**
     * Wait for logon to complete.
     *
     * @param timeoutSeconds Max seconds to wait
     * @return true if logged on within timeout
     */
    public boolean awaitLogon(int timeoutSeconds) {
        if (logonLatch == null) return false;
        try {
            return logonLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stop the FIX session and engine.
     */
    public void stop() {
        log.info("Stopping Kalshi FIX session manager");
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        session = null;
    }

    /**
     * Check if the FIX session is logged on and can send application messages.
     */
    public boolean isLoggedOn() {
        return currentState.get() == SessionState.LOGGED_ON;
    }

    /**
     * Get the underlying FIX session for sending messages.
     */
    public FixSession getSession() {
        return session;
    }

    /**
     * Add a message listener (must be called before start()).
     */
    public void addMessageListener(MessageListener listener) {
        messageListeners.add(listener);
        if (session != null) {
            session.addMessageListener(listener);
        }
    }

    /**
     * Add a state listener.
     */
    public void addStateListener(SessionStateListener listener) {
        stateListeners.add(listener);
    }

    // ==================== SessionStateListener ====================

    @Override
    public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
        currentState.set(newState);
        log.info("Kalshi FIX session state: {} -> {}", oldState, newState);
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionStateChange(session, oldState, newState);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onSessionCreated(FixSession session) {
        log.info("Kalshi FIX session created: {}", session);
    }

    @Override
    public void onSessionConnected(FixSession session) {
        log.info("Kalshi FIX session connected");
    }

    @Override
    public void onSessionLogon(FixSession session) {
        log.info("Kalshi FIX session logged on");
        currentState.set(SessionState.LOGGED_ON);
        if (logonLatch != null) {
            logonLatch.countDown();
        }
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionLogon(session);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onSessionLogout(FixSession session, String reason) {
        log.warn("Kalshi FIX session logged out: {}", reason);
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionLogout(session, reason);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onSessionDisconnected(FixSession session, Throwable cause) {
        log.warn("Kalshi FIX session disconnected: {}",
                cause != null ? cause.getMessage() : "unknown");
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionDisconnected(session, cause);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onSessionError(FixSession session, Throwable error) {
        log.error("Kalshi FIX session error: {}", error.getMessage(), error);
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionError(session, error);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    private SslConfig buildSslConfig() {
        if (!config.isSslEnabled()) {
            return SslConfig.disabled();
        }
        // Use default JVM trust store (no client auth, just TLS)
        return SslConfig.builder()
                .enabled(true)
                .protocol("TLSv1.3")
                .build();
    }
}
