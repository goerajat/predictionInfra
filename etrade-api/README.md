# E*TRADE API Client Library

A Java client library for accessing E*TRADE market data with OAuth 1.0 authentication, quote subscriptions, and callback support.

## Features

- **OAuth 1.0 Authentication** - Complete OAuth flow with HMAC-SHA1 signing
- **INTRADAY Quotes** - Real-time and delayed quote data
- **Subscription Support** - Poll-based quote subscriptions with configurable intervals
- **Callback System** - Receive quote updates via callbacks
- **JavaFX Support** - Built-in authorization dialog for JavaFX applications
- **Thread-Safe** - Safe for use in multi-threaded applications

## Quick Start

### 1. Configuration

Create `etrade-config.properties`:

```properties
consumer.key=your-consumer-key
consumer.secret=your-consumer-secret
use.sandbox=true
poll.interval.seconds=5
```

### 2. Basic Usage

```java
// Create manager from config file
MarketDataManager manager = MarketDataManager.fromPropertiesFile("etrade-config.properties");

// Authenticate (with console input)
manager.authenticate(authUrl -> {
    System.out.println("Visit: " + authUrl);
    return new Scanner(System.in).nextLine(); // verification code
});

// Subscribe to INTRADAY quotes
String subscriptionId = manager.subscribe(
    Arrays.asList("AAPL", "GOOGL", "MSFT"),
    quotes -> {
        for (QuoteData quote : quotes) {
            System.out.printf("%s: $%.2f%n", quote.getSymbol(), quote.getLastPrice());
        }
    },
    error -> System.err.println("Error: " + error.getMessage())
);

// One-time quote fetch
List<QuoteData> quotes = manager.getQuotes("AAPL", "TSLA");

// Unsubscribe when done
manager.unsubscribe(subscriptionId);

// Shutdown
manager.shutdown();
```

## MarketDataManager API

The `MarketDataManager` class is the main gateway for accessing E*TRADE market data.

### Factory Methods

| Method | Description |
|--------|-------------|
| `fromPropertiesFile(String path)` | Create from properties file path |
| `fromPropertiesFile(Path path)` | Create from Path object |
| `fromProperties(Properties props)` | Create from Properties object |

### Configuration Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `consumer.key` | Yes | - | E*TRADE API consumer key |
| `consumer.secret` | Yes | - | E*TRADE API consumer secret |
| `use.sandbox` | No | `true` | Use sandbox environment |
| `poll.interval.seconds` | No | `5` | Quote polling interval |

### Authentication

```java
// Authenticate with callback
manager.authenticate(authUrl -> {
    // Display authUrl to user
    // Return verification code
    return verifierCode;
});

// Check authentication status
boolean isAuth = manager.isAuthenticated();
```

### Subscription Methods

| Method | Description |
|--------|-------------|
| `subscribe(symbols, callback)` | Subscribe to quote updates |
| `subscribe(symbols, callback, errorCallback)` | Subscribe with error handling |
| `subscribe(callback, symbols...)` | Varargs version |
| `unsubscribe(subscriptionId)` | Cancel a subscription |
| `unsubscribeAll()` | Cancel all subscriptions |
| `getActiveSubscriptionCount()` | Get number of active subscriptions |
| `getActiveSubscriptionIds()` | Get all subscription IDs |
| `getSubscriptionSymbols(id)` | Get symbols for a subscription |

### Quote Methods

| Method | Description |
|--------|-------------|
| `getQuotes(symbols...)` | One-time quote fetch (varargs) |
| `getQuotes(Collection<String>)` | One-time quote fetch (collection) |

### Lifecycle Methods

| Method | Description |
|--------|-------------|
| `isRunning()` | Check if manager is running |
| `shutdown()` | Shutdown and release resources |
| `getClient()` | Get underlying ETradeClient |
| `getPollIntervalMs()` | Get poll interval in milliseconds |

## JavaFX Integration

For JavaFX applications, use the built-in `JavaFxAuthorizationDialog`:

```java
import com.betting.etrade.manager.JavaFxAuthorizationDialog;
import com.betting.etrade.manager.MarketDataManager;

public class MyJavaFxApp extends Application {

    private MarketDataManager marketDataManager;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Create manager
        marketDataManager = MarketDataManager.fromPropertiesFile("etrade-config.properties");

        // Authenticate using JavaFX dialog
        marketDataManager.authenticate(new JavaFxAuthorizationDialog(primaryStage));

        // Subscribe to quotes with UI updates
        marketDataManager.subscribe(
            Arrays.asList("AAPL", "GOOGL"),
            quotes -> Platform.runLater(() -> updateUI(quotes)),
            error -> Platform.runLater(() -> showError(error))
        );
    }

    @Override
    public void stop() {
        if (marketDataManager != null) {
            marketDataManager.shutdown();
        }
    }
}
```

### JavaFxAuthorizationDialog Features

| Feature | Description |
|---------|-------------|
| **Auto-open browser** | Automatically opens authorization URL in default browser |
| **Copy URL button** | Copies URL to clipboard for manual pasting |
| **Open Browser button** | Manually open URL if auto-open failed |
| **Verification code field** | Text input for the code from E*TRADE |
| **Modal dialog** | Blocks until user enters code or cancels |
| **Thread-safe** | Works from any thread, handles JavaFX threading |

### Constructor Options

```java
// Simple - no owner stage
new JavaFxAuthorizationDialog(null)

// With owner stage (dialog centers on parent)
new JavaFxAuthorizationDialog(primaryStage)

// Full control
new JavaFxAuthorizationDialog(
    primaryStage,           // owner stage
    "Custom Title",         // dialog title
    false                   // don't auto-open browser
)

// Static factory methods
JavaFxAuthorizationDialog.create()
JavaFxAuthorizationDialog.create(primaryStage)
```

## Callback Interfaces

### QuoteUpdateCallback

```java
@FunctionalInterface
public interface QuoteUpdateCallback {
    void onQuoteUpdate(List<QuoteData> quotes);
}
```

### MarketDataErrorCallback

```java
@FunctionalInterface
public interface MarketDataErrorCallback {
    void onError(Exception error);
}
```

### AuthorizationCallback

```java
public interface AuthorizationCallback {
    String onAuthorizationRequired(String authorizationUrl);
}
```

## Quote Data Model

### QuoteData

```java
QuoteData quote = quotes.get(0);

// Basic info
String symbol = quote.getSymbol();
String status = quote.getQuoteStatus();  // REALTIME, DELAYED, etc.
String dateTime = quote.getDateTime();
Double lastPrice = quote.getLastPrice();
boolean isRealTime = quote.isRealTime();

// Intraday details
IntradayQuote intraday = quote.getIntraday();
if (intraday != null) {
    Double last = intraday.getLastTrade();
    Double bid = intraday.getBid();
    Double ask = intraday.getAsk();
    Double change = intraday.getChangeClose();
    Double changePct = intraday.getChangeClosePercentage();
    Double high = intraday.getHigh();
    Double low = intraday.getLow();
    Long volume = intraday.getTotalVolume();
}
```

### Quote Status Values

| Status | Description |
|--------|-------------|
| `REALTIME` | Real-time quote |
| `DELAYED` | Delayed quote (15+ minutes) |
| `CLOSING` | Closing price |
| `EH_REALTIME` | Extended hours real-time |
| `EH_BEFORE_OPEN` | Extended hours before open |
| `EH_CLOSED` | Extended hours closed |

## Sample Applications

### Console Application (etrade-sample-app)

Interactive command-line application for testing:

```bash
cd etrade-sample-app
mvn exec:java
```

Features:
- Get single/multiple quotes
- Subscribe to quotes with callbacks
- Interactive menu

### JavaFX Application (etrade-javafx-app)

Full GUI application with live quote table:

```bash
cd etrade-javafx-app
mvn javafx:run
```

Features:
- OAuth authorization dialog
- Live updating quote table
- Color-coded gains/losses
- Activity log

#### JavaFX App Screenshot

```
┌─────────────────────────────────────────────────────────────────┐
│  E*TRADE Market Data Manager                                    │
│  ● Connected                Ready              [Connected]      │
├─────────────────────────────────────────────────────────────────┤
│  Symbols: [AAPL,GOOGL,MSFT,AMZN,TSLA] [Fetch] [Subscribe] [Stop]│
├─────────────────────────────────────────────────────────────────┤
│  Symbol │  Last    │ Change   │ Change%  │  Bid   │  Ask  │ Vol │
│  AAPL   │ $185.50  │ +$2.30   │ +1.25%   │ $185.48│$185.52│45.2M│
│  GOOGL  │ $142.80  │ -$0.95   │ -0.66%   │ $142.78│$142.82│12.4M│
│  MSFT   │ $378.20  │ +$4.10   │ +1.09%   │ $378.18│$378.22│18.9M│
├─────────────────────────────────────────────────────────────────┤
│  Activity Log:                                                  │
│  [17:30:01] Authentication successful!                          │
│  [17:30:05] Quote update: 5 quotes received                     │
│  [17:30:10] Quote update: 5 quotes received                     │
└─────────────────────────────────────────────────────────────────┘
```

## IntelliJ Run Configurations

Pre-configured run configurations are available:

| Configuration | Description |
|---------------|-------------|
| `ETradeApp` | Console sample application |
| `ETradeJavaFxApp` | JavaFX sample application |

## Project Structure

```
etrade-api/
├── src/main/java/com/betting/etrade/
│   ├── ETradeApiFactory.java           # Convenience factory
│   ├── client/
│   │   └── ETradeClient.java           # Main API client
│   ├── exception/
│   │   └── ETradeApiException.java     # Custom exception
│   ├── manager/
│   │   ├── MarketDataManager.java      # High-level gateway
│   │   ├── AuthorizationCallback.java  # Auth callback interface
│   │   ├── QuoteUpdateCallback.java    # Quote callback interface
│   │   ├── MarketDataErrorCallback.java# Error callback interface
│   │   └── JavaFxAuthorizationDialog.java # JavaFX auth dialog
│   ├── model/
│   │   ├── QuoteData.java              # Quote data wrapper
│   │   ├── QuoteResponse.java          # API response wrapper
│   │   ├── IntradayQuote.java          # Intraday quote details
│   │   ├── AllQuoteDetails.java        # Full quote details
│   │   ├── Product.java                # Security/product info
│   │   ├── DetailFlag.java             # Quote detail types
│   │   └── QuoteStatus.java            # Quote status enum
│   ├── oauth/
│   │   ├── OAuthConfig.java            # OAuth configuration
│   │   ├── OAuthService.java           # OAuth flow handler
│   │   ├── OAuthSigner.java            # HMAC-SHA1 signing
│   │   └── OAuthToken.java             # Token storage
│   └── subscription/
│       ├── QuoteCallback.java          # Legacy callback
│       ├── QuoteErrorCallback.java     # Legacy error callback
│       └── QuoteSubscription.java      # Legacy subscription
└── pom.xml
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| OkHttp | 4.12.0 | HTTP client |
| Jackson | 2.17.0 | JSON processing |
| SLF4J | 2.0.12 | Logging |
| JavaFX | 21 | GUI (optional) |

## Getting E*TRADE API Credentials

1. Visit [E*TRADE Developer Portal](https://developer.etrade.com)
2. Create a developer account
3. Register an application
4. Obtain consumer key and secret
5. For production, apply for production access

## Environment URLs

| Environment | Base URL |
|-------------|----------|
| Sandbox | `https://apisb.etrade.com` |
| Production | `https://api.etrade.com` |

## License

Part of the predictionInfra platform.
