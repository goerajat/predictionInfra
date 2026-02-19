package com.kalshi.fix;

import java.util.Properties;

/**
 * Configuration for the Kalshi FIX transport.
 * Can be loaded from strategy.properties or built programmatically.
 */
public class KalshiFixConfig {

    private static final String PROD_HOST = "fix.elections.kalshi.com";
    private static final String DEMO_HOST = "fix.demo.kalshi.co";
    private static final int DEFAULT_PORT = 8228;
    private static final String DEFAULT_TARGET_COMP_ID = "KalshiNR";
    private static final String DEFAULT_BEGIN_STRING = "FIXT.1.1";
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 30;
    private static final int DEFAULT_RECONNECT_INTERVAL = 5;
    private static final int DEFAULT_ORDER_TIMEOUT = 5;

    private String host;
    private int port = DEFAULT_PORT;
    private String senderCompId;
    private String targetCompId = DEFAULT_TARGET_COMP_ID;
    private String beginString = DEFAULT_BEGIN_STRING;
    private int heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private boolean resetOnLogon = true;
    private int reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    private boolean sslEnabled = true;
    private int orderTimeoutSeconds = DEFAULT_ORDER_TIMEOUT;
    private boolean useDemo = false;
    private String transportMode = "fix-with-rest-fallback";

    public KalshiFixConfig() {
    }

    /**
     * Load FIX configuration from Properties.
     */
    public static KalshiFixConfig fromProperties(Properties props) {
        KalshiFixConfig config = new KalshiFixConfig();

        config.useDemo = Boolean.parseBoolean(props.getProperty("fix.useDemo",
                props.getProperty("api.useDemo", "false")));
        config.host = props.getProperty("fix.host",
                config.useDemo ? DEMO_HOST : PROD_HOST);
        config.port = Integer.parseInt(props.getProperty("fix.port",
                String.valueOf(DEFAULT_PORT)));
        config.senderCompId = props.getProperty("fix.senderCompId",
                props.getProperty("api.keyId", ""));
        config.targetCompId = props.getProperty("fix.targetCompId", DEFAULT_TARGET_COMP_ID);
        config.beginString = props.getProperty("fix.beginString", DEFAULT_BEGIN_STRING);
        config.heartbeatInterval = Integer.parseInt(props.getProperty("fix.heartbeatInterval",
                String.valueOf(DEFAULT_HEARTBEAT_INTERVAL)));
        config.resetOnLogon = Boolean.parseBoolean(props.getProperty("fix.resetOnLogon", "true"));
        config.reconnectInterval = Integer.parseInt(props.getProperty("fix.reconnectInterval",
                String.valueOf(DEFAULT_RECONNECT_INTERVAL)));
        config.sslEnabled = Boolean.parseBoolean(props.getProperty("fix.ssl.enabled", "true"));
        config.orderTimeoutSeconds = Integer.parseInt(props.getProperty("fix.orderTimeoutSeconds",
                String.valueOf(DEFAULT_ORDER_TIMEOUT)));
        config.transportMode = props.getProperty("transport.mode", "fix-with-rest-fallback");

        return config;
    }

    // Getters
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getSenderCompId() { return senderCompId; }
    public String getTargetCompId() { return targetCompId; }
    public String getBeginString() { return beginString; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public boolean isResetOnLogon() { return resetOnLogon; }
    public int getReconnectInterval() { return reconnectInterval; }
    public boolean isSslEnabled() { return sslEnabled; }
    public int getOrderTimeoutSeconds() { return orderTimeoutSeconds; }
    public boolean isUseDemo() { return useDemo; }
    public String getTransportMode() { return transportMode; }

    // Setters for programmatic use
    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public void setSenderCompId(String senderCompId) { this.senderCompId = senderCompId; }
    public void setTargetCompId(String targetCompId) { this.targetCompId = targetCompId; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public void setResetOnLogon(boolean resetOnLogon) { this.resetOnLogon = resetOnLogon; }
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }
    public void setOrderTimeoutSeconds(int orderTimeoutSeconds) { this.orderTimeoutSeconds = orderTimeoutSeconds; }
    public void setUseDemo(boolean useDemo) { this.useDemo = useDemo; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }

    @Override
    public String toString() {
        return "KalshiFixConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", senderCompId='" + (senderCompId != null ? senderCompId.substring(0, Math.min(8, senderCompId.length())) + "..." : "null") + '\'' +
                ", targetCompId='" + targetCompId + '\'' +
                ", heartbeatInterval=" + heartbeatInterval +
                ", sslEnabled=" + sslEnabled +
                ", transportMode='" + transportMode + '\'' +
                '}';
    }
}
