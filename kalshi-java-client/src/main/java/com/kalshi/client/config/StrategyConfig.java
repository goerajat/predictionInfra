package com.kalshi.client.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Configuration for strategy initialization and event filtering.
 * Can be loaded from a properties file or built programmatically.
 *
 * <p>Properties file format:</p>
 * <pre>
 * # Series to monitor (comma-separated)
 * series.tickers=KXINX,KXBTC,KXNDX
 *
 * # Strategy class name
 * strategy.class=com.kalshi.sample.strategy.IndexEventStrategy
 *
 * # Filter criteria
 * filter.minStrikeDateHours=2
 * filter.maxStrikeDateDays=7
 * filter.category=
 * filter.categories=
 * filter.titleContains=
 *
 * # Risk limits (global)
 * risk.enabled=true
 * risk.maxOrderQuantity=50
 * risk.maxOrderNotional=2500
 * risk.maxPositionQuantity=200
 * risk.maxPositionNotional=10000
 *
 * # Strategy-specific risk limits (optional)
 * risk.strategy.IndexEventStrategy.maxOrderQuantity=25
 * risk.strategy.IndexEventStrategy.maxOrderNotional=1250
 *
 * # Strategy settings
 * strategy.timerIntervalSeconds=10
 * strategy.maxStrategies=10
 *
 * # Parallel fetching
 * filter.parallelThreads=4
 * filter.fetchTimeoutSeconds=30
 * </pre>
 */
public class StrategyConfig {

    // Series configuration
    private List<String> seriesTickers = new ArrayList<>();

    // Strategy class
    private String strategyClassName;

    // Filter criteria
    private Duration minStrikeDateFromNow;
    private Duration maxStrikeDateFromNow;
    private String category;
    private List<String> categories;
    private String titleContains;
    private String titlePattern;
    private Boolean mutuallyExclusive;

    // Risk configuration
    private boolean riskEnabled = true;
    private Integer maxOrderQuantity;
    private Integer maxOrderNotional;
    private Integer maxPositionQuantity;
    private Integer maxPositionNotional;
    private Properties strategyRiskOverrides = new Properties();

    // Strategy settings
    private long timerIntervalSeconds = 5;
    private int maxStrategies = 50;

    // Filter settings
    private int parallelThreads = 4;
    private Duration fetchTimeout = Duration.ofSeconds(30);

    // API configuration (optional - can be overridden)
    private String apiKeyId;
    private String privateKeyFile;
    private boolean useDemo = false;

    // Market filter configuration
    private Integer marketFilterMinVolume24h;
    private Integer marketFilterMinHoursToExpiration;
    private Integer marketFilterMaxHoursUntilOpen;
    private Boolean marketFilterRequireActiveOrInitialized;
    private Integer wideSpreadThreshold;

    // Market data configuration (external data like E*TRADE)
    private String marketDataSymbol;

    // Transport mode: "rest", "fix", "fix-with-rest-fallback"
    private String transportMode = "rest";

    // Raw properties (needed for FIX config which lives in kalshi-fix-transport module)
    private Properties rawProperties;

    /**
     * Create an empty configuration.
     */
    public StrategyConfig() {
    }

    /**
     * Create a builder for StrategyConfig.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Load configuration from Properties.
     *
     * @param props Properties to load from
     * @return Configured StrategyConfig
     */
    public static StrategyConfig fromProperties(Properties props) {
        StrategyConfig config = new StrategyConfig();

        // Series tickers
        String tickers = props.getProperty("series.tickers", "");
        if (!tickers.isEmpty()) {
            for (String ticker : tickers.split(",")) {
                String trimmed = ticker.trim();
                if (!trimmed.isEmpty()) {
                    config.seriesTickers.add(trimmed);
                }
            }
        }

        // Strategy class
        config.strategyClassName = props.getProperty("strategy.class");

        // Filter criteria
        String minHours = props.getProperty("filter.minStrikeDateHours");
        if (minHours != null && !minHours.isEmpty()) {
            config.minStrikeDateFromNow = Duration.ofHours(Long.parseLong(minHours));
        }

        String maxDays = props.getProperty("filter.maxStrikeDateDays");
        if (maxDays != null && !maxDays.isEmpty()) {
            config.maxStrikeDateFromNow = Duration.ofDays(Long.parseLong(maxDays));
        }

        config.category = getPropertyOrNull(props, "filter.category");
        config.titleContains = getPropertyOrNull(props, "filter.titleContains");
        config.titlePattern = getPropertyOrNull(props, "filter.titlePattern");

        String categories = props.getProperty("filter.categories");
        if (categories != null && !categories.isEmpty()) {
            config.categories = new ArrayList<>();
            for (String cat : categories.split(",")) {
                String trimmed = cat.trim();
                if (!trimmed.isEmpty()) {
                    config.categories.add(trimmed);
                }
            }
        }

        String mutExcl = props.getProperty("filter.mutuallyExclusive");
        if (mutExcl != null && !mutExcl.isEmpty()) {
            config.mutuallyExclusive = Boolean.parseBoolean(mutExcl);
        }

        // Risk configuration
        config.riskEnabled = Boolean.parseBoolean(props.getProperty("risk.enabled", "true"));

        String maxOrderQty = props.getProperty("risk.maxOrderQuantity");
        if (maxOrderQty != null && !maxOrderQty.isEmpty()) {
            config.maxOrderQuantity = Integer.parseInt(maxOrderQty);
        }

        String maxOrderNot = props.getProperty("risk.maxOrderNotional");
        if (maxOrderNot != null && !maxOrderNot.isEmpty()) {
            config.maxOrderNotional = Integer.parseInt(maxOrderNot);
        }

        String maxPosQty = props.getProperty("risk.maxPositionQuantity");
        if (maxPosQty != null && !maxPosQty.isEmpty()) {
            config.maxPositionQuantity = Integer.parseInt(maxPosQty);
        }

        String maxPosNot = props.getProperty("risk.maxPositionNotional");
        if (maxPosNot != null && !maxPosNot.isEmpty()) {
            config.maxPositionNotional = Integer.parseInt(maxPosNot);
        }

        // Extract strategy-specific risk overrides
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("risk.strategy.")) {
                config.strategyRiskOverrides.setProperty(key, props.getProperty(key));
            }
        }

        // Strategy settings
        String timerInterval = props.getProperty("strategy.timerIntervalSeconds");
        if (timerInterval != null && !timerInterval.isEmpty()) {
            config.timerIntervalSeconds = Long.parseLong(timerInterval);
        }

        String maxStrats = props.getProperty("strategy.maxStrategies");
        if (maxStrats != null && !maxStrats.isEmpty()) {
            config.maxStrategies = Integer.parseInt(maxStrats);
        }

        // Filter settings
        String parallelThreads = props.getProperty("filter.parallelThreads");
        if (parallelThreads != null && !parallelThreads.isEmpty()) {
            config.parallelThreads = Integer.parseInt(parallelThreads);
        }

        String fetchTimeout = props.getProperty("filter.fetchTimeoutSeconds");
        if (fetchTimeout != null && !fetchTimeout.isEmpty()) {
            config.fetchTimeout = Duration.ofSeconds(Long.parseLong(fetchTimeout));
        }

        // API configuration
        config.apiKeyId = getPropertyOrNull(props, "api.keyId");
        config.privateKeyFile = getPropertyOrNull(props, "api.privateKeyFile");
        config.useDemo = Boolean.parseBoolean(props.getProperty("api.useDemo", "false"));

        // Market filter configuration
        String minVol = props.getProperty("marketFilter.minVolume24h");
        if (minVol != null && !minVol.isEmpty()) {
            config.marketFilterMinVolume24h = Integer.parseInt(minVol);
        }

        String minHoursExp = props.getProperty("marketFilter.minHoursToExpiration");
        if (minHoursExp != null && !minHoursExp.isEmpty()) {
            config.marketFilterMinHoursToExpiration = Integer.parseInt(minHoursExp);
        }

        String maxHoursOpen = props.getProperty("marketFilter.maxHoursUntilOpen");
        if (maxHoursOpen != null && !maxHoursOpen.isEmpty()) {
            config.marketFilterMaxHoursUntilOpen = Integer.parseInt(maxHoursOpen);
        }

        String requireActive = props.getProperty("marketFilter.requireActiveOrInitialized");
        if (requireActive != null && !requireActive.isEmpty()) {
            config.marketFilterRequireActiveOrInitialized = Boolean.parseBoolean(requireActive);
        }

        String wideSpread = props.getProperty("strategy.wideSpreadThreshold");
        if (wideSpread != null && !wideSpread.isEmpty()) {
            config.wideSpreadThreshold = Integer.parseInt(wideSpread);
        }

        // Market data configuration
        config.marketDataSymbol = getPropertyOrNull(props, "marketdata.symbol");

        // Transport mode
        config.transportMode = props.getProperty("transport.mode", "rest");

        // Store raw properties for FIX config (parsed by kalshi-fix-transport module)
        config.rawProperties = new Properties();
        config.rawProperties.putAll(props);

        return config;
    }

    private static String getPropertyOrNull(Properties props, String key) {
        String value = props.getProperty(key);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
    }

    // ==================== Getters ====================

    public List<String> getSeriesTickers() {
        return Collections.unmodifiableList(seriesTickers);
    }

    public String getStrategyClassName() {
        return strategyClassName;
    }

    public Duration getMinStrikeDateFromNow() {
        return minStrikeDateFromNow;
    }

    public Duration getMaxStrikeDateFromNow() {
        return maxStrikeDateFromNow;
    }

    public String getCategory() {
        return category;
    }

    public List<String> getCategories() {
        return categories;
    }

    public String getTitleContains() {
        return titleContains;
    }

    public String getTitlePattern() {
        return titlePattern;
    }

    public Boolean getMutuallyExclusive() {
        return mutuallyExclusive;
    }

    public boolean isRiskEnabled() {
        return riskEnabled;
    }

    public Integer getMaxOrderQuantity() {
        return maxOrderQuantity;
    }

    public Integer getMaxOrderNotional() {
        return maxOrderNotional;
    }

    public Integer getMaxPositionQuantity() {
        return maxPositionQuantity;
    }

    public Integer getMaxPositionNotional() {
        return maxPositionNotional;
    }

    public Properties getStrategyRiskOverrides() {
        return strategyRiskOverrides;
    }

    public long getTimerIntervalSeconds() {
        return timerIntervalSeconds;
    }

    public int getMaxStrategies() {
        return maxStrategies;
    }

    public int getParallelThreads() {
        return parallelThreads;
    }

    public Duration getFetchTimeout() {
        return fetchTimeout;
    }

    public String getApiKeyId() {
        return apiKeyId;
    }

    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    public boolean isUseDemo() {
        return useDemo;
    }

    public Integer getMarketFilterMinVolume24h() {
        return marketFilterMinVolume24h;
    }

    public Integer getMarketFilterMinHoursToExpiration() {
        return marketFilterMinHoursToExpiration;
    }

    public Integer getMarketFilterMaxHoursUntilOpen() {
        return marketFilterMaxHoursUntilOpen;
    }

    public Boolean getMarketFilterRequireActiveOrInitialized() {
        return marketFilterRequireActiveOrInitialized;
    }

    public Integer getWideSpreadThreshold() {
        return wideSpreadThreshold;
    }

    public String getMarketDataSymbol() {
        return marketDataSymbol;
    }

    public String getTransportMode() {
        return transportMode;
    }

    public Properties getRawProperties() {
        return rawProperties;
    }

    /**
     * Check if this configuration is valid for launching strategies.
     */
    public boolean isValid() {
        return !seriesTickers.isEmpty() && strategyClassName != null && !strategyClassName.isEmpty();
    }

    /**
     * Get validation errors if configuration is invalid.
     */
    public List<String> getValidationErrors() {
        List<String> errors = new ArrayList<>();
        if (seriesTickers.isEmpty()) {
            errors.add("No series tickers configured (series.tickers)");
        }
        if (strategyClassName == null || strategyClassName.isEmpty()) {
            errors.add("No strategy class configured (strategy.class)");
        }
        return errors;
    }

    @Override
    public String toString() {
        return "StrategyConfig{" +
                "seriesTickers=" + seriesTickers +
                ", strategyClassName='" + strategyClassName + '\'' +
                ", minStrikeDateFromNow=" + minStrikeDateFromNow +
                ", maxStrikeDateFromNow=" + maxStrikeDateFromNow +
                ", maxStrategies=" + maxStrategies +
                ", riskEnabled=" + riskEnabled +
                '}';
    }

    // ==================== Builder ====================

    public static class Builder {
        private final StrategyConfig config = new StrategyConfig();

        public Builder seriesTickers(List<String> tickers) {
            config.seriesTickers = new ArrayList<>(tickers);
            return this;
        }

        public Builder seriesTickers(String... tickers) {
            config.seriesTickers = new ArrayList<>();
            Collections.addAll(config.seriesTickers, tickers);
            return this;
        }

        public Builder strategyClassName(String className) {
            config.strategyClassName = className;
            return this;
        }

        public Builder minStrikeDateFromNow(Duration duration) {
            config.minStrikeDateFromNow = duration;
            return this;
        }

        public Builder maxStrikeDateFromNow(Duration duration) {
            config.maxStrikeDateFromNow = duration;
            return this;
        }

        public Builder category(String category) {
            config.category = category;
            return this;
        }

        public Builder titleContains(String text) {
            config.titleContains = text;
            return this;
        }

        public Builder riskEnabled(boolean enabled) {
            config.riskEnabled = enabled;
            return this;
        }

        public Builder maxOrderQuantity(int qty) {
            config.maxOrderQuantity = qty;
            return this;
        }

        public Builder maxOrderNotional(int notional) {
            config.maxOrderNotional = notional;
            return this;
        }

        public Builder maxPositionQuantity(int qty) {
            config.maxPositionQuantity = qty;
            return this;
        }

        public Builder maxPositionNotional(int notional) {
            config.maxPositionNotional = notional;
            return this;
        }

        public Builder timerIntervalSeconds(long seconds) {
            config.timerIntervalSeconds = seconds;
            return this;
        }

        public Builder maxStrategies(int max) {
            config.maxStrategies = max;
            return this;
        }

        public Builder apiKeyId(String keyId) {
            config.apiKeyId = keyId;
            return this;
        }

        public Builder privateKeyFile(String file) {
            config.privateKeyFile = file;
            return this;
        }

        public Builder useDemo(boolean useDemo) {
            config.useDemo = useDemo;
            return this;
        }

        public StrategyConfig build() {
            return config;
        }
    }
}
