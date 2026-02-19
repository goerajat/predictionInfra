# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

This is a Java 17 multi-module Maven project.

```bash
# Build entire project
mvn clean install

# Build a single module
mvn clean install -pl kalshi-java-client

# Run all tests
mvn test

# Run tests for a single module
mvn test -pl kalshi-java-client

# Run a single test class
mvn test -pl kalshi-java-client -Dtest=OrderbookTest

# Run a single test method
mvn test -pl kalshi-java-client -Dtest=OrderbookTest#testMethodName

# Run the E*TRADE console sample app
mvn exec:java -pl etrade-sample-app

# Run the E*TRADE JavaFX app
mvn javafx:run -pl etrade-javafx-app
```

No linter or formatter is configured. Tests use JUnit 5 with OkHttp MockWebServer for HTTP mocking.

## Architecture

Multi-module Maven project for trading on Kalshi (binary options) with optional E*TRADE stock market data integration.

### Module Dependency Graph

```
marketdata-api          (provider-agnostic interfaces: Quote, MarketDataManager)
    ↑
etrade-api              (E*TRADE client: OAuth 1.0, INTRADAY quotes, subscriptions)
    ↑
kalshi-java-client      (core: Kalshi REST + WebSocket client, strategy framework, risk mgmt)
    ↑
kalshi-fix-transport    (FIX protocol order transport using OmniBridge FIX engine)
    ↑
betting-app             (application: demo apps, JavaFX UI, strategy implementations)

etrade-sample-app       (standalone console demo for E*TRADE)
etrade-javafx-app       (standalone JavaFX demo for E*TRADE)
```

### kalshi-java-client — Core Library

Entry point: `KalshiApi` (builder pattern). Provides access to all services via `api.series()`, `api.events()`, `api.markets()`, `api.orders()`.

**Key layers:**
- **Services** (`SeriesService`, `EventService`, `MarketService`, `OrderService`) — REST API calls via `KalshiClient` (OkHttp + Jackson)
- **Managers** (`OrderManager`, `PositionManager`, `MarketManager`, `EventManager`) — stateful tracking with polling/WebSocket
- **WebSocket clients** (`OrderbookWebSocketClient`, `PositionWebSocketClient`, `MarketLifecycleWebSocketClient`) — live streaming
- **Strategy framework** (`TradingStrategy` → `EventStrategy`) — abstract base with lifecycle hooks (`onStart`, `onTimer`, `onMarketChange`, `onOrderChange`). Strategies are loaded by class name from `strategy.properties` and managed by `StrategyManager`/`StrategyLauncher`
- **Risk management** (`RiskChecker`, `RiskConfig`) — per-order/position limits with per-strategy overrides
- **Event filtering** (`EventFilter`, `EventFilterCriteria`) — filter events by series, date range, category, title pattern
- **Authentication** — RSA-PSS SHA256 signing via `KalshiAuthenticator`
- **Transport abstraction** (`OrderTransport`, `RestOrderTransport`) — pluggable order routing. `OrderService` delegates to the active transport when set via `setOrderTransport()`. Risk checking remains in `OrderService` before delegation.
- **Exception hierarchy** — `KalshiApiException` → `AuthenticationException`, `RateLimitException`, `OrderException`

### kalshi-fix-transport — FIX Order Transport

Integrates the OmniBridge FIX engine (from `C:\Users\rajat\omnibridge`) as an alternative order transport. Requires `mvn install -DskipTests -pl protocols/fix/message,protocols/fix/engine -am` from the omnibridge repo first.

- **`KalshiFixConfig`** — FIX session configuration (host, port, senderCompId, etc.) loaded from `strategy.properties`
- **`FixFieldMapper`** — bidirectional mapping between Kalshi REST model and FIX messages. Critical: Kalshi FIX uses Side 1=Buy Yes, Side 2=Sell No
- **`KalshiFixSessionManager`** — owns `FixEngine` + `FixSession`, handles FIXT.1.1/FIX50SP2 logon with TLS, custom tags up to 21009
- **`FixOrderTransport`** — implements `OrderTransport`, sends NewOrderSingle (D) and blocks for ExecutionReport via `CompletableFuture`
- **`FixOrderStateTracker`** — processes ExecutionReports, routes to pending futures, fires `OrderManager.injectOrderUpdate()` for live updates
- **`FallbackOrderTransport`** — tries FIX first, falls back to REST if unavailable

Transport modes (set `transport.mode` in `strategy.properties`):
- `rest` — REST only (default)
- `fix` — FIX only
- `fix-with-rest-fallback` — FIX primary, REST fallback

### betting-app — Application Layer

- `KalshiDemoApp` — console demo
- `OrderbookFxApp` — JavaFX live orderbook viewer
- `MultiEventStrategyDemo` — runs multiple strategies
- Strategy implementations: `IndexEventStrategy`, `ExampleStrategy`
- UI components: `StrategyManagerTab`, `OrderBlotterTable`, `PositionBlotterTable`, `RiskConfigPanel`
- FIX transport wiring: `FixTransportFactory` — configures and starts FIX session from strategy.properties
- Configuration: `strategy.properties` (series tickers, strategy class, filters, risk limits, API credentials, FIX transport) and `etrade-config.properties`

### API Environments

- **Kalshi Production**: `https://api.elections.kalshi.com/trade-api/v2`
- **Kalshi Demo**: `https://demo-api.kalshi.co/trade-api/v2` (set `api.useDemo=true` or call `.useDemo()` on builder)
- **E*TRADE Sandbox**: `https://apisb.etrade.com`
- **E*TRADE Production**: `https://api.etrade.com`

### FIX Environments

- **Kalshi FIX Production**: `fix.elections.kalshi.com:8228` (no retransmit) or `:8230` (with retransmit)
- **Kalshi FIX Demo**: `fix.demo.kalshi.co` (same ports)
- **TargetCompID**: `KalshiNR` (port 8228) or `KalshiRT` (port 8230)

### Key Dependencies

OkHttp 4.12.0 (HTTP), Jackson 2.17.0 (JSON), SLF4J 2.0.12 + Logback (logging), JavaFX 21 (GUI, optional), JUnit 5.10.2 (testing), OmniBridge FIX Engine 1.0.0-SNAPSHOT (FIX transport).

## Important Notes

- API credentials (PEM keys, key IDs) are configured in `strategy.properties` and `etrade-config.properties`. These can also be set via environment variables `KALSHI_API_KEY_ID` and `KALSHI_PRIVATE_KEY_FILE`.
- Prices in Kalshi are in cents (e.g., 50 = $0.50). Risk limits (`maxOrderNotional`, etc.) are also in cents.
- New strategies extend `EventStrategy`, implement lifecycle hooks, and are registered by fully-qualified class name in `strategy.properties`.
