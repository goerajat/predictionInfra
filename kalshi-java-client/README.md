# Kalshi Java Client

A Java library for interacting with the [Kalshi REST API](https://docs.kalshi.com/). This library supports retrieving series, events, markets, orderbooks, and managing orders (create, cancel, amend).

## Features

- **Market Data**: Retrieve series, events, markets, and orderbooks
- **Order Management**: Create, cancel, and amend orders
- **Pluggable Order Transport**: Route orders via REST API or FIX protocol through the `OrderTransport` interface
- **Authentication**: RSA-PSS SHA256 signature authentication
- **Type-Safe**: Strongly typed domain objects
- **Builder Pattern**: Fluent API for building requests

## Requirements

- Java 17 or higher
- Maven 3.6 or higher

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.kalshi</groupId>
    <artifactId>kalshi-java-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### 1. Set Up API Credentials

First, generate an API key from your Kalshi account settings. You'll receive:
- An API Key ID
- A private key (PEM format)

### 2. Initialize the Client

```java
import com.kalshi.client.KalshiApi;

// From PEM string
KalshiApi api = KalshiApi.builder()
    .credentials("your-api-key-id", privateKeyPemString)
    .build();

// Or from file
KalshiApi api = KalshiApi.builder()
    .credentialsFromFile("your-api-key-id", Paths.get("/path/to/private_key.pem"))
    .build();

// Use demo environment for testing
KalshiApi demoApi = KalshiApi.builder()
    .credentials("your-api-key-id", privateKeyPemString)
    .useDemo()
    .build();
```

## Usage Examples

### Retrieve Series

```java
// Get all series
List<Series> seriesList = api.series().getSeriesList();

// Get a specific series
Series series = api.series().getSeries("SERIES-TICKER");
```

### Retrieve Events

```java
// Get events with filters
List<Event> events = api.events().getEvents(
    EventQuery.builder()
        .seriesTicker("SERIES-TICKER")
        .status("open")
        .limit(100)
        .build()
);

// Get a specific event with nested markets
Event event = api.events().getEvent("EVENT-TICKER");
```

### Retrieve Markets

```java
// Get markets with filters
List<Market> markets = api.markets().getMarkets(
    MarketQuery.builder()
        .status("open")
        .limit(100)
        .build()
);

// Get a specific market
Market market = api.markets().getMarket("MARKET-TICKER");

// Get markets for an event
List<Market> eventMarkets = api.markets().getMarketsByEvent("EVENT-TICKER");
```

### Retrieve Orderbook

```java
// Get orderbook for a market
Orderbook orderbook = api.markets().getOrderbook("MARKET-TICKER");

// Get orderbook with depth limit
Orderbook orderbook = api.markets().getOrderbook("MARKET-TICKER", 10);

// Access orderbook data
Integer bestYesBid = orderbook.getBestYesBid();
Integer bestNoBid = orderbook.getBestNoBid();
long totalYesDepth = orderbook.getTotalYesDepth();
```

### Create Orders

```java
// Using the builder
Order order = api.orders().createOrder(
    CreateOrderRequest.builder()
        .ticker("MARKET-TICKER")
        .side(OrderSide.YES)
        .action(OrderAction.BUY)
        .count(10)
        .yesPrice(50)  // 50 cents
        .timeInForce(TimeInForce.GOOD_TILL_CANCELED)
        .build()
);

// Using convenience methods
Order buyYesOrder = api.orders().buyYes("MARKET-TICKER", 10, 50);
Order buyNoOrder = api.orders().buyNo("MARKET-TICKER", 5, 40);
Order sellYesOrder = api.orders().sellYes("MARKET-TICKER", 10, 55);
Order sellNoOrder = api.orders().sellNo("MARKET-TICKER", 5, 45);
```

### Cancel Orders

```java
// Cancel a single order
Order canceledOrder = api.orders().cancelOrder("ORDER-ID");

// Cancel multiple orders (up to 20)
api.orders().cancelOrders("ORDER-ID-1", "ORDER-ID-2", "ORDER-ID-3");

// Cancel multiple orders from a list
api.orders().cancelOrders(List.of("ORDER-ID-1", "ORDER-ID-2"));
```

### Amend Orders

```java
// Amend price and quantity
Order amendedOrder = api.orders().amendOrder("ORDER-ID",
    AmendOrderRequest.builder()
        .yesPrice(55)  // New price
        .count(15)     // New quantity
        .build()
);

// Amend just the price
Order amendedOrder = api.orders().amendOrderPrice("ORDER-ID", 55, true);

// Amend just the quantity
Order amendedOrder = api.orders().amendOrderQuantity("ORDER-ID", 15);
```

### Get Orders and Fills

```java
// Get open orders
List<Order> openOrders = api.orders().getOpenOrders();

// Get orders for a specific market
List<Order> marketOrders = api.orders().getOrdersByMarket("MARKET-TICKER");

// Get fills (executed trades)
List<Trade> fills = api.orders().getFills();

// Get fills for a specific market
List<Trade> marketFills = api.orders().getFillsByMarket("MARKET-TICKER");
```

## Order Transport

The `OrderService` routes all order operations (create, cancel, amend) through an `OrderTransport` interface. By default, it uses `RestOrderTransport` (HTTP API). An alternative transport can be plugged in at runtime — the `kalshi-fix-transport` module provides a FIX protocol implementation.

### OrderTransport Interface

```java
public interface OrderTransport {
    Order createOrder(CreateOrderRequest request);
    Order cancelOrder(String orderId);
    void cancelOrders(List<String> orderIds);
    Order amendOrder(String orderId, AmendOrderRequest request);
    boolean isAvailable();
    TransportType getType();  // REST or FIX
}
```

### Default Behavior (REST)

With no transport configured, `OrderService` uses `RestOrderTransport` internally. All existing code continues to work unchanged:

```java
// These all route through REST by default
api.orders().createOrder(request);
api.orders().buyYes("TICKER", 10, 50);
api.orders().cancelOrder("ORDER-ID");
api.orders().amendOrder("ORDER-ID", amendRequest);
```

### Configuring an Alternative Transport

```java
// Set a custom transport on the order service
api.orders().setOrderTransport(myCustomTransport);

// Orders now route through the custom transport (if available)
// Risk checking still happens in OrderService before delegation
api.orders().buyYes("TICKER", 10, 50);  // → myCustomTransport.createOrder(...)
```

When a custom transport is set, `OrderService` checks `transport.isAvailable()` before each call. If the transport is unavailable, it transparently falls back to the built-in REST transport.

### Built-in Transports

| Class | Type | Description |
|-------|------|-------------|
| `RestOrderTransport` | REST | HTTP calls via `KalshiClient` (default, always available) |
| `FixOrderTransport` | FIX | FIX NewOrderSingle/Cancel/Amend via persistent session (in `kalshi-fix-transport`) |
| `FallbackOrderTransport` | FIX/REST | Tries FIX first, falls back to REST on failure (in `kalshi-fix-transport`) |

### Implementing a Custom Transport

```java
public class MyTransport implements OrderTransport {

    @Override
    public Order createOrder(CreateOrderRequest request) {
        // Your order routing logic here
    }

    @Override
    public Order cancelOrder(String orderId) { /* ... */ }

    @Override
    public void cancelOrders(List<String> orderIds) { /* ... */ }

    @Override
    public Order amendOrder(String orderId, AmendOrderRequest request) { /* ... */ }

    @Override
    public boolean isAvailable() {
        return true;  // Return false to trigger REST fallback
    }

    @Override
    public TransportType getType() {
        return TransportType.FIX;  // or TransportType.REST
    }
}
```

## Domain Objects

### Series
Represents a template for recurring events (e.g., "Monthly Jobs Report").

### Event
Represents a real-world occurrence that can be traded on. Contains one or more markets.

### Market
Represents a specific binary outcome within an event. Has yes/no positions and prices.

### Orderbook
Shows all active bid orders for both yes and no sides of a market.

### Order
Represents an order to buy or sell contracts. Status can be: `resting`, `executed`, or `canceled`.

### Trade (Fill)
Represents an executed trade when an order is matched.

## Error Handling

The library throws specific exceptions for different error scenarios:

```java
try {
    Order order = api.orders().createOrder(request);
} catch (AuthenticationException e) {
    // Handle authentication errors (401, 403)
} catch (RateLimitException e) {
    // Handle rate limit exceeded (429)
    long retryAfter = e.getRetryAfterMs();
} catch (OrderException e) {
    // Handle order-specific errors (400, 422)
} catch (KalshiApiException e) {
    // Handle other API errors
    int statusCode = e.getStatusCode();
    String errorCode = e.getErrorCode();
}
```

## Configuration

```java
KalshiApi api = KalshiApi.builder()
    .credentials("api-key-id", privateKeyPem)
    .baseUrl("https://api.elections.kalshi.com/trade-api/v2")  // Custom base URL
    .connectTimeout(Duration.ofSeconds(10))
    .readTimeout(Duration.ofSeconds(30))
    .writeTimeout(Duration.ofSeconds(30))
    .build();
```

## Building from Source

```bash
cd kalshi-java-client
mvn clean install
```

## Running Tests

```bash
mvn test
```

## API Reference

For complete API documentation, visit:
- [Kalshi API Documentation](https://docs.kalshi.com/)
- [Kalshi API Help Center](https://help.kalshi.com/kalshi-api)

## License

This library is provided as-is for educational and development purposes.
