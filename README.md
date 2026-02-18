# Online Betting Platform

A multi-module Java trading platform for [Kalshi](https://kalshi.com/) binary options markets with an extensible plug-and-play strategy framework, integrated risk management, and optional [E*TRADE](https://developer.etrade.com/) market data.

## Highlights

- **Plug-and-play strategy framework** — write a strategy class, drop its name into a config file, and the platform handles discovery, lifecycle, market subscriptions, and risk enforcement automatically
- **Live market data** via REST and WebSocket (orderbook snapshots, deltas, position updates, market lifecycle events)
- **Built-in risk management** with global limits and per-strategy overrides
- **External market data integration** — consume real-time stock quotes from E*TRADE (or other providers) alongside Kalshi event data
- **JavaFX UI** for live orderbook visualization, strategy management, order/position blotters, and risk configuration

## Architecture

```
betting-app                 Application layer: demo apps, UI, strategy implementations
    │
    ├── kalshi-java-client   Core library: Kalshi API client, strategy framework, risk engine
    ├── etrade-api           E*TRADE client: OAuth 1.0, INTRADAY quotes, subscriptions
    └── marketdata-api       Provider-agnostic interfaces (Quote, MarketDataManager)

etrade-sample-app           Standalone E*TRADE console demo
etrade-javafx-app           Standalone E*TRADE JavaFX demo
```

## Requirements

- Java 17+
- Maven 3.6+

## Quick Start

```bash
# Build everything
mvn clean install

# Run tests
mvn test
```

### API Credentials

**Kalshi** — generate an API key at [kalshi.com/settings/api](https://kalshi.com/settings/api). You'll get a Key ID and a private key PEM file. Configure them in `strategy.properties`:

```properties
api.keyId=your-key-id
api.privateKeyFile=path/to/private_key.pem
api.useDemo=true    # use demo environment for testing
```

Or via environment variables: `KALSHI_API_KEY_ID`, `KALSHI_PRIVATE_KEY_FILE`.

**E*TRADE** — register at the [E*TRADE Developer Portal](https://developer.etrade.com/) and configure `etrade-config.properties`:

```properties
consumer.key=your-consumer-key
consumer.secret=your-consumer-secret
use.sandbox=true
```

## Plug-and-Play Strategy Framework

The platform's core design principle is that **strategies are self-contained units of trading logic** that plug into the platform without modifying any framework code. The platform handles everything else: API connectivity, market data subscriptions, order routing, position tracking, and risk enforcement.

### How It Works

1. **Write a strategy class** that extends `EventStrategy` (or `TradingStrategy` for non-event strategies)
2. **Set the class name** in `strategy.properties`
3. **Run the app** — the `StrategyLauncher` discovers your class via reflection, creates instances per matched event, wires up all services, and manages the full lifecycle

### Strategy Lifecycle

```
StrategyLauncher reads strategy.properties
        │
        ▼
EventFilter scans series → filters events by date, category, title
        │
        ▼
For each matching event:
    Strategy class instantiated via reflection (constructor takes event ticker)
        │
        ▼
    initialize() wires in KalshiApi, OrderManager, PositionManager, MarketManager
        │
        ▼
    onInitialized() → loads event, filters markets via shouldTrackMarket()
        │
        ▼
    makeActive() → subscribes to market data, starts timer
        │
        ▼
    Running: onTimer(), onMarketDataUpdate(), onOrderCreated(), onPositionUpdated()...
        │
        ▼
    shutdown() → unsubscribes, stops timer, cleanup
```

### Writing a Strategy

Extend `EventStrategy` and override the hooks you need:

```java
public class MyStrategy extends EventStrategy {

    public MyStrategy(String eventTicker) {
        super(eventTicker);
    }

    // Optional: accept StrategyConfig for access to market filter settings
    public MyStrategy(String eventTicker, StrategyConfig config) {
        super(eventTicker);
    }

    @Override
    protected boolean shouldTrackMarket(Market market) {
        // Filter which markets within the event to track
        return "active".equalsIgnoreCase(market.getStatus())
            && market.getVolume24h() > 100;
    }

    @Override
    protected void onStrategyReady() {
        // Called after event loaded and markets filtered (still inactive)
        makeActive();  // subscribe to market data and start timer
    }

    @Override
    protected void onMarketDataUpdate(ManagedMarket market) {
        // Live orderbook update — react to price changes
        Integer bid = market.getBestYesBid();
        Integer ask = market.getBestYesAsk();
    }

    @Override
    public void onTimer() {
        // Called every N seconds — check conditions, place orders
        for (ManagedMarket m : getTrackedMarkets()) {
            if (m.getYesSpread() != null && m.getYesSpread() > 5) {
                buy(m.getTicker(), "yes", 1, m.getBestYesBid() + 1);
            }
        }
    }

    @Override
    public void onOrderCreated(Order order) { /* ... */ }

    @Override
    public void onPositionUpdated(Position position) { /* ... */ }
}
```

Then register it:

```properties
# strategy.properties
strategy.class=com.example.MyStrategy
series.tickers=KXINX,KXINXU
strategy.maxStrategies=10
strategy.timerIntervalSeconds=10
```

### Available Lifecycle Hooks

| Hook | When it fires |
|------|--------------|
| `onStrategyReady()` | After event loaded and markets filtered (inactive state) |
| `onActivated()` / `onDeactivated()` | When `makeActive()` / `makeInactive()` is called |
| `onTimer()` | Every N seconds (configurable) |
| `onMarketDataUpdate(ManagedMarket)` | Orderbook snapshot or delta received |
| `onMarketInfoUpdate(ManagedMarket)` | Market metadata updated via REST |
| `onOrderCreated/Modified/Removed(Order)` | Order lifecycle events |
| `onPositionOpened/Updated/Closed(Position)` | Position lifecycle events |
| `onMarketDataConnected/Disconnected()` | WebSocket connection state changes |
| `onShutdown()` | Application shutting down |

### What the Strategy Gets for Free

- **`getApi()`** — full Kalshi API access (series, events, markets, orders)
- **`getOrderService()`** — create, cancel, amend orders with `buy()`, `sell()`, `cancelOrder()` helpers
- **`getOrderManager()`** / **`getPositionManager()`** — live order and position state
- **`getMarketManager()`** — live orderbook data for subscribed markets
- **`getMarketDataManager()`** — external market data (E*TRADE quotes) if configured
- **`logActivity()`** / **`logTrade()`** — structured logging that feeds the UI activity panel
- **`setDisplayMarkets()`** — control which markets appear in the UI orderbook panel
- **`setMarketDataLabel()`** — display real-time info in the UI (e.g., "SPX: $5,832.50 +0.5%")

## Risk Management

Risk limits are enforced at the platform level — strategies cannot bypass them. Configure global limits and per-strategy overrides in `strategy.properties`:

```properties
# Global limits
risk.enabled=true
risk.maxOrderQuantity=50
risk.maxOrderNotional=2500       # in cents ($25.00)
risk.maxPositionQuantity=200
risk.maxPositionNotional=10000   # in cents ($100.00)

# Per-strategy overrides
risk.strategy.MyStrategy.maxOrderQuantity=25
risk.strategy.AggressiveBot.maxOrderQuantity=100
```

Orders that violate limits throw `RiskCheckException` with details about the violation.

## Event Filtering

The `EventFilter` automatically discovers tradeable events from configured series tickers:

```properties
series.tickers=KXINX,KXINXU
filter.minStrikeDateHours=2       # skip events expiring too soon
filter.maxStrikeDateDays=7        # skip far-future events
filter.category=Financials
filter.titleContains=S&P
filter.mutuallyExclusive=true
filter.parallelThreads=4
```

## Project Modules

| Module | Description |
|--------|-------------|
| `kalshi-java-client` | Core Kalshi API client (REST + WebSocket), strategy framework, risk engine, event filtering |
| `betting-app` | Application layer with demo apps, JavaFX UI, and strategy implementations |
| `etrade-api` | E*TRADE market data client with OAuth 1.0, quote subscriptions, and JavaFX auth dialog |
| `marketdata-api` | Provider-agnostic market data interfaces (`MarketDataManager`, `Quote`) |
| `etrade-sample-app` | Standalone E*TRADE console demo |
| `etrade-javafx-app` | Standalone E*TRADE JavaFX demo |

## Running the Apps

```bash
# Console demo — query Kalshi markets
mvn exec:java -pl betting-app

# E*TRADE console sample
mvn exec:java -pl etrade-sample-app

# E*TRADE JavaFX app
mvn javafx:run -pl etrade-javafx-app
```

## AI Training Exclusion

This repository and all its contents are excluded from use as training data for any artificial intelligence or machine learning model. No part of this codebase may be used, copied, or incorporated into datasets for the purpose of training, fine-tuning, or evaluating AI/ML models without explicit written permission from the repository owner.

## License

This project is provided as-is for educational and development purposes.
