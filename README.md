# FYNXT Java Developer Take-Home Assignment
## Order Booking & Portfolio Service

A high-performance, production-ready stock trading desk backend built with **Spring Boot** and **Java 17+**. The service handles order booking, portfolio tracking by sector, concurrent order state transitions, and pure-Java sector overlap analysis against benchmark baskets.

---

## Table of Contents
1. [Architecture & Design Principles](#architecture--design-principles)
2. [API Endpoints Specification](#api-endpoints-specification)
3. [Concurrency & Thread-Safety Design](#concurrency--thread-safety-design)
4. [Pure Java Sector Overlap Engine](#pure-java-sector-overlap-engine)
5. [Database Schema & Constraints](#database-schema--constraints)
6. [Design Decisions & Trade-offs](#design-decisions--trade-offs)
7. [What Was Intentionally Skipped (With Reasons)](#what-was-intentionally-skipped-with-reasons)
8. [Testing Strategy & Coverage](#testing-strategy--coverage)
9. [How to Run & Test](#how-to-run--test)

---

## Architecture & Design Principles

The application adheres to **SOLID**, **DRY**, and **KISS** clean-code design principles:

```
com.fynxt.trading
├── config          # Application configuration (CORS, Jackson, DataSource)
├── controller      # REST Controllers (OrderController, PortfolioController)
├── dto             # Strongly-typed Request and Response models
│   ├── request     # PlaceOrderRequest, AddToPortfolioRequest
│   └── response    # OrderResponse, PortfolioResponse, SectorOverlapResponse, ErrorResponse
├── entity          # JPA Entities (Trader, Stock, TradeOrder, PortfolioHolding)
├── enums           # Domain enums (OrderSide, OrderStatus, BenchmarkBasket)
├── exception       # Domain exceptions & centralized GlobalExceptionHandler
├── repository      # Spring Data JPA repositories with JPQL & Projections
│   └── projection  # HoldingPositionProjection (avoids N+1 query problem)
├── service         # Domain service interfaces & implementations
│   └── impl        # OrderServiceImpl, PortfolioServiceImpl
└── util            # Pure Java calculation utilities (SectorOverlapCalculator)
```

### Key Architectural Standards
* **Decoupled Request & Response DTOs**: Internal entity representations are never leaked through REST APIs.
* **Explicit Constructor Injection**: All dependencies are injected via clean, standard constructors rather than `@RequiredArgsConstructor` or field injection.
* **Dual Routing Support**: Endpoints support both `/api/...` and root `/...` paths (`/api/orders` & `/orders`, `/api/portfolio` & `/portfolio`).
* **Clean Exception Handling**: Centralized [`GlobalExceptionHandler`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/main/java/com/fynxt/trading/exception/GlobalExceptionHandler.java) returns standard, RFC-compliant error payloads with timestamps and HTTP status codes.

---

## API Endpoints Specification

### 1. Place an Order
* **Endpoint**: `POST /api/orders` (or `POST /orders`)
* **Description**: Places a new BUY or SELL order in `PENDING` state.
* **Rules Enforced**:
  * Max 3 `PENDING` orders per trader at any time.
  * Rejects `SELL` orders if requested shares exceed current portfolio holding.
* **Request Body**:
```json
{
  "traderId": "T001",
  "stock": "AAPL",
  "sector": "TECH",
  "quantity": 50,
  "side": "BUY"
}
```
* **Response Body** (`201 Created`):
```json
{
  "orderId": 1,
  "traderId": "T001",
  "stock": "AAPL",
  "sector": "TECH",
  "quantity": 50,
  "side": "BUY",
  "status": "PENDING",
  "createdAt": "2026-10-13T00:15:00Z"
}
```

---

### 2. Fill an Order
* **Endpoint**: `POST /api/orders/{orderId}/fill` (or `POST /orders/{orderId}/fill`)
* **Description**: Transitions an order from `PENDING` $\rightarrow$ `FILLED` and atomically updates portfolio holdings (`BUY` increases shares, `SELL` decreases shares).
* **Rules Enforced**: Only `PENDING` orders can be filled; already `FILLED` or `CANCELLED` orders return `400 Bad Request`.
* **Response Body** (`200 OK`):
```json
{
  "orderId": 1,
  "traderId": "T001",
  "stock": "AAPL",
  "sector": "TECH",
  "quantity": 50,
  "side": "BUY",
  "status": "FILLED",
  "updatedAt": "2026-10-13T00:16:00Z"
}
```

---

### 3. Cancel an Order
* **Endpoint**: `POST /api/orders/{orderId}/cancel` (or `POST /orders/{orderId}/cancel`)
* **Description**: Transitions an order from `PENDING` $\rightarrow$ `CANCELLED`.
* **Rules Enforced**: Only `PENDING` orders can be cancelled; any other state returns descriptive error response.
* **Response Body** (`200 OK`):
```json
{
  "orderId": 1,
  "traderId": "T001",
  "stock": "AAPL",
  "sector": "TECH",
  "quantity": 50,
  "side": "BUY",
  "status": "CANCELLED",
  "updatedAt": "2026-10-13T00:17:00Z"
}
```

---

### 4. Get Portfolio
* **Endpoint**: `GET /api/portfolio/{traderId}` (or `GET /portfolio/{traderId}`)
* **Description**: Returns stock positions and aggregated sector breakdown for a trader.
* **Performance**: Uses a high-performance database projection (`HoldingPositionProjection`) in a single query, eliminating Hibernate hydration overhead and avoiding N+1 queries.
* **Response Body** (`200 OK`):
```json
{
  "traderId": "T001",
  "positions": {
    "AAPL": 150,
    "TSLA": 80
  },
  "sectorBreakdown": {
    "TECH": 230
  }
}
```

---

### 5. Sector Overlap Analysis
* **Endpoint**: `GET /api/portfolio/{traderId}/overlap` (or `GET /portfolio/{traderId}/overlap`)
* **Description**: Compares trader holdings against benchmark baskets (`TECH_HEAVY`, `FINANCE_HEAVY`, `BALANCED`).
* **Response Body** (`200 OK`):
```json
{
  "overlaps": [
    { "basket": "TECH_HEAVY", "overlap": "75.00%" },
    { "basket": "FINANCE_HEAVY", "overlap": "0.00%" },
    { "basket": "BALANCED", "overlap": "50.00%" }
  ],
  "dominantBasket": "TECH_HEAVY",
  "riskFlag": "HIGH"
}
```

---

### 6. Add to Portfolio
* **Endpoint**: `POST /api/portfolio/{traderId}/add` (or `POST /portfolio/{traderId}/add`)
* **Description**: Directly adds stock holdings to a trader's portfolio.
* **Request Body**:
```json
{
  "stock": "NVDA",
  "sector": "TECH",
  "quantity": 100
}
```
* **Response Body** (`200 OK`): Returns the updated portfolio summary.

---

## Concurrency & Thread-Safety Design

Under concurrent load where multiple traders and automated systems act at the same time:

1. **Enforcing Maximum 3 PENDING Orders (`SELECT ... FOR UPDATE`)**:
   - Order placement acquires a pessimistic write lock on the trader row (`findByIdForUpdate`).
   - Serializes concurrent order placement for that specific trader.
   - Prevents race conditions where two simultaneous threads check count (`2 < 3`) and both insert, exceeding the limit.
2. **Preventing Fill vs. Cancel Race Conditions**:
   - Both `fillOrder` and `cancelOrder` lock the specific `TradeOrder` row (`findByIdForUpdate`) inside a database transaction.
   - If Thread A attempts fill while Thread B attempts cancel, one acquires the lock first, updates status to `FILLED` (or `CANCELLED`), and commits. The second thread reads the terminal status and safely fails with `InvalidOrderStateException`.
3. **Atomic Holding Updates**:
   - Holding modifications execute within the same transactional boundary as the order fill, guaranteeing consistency without partial updates.

---

## Pure Java Sector Overlap Engine

The assignment strictly mandates: **"Sector overlap logic must be pure Java — no DB calls, no framework dependencies."**

Located in [`SectorOverlapCalculator.java`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/main/java/com/fynxt/trading/util/SectorOverlapCalculator.java):
* **Stateless Utility**: Declared as a pure static utility class with a private constructor.
* **Zero Framework Imports**: Relies exclusively on core Java SE standard library (`java.util.*`).
* **Overlap Formula**:
  $$\text{Overlap} = \left[ \frac{2 \times |\text{common stocks}|}{|\text{portfolio}| + |\text{basket}|} \right] \times 100$$
* **Risk Flag Evaluation**:
  - $\ge 60\%$ $\rightarrow$ `HIGH`
  - $\ge 40\%$ $\rightarrow$ `MEDIUM`
  - $< 40\%$ $\rightarrow$ `LOW`
* **Verified Worked Example**:
  - Portfolio: `[AAPL, TSLA, NVDA]`
  - vs `TECH_HEAVY`: $[2 \times 3 / (3 + 5)] \times 100 = 75.00\%$ $\rightarrow$ `HIGH`, dominant `TECH_HEAVY`
  - vs `BALANCED`: $[2 \times 2 / (3 + 5)] \times 100 = 50.00\%$
  - vs `FINANCE_HEAVY`: $[2 \times 0 / (3 + 5)] \times 100 = 0.00\%$

---

## Database Schema & Constraints

Located in [`src/main/resources/sql/schema.sql`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/main/resources/sql/schema.sql):

* **`traders`**: `trader_id` (PK), `created_at`.
* **`stocks`**: `stock_symbol` (PK), `stock_name`, `sector`.
* **`trade_orders`**:
  - `order_id` (PK generated identity)
  - `trader_id` (FK to `traders`)
  - `stock_symbol` (FK to `stocks`)
  - Constraints: `CHECK (quantity > 0)`, `CHECK (side IN ('BUY', 'SELL'))`, `CHECK (status IN ('PENDING', 'FILLED', 'CANCELLED'))`
  - Composite Index: `(trader_id, status)` for instant pending count lookups.
* **`portfolio_holdings`**:
  - `holding_id` (PK)
  - `trader_id` (FK), `stock_symbol` (FK)
  - Constraints: `UNIQUE (trader_id, stock_symbol)`, `CHECK (quantity >= 0)`
  - Index: `idx_portfolio_holdings_trader` on `trader_id`.

---

## Design Decisions & Trade-offs

| Decision | Chosen Approach | Alternative Considered | Rationale / Trade-off |
|---|---|---|---|
| **Pending Order Limit** | Pessimistic Lock on Trader row (`SELECT FOR UPDATE`) | Distributed Redis lock or DB Trigger | Simpler, acid-compliant within single DB transaction; zero external infra dependencies. |
| **Portfolio Read Performance** | Fast projection query (`findHoldingPositionsByTraderId`) | JPA Entity graph / hydration | Projections query only 3 scalar columns, avoiding entity hydration overhead and N+1 queries. |
| **Benchmark Baskets Storage** | Java Enum (`BenchmarkBasket`) | Database table with join queries | Assignment specifically defined fixed static benchmark baskets and mandated pure Java overlap calculation. |
| **Trader & Stock Onboarding** | Auto-onboard on first interaction if missing | Strict 404 rejection | Allows immediate testing without requiring prior seed data calls, while maintaining referential integrity. |

---

## What Was Intentionally Skipped (With Reasons)

1. **Authentication & Authorization (JWT / OAuth2)**:
   - *Reason*: Omitted to focus entirely on core business requirements (order lifecycle, portfolio balance calculations, overlap algorithms, and concurrency).
2. **Dynamic Benchmark Basket Administration**:
   - *Reason*: The assignment specified three fixed baskets (`TECH_HEAVY`, `FINANCE_HEAVY`, `BALANCED`). Moving these to a dynamic CRUD DB model would violate the "pure Java with no DB calls" requirement for overlap logic.
3. **Partial Order Fills**:
   - *Reason*: The assignment defines order states strictly as `PENDING -> FILLED` or `PENDING -> CANCELLED`.

---

## Testing Strategy & Coverage

The test suite contains **21 high-value unit tests** organized across three core dimensions:

1. **Concurrency Tests** ([`OrderServiceConcurrencyTest.java`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/test/java/com/fynxt/trading/service/OrderServiceConcurrencyTest.java)):
   - 5 simultaneous threads competing to place orders enforcing max 3 pending limit.
   - 2 simultaneous threads racing Fill vs Cancel on the same order.
2. **Business Logic Tests** ([`OrderServiceImplTest.java`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/test/java/com/fynxt/trading/service/OrderServiceImplTest.java), [`PortfolioServiceImplTest.java`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/test/java/com/fynxt/trading/service/PortfolioServiceImplTest.java), [`SectorOverlapCalculatorTest.java`](file:///c:/Users/helLO/IdeaProjects/trading-portfolio-service/src/test/java/com/fynxt/trading/util/SectorOverlapCalculatorTest.java)):
   - Order placement, holding increases on BUY fill, holding decreases on SELL fill, order cancellation.
   - Exact verification of the PDF Page 3 worked example.
3. **Edge Cases**:
   - Boundary checks for pending order limit.
   - Insufficient shares for SELL orders.
   - Disallowed state transitions (filling cancelled orders, cancelling filled orders).
   - Non-existent entity queries.
   - Empty, null, and non-benchmark stock portfolios in overlap calculation.

---

## How to Run & Test

### Prerequisites
* **Java 17+** (e.g. Amazon Corretto 17, Eclipse Temurin 17, OpenJDK 17)
* **Maven 3.8+** (or use included `mvnw`)

### 1. Run Unit Tests
```bash
# Using standard Maven
mvn test

# Or using Maven wrapper
.\mvnw.cmd test
```

### 2. Run the Application Locally
```bash
mvn spring-boot:run
```
The server will start on port `8080`.

### 3. Quick curl Verification
```bash
# Place BUY Order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"traderId":"T001","stock":"AAPL","sector":"TECH","quantity":50,"side":"BUY"}'

# Fill Order 1
curl -X POST http://localhost:8080/api/orders/1/fill

# Get Portfolio
curl -X GET http://localhost:8080/api/portfolio/T001

# Run Sector Overlap Analysis
curl -X GET http://localhost:8080/api/portfolio/T001/overlap
```
