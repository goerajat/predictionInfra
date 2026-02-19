# Online Betting Platform

A multi-module Java trading platform for [Kalshi](https://kalshi.com/) binary options markets with an extensible plug-and-play strategy framework, integrated risk management, and optional [E*TRADE](https://developer.etrade.com/) market data.

## Highlights

- **Plug-and-play strategy framework** — write a strategy class, drop its name into a config file, and the platform handles discovery, lifecycle, market subscriptions, and risk enforcement automatically
- **Dual-transport order routing** — REST API and FIX protocol (FIXT.1.1 / FIX 5.0 SP2) with automatic failover
- **Live market data** via REST and WebSocket (orderbook snapshots, deltas, position updates, market lifecycle events)
- **Built-in risk management** with global limits and per-strategy overrides
- **External market data integration** — consume real-time stock quotes from E*TRADE (or other providers) alongside Kalshi event data
- **JavaFX UI** for live orderbook visualization, strategy management, order/position blotters, and risk configuration

## Screenshots

### Strategy Manager

The main dashboard lists all discovered strategies with their event tickers, market counts, and close times. Strategies can be individually activated/deactivated or controlled in bulk. The bottom activity log streams timestamped system events (initialization, authentication, strategy creation).

![Strategy Manager](assets/StrategyManager.png)

### Strategy Detail Dialog

Clicking a strategy opens its detail view. The header shows the event title, live external market data (SPX price), strategy type, and running status. Below that: an activity log with per-strategy events, order and position blotters, and a scrollable row of live market orderbooks sorted by volume with bid/ask depth at each price level.

![Strategy Detail Dialog](assets/StrategyDialog.png)

### External Market Data

The Market Data tab connects to E\*TRADE for real-time stock quotes. Enter ticker symbols to subscribe, and the table streams live price, change, bid/ask, and volume. Strategies consume this data via `getMarketDataManager()` to inform trading decisions on Kalshi markets.

![External Market Data](assets/MarketData.png)

## Architecture

```
betting-app                     Application layer: demo apps, UI, strategy implementations
    │
    ├── kalshi-fix-transport    FIX protocol transport for low-latency order placement
    ├── kalshi-java-client      Core library: Kalshi API client, strategy framework, risk engine
    ├── etrade-api              E*TRADE client: OAuth 1.0, INTRADAY quotes, subscriptions
    └── marketdata-api          Provider-agnostic interfaces (Quote, MarketDataManager)

etrade-sample-app              Standalone E*TRADE console demo
etrade-javafx-app              Standalone E*TRADE JavaFX demo
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

## Order Transport

Orders are routed through a pluggable `OrderTransport` interface. Strategies call `buy()`, `sell()`, `cancelOrder()`, and `amendOrder()` exactly as before — the transport layer is completely transparent.

### Transport Modes

| Mode | Description |
|------|-------------|
| `rest` | REST API only (default). All orders sent via HTTPS. |
| `fix` | FIX protocol only. Orders sent as FIX messages over a persistent TLS session. Fails if the FIX session is disconnected. |
| `fix-with-rest-fallback` | FIX when connected, automatic REST fallback when FIX is unavailable or errors occur. |

### How It Works

```
Strategy.buy() / sell() / cancelOrder() / amendOrder()
    │
    ▼
OrderService — risk checks (unchanged)
    │
    ▼
OrderTransport (interface)
    ├── RestOrderTransport      HTTP POST/DELETE via KalshiClient
    ├── FixOrderTransport       FIX NewOrderSingle (D) → ExecutionReport (8)
    └── FallbackOrderTransport  Tries FIX first, falls back to REST
```

When using FIX transport, ExecutionReports are parsed back into the same `Order` model used by REST, so strategies and the `OrderManager` receive identical objects regardless of transport. FIX ExecutionReports also feed directly into `OrderManager.injectOrderUpdate()`, providing sub-millisecond order state callbacks instead of waiting for the 5-second REST polling cycle.

### FIX Protocol Details

- **Protocol**: FIXT.1.1 / FIX 5.0 SP2
- **Production**: `fix.elections.kalshi.com:8228` (no retransmit) or `:8230` (with retransmit)
- **Demo**: `fix.demo.kalshi.co` (same ports)
- **TLS required** — plain TCP connections are rejected
- **Supported operations**: NewOrderSingle (D), OrderCancelRequest (F), OrderCancelReplaceRequest (G)
- **Side encoding**: `1` = Buy Yes, `2` = Sell No (the FIX layer handles mapping from Kalshi's yes/no + buy/sell model)

### Configuration

Set the transport mode and FIX session properties in `strategy.properties`:

```properties
# Transport mode (default: rest)
transport.mode=fix-with-rest-fallback

# FIX session settings
fix.senderCompId=your-api-key-uuid       # Required: your Kalshi FIX API key
fix.host=fix.elections.kalshi.com         # Default for production
fix.port=8228                             # 8228 = no retransmit, 8230 = with retransmit
fix.targetCompId=KalshiNR                 # KalshiNR for port 8228, KalshiRT for 8230
fix.heartbeatInterval=30                  # Heartbeat in seconds
fix.ssl.enabled=true                      # TLS (required by Kalshi)
fix.orderTimeoutSeconds=5                 # Max wait for ExecutionReport
fix.useDemo=false                         # true → connects to fix.demo.kalshi.co
```

The `fix.senderCompId` is the same UUID as your Kalshi API key. If omitted, the platform falls back to REST with a warning.

## Project Modules

| Module | Description |
|--------|-------------|
| `kalshi-java-client` | Core Kalshi API client (REST + WebSocket), strategy framework, risk engine, order transport abstraction |
| `kalshi-fix-transport` | FIX protocol transport — session management, FIX message encoding/parsing, fallback logic |
| `betting-app` | Application layer with demo apps, JavaFX UI, strategy implementations, and FIX transport wiring |
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
