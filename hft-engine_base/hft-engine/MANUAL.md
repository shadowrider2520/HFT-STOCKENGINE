# HFT Order Matching Engine — Complete Project Manual

**Project:** In-memory stock-exchange matching engine with price-time priority, a
browser trading terminal, a console demo and a container image.
**Language:** Java 17+ (JDK only — no Maven, Gradle, Spring or any third-party JAR).
**Team:** Naren Kaarthi, Giri Prasath, Mithun Prasath K (lead), Gathin.

This document is self-contained. It assumes nothing except a machine with a JDK
and a copy of this folder, and it is enough to understand, build, run, test,
demonstrate, deploy and defend the project. If you read only one section before
an evaluation, read **§0** and **§16**.

---

## Contents

0. [Five-minute cheat sheet](#0-five-minute-cheat-sheet)
1. [Abstract (ready to paste into a report)](#1-abstract)
2. [Problem statement, objectives and scope](#2-problem-statement-objectives-and-scope)
3. [System architecture](#3-system-architecture)
4. [Source map — which file does what](#4-source-map)
5. [Data structures and why they were chosen](#5-data-structures-and-why-they-were-chosen)
6. [Algorithms, in pseudocode](#6-algorithms-in-pseudocode)
7. [Class reference](#7-class-reference)
8. [Data formats and persistence](#8-data-formats-and-persistence)
9. [HTTP API reference](#9-http-api-reference)
10. [Web interface](#10-web-interface)
11. [Build and run anywhere](#11-build-and-run-anywhere)
12. [Verification: tests and benchmark](#12-verification-tests-and-benchmark)
13. [Edge cases and failure behaviour](#13-edge-cases-and-failure-behaviour)
14. [Base version vs fixed version](#14-base-version-vs-fixed-version)
15. [Limitations and future work](#15-limitations-and-future-work)
16. [Viva / interview question bank](#16-viva--interview-question-bank)
17. [Troubleshooting](#17-troubleshooting)
18. [Evaluation demo script](#18-evaluation-demo-script)
19. [Glossary](#19-glossary)

---

## 0. Five-minute cheat sheet

```
JDK 17+ required.  No build tool.  No internet needed.

compile :  mkdir -p out && javac -d out src/*.java src/tests/*.java
run     :  java -cp out Main                 # http://localhost:8080
port    :  PORT=9000 java -cp out Main
no demo :  java -cp out Main --no-seed
console :  java -cp out Main demo
tests   :  java -cp out QueueTest   (33 checks)
           java -cp out TreapTest   (43 checks)
           java -cp out MatchingTest(47 checks)
bench   :  java -Xmx1g -cp out Benchmark          # 1,000,000 orders
           java -cp out Benchmark 200000          # quick run
docker  :  docker build -t hft-engine . && docker run --rm -p 8080:8080 hft-engine
```

Three sentences that describe the whole design:

> Prices live in a **Treap** (randomised balanced BST) so a trending market
> cannot degenerate the ladder into a linked list. Orders at one price live in a
> **circular array queue** so time priority is O(1). A single synchronized
> **matching loop** crosses the best bid against the best ask until they no
> longer overlap, printing every execution at the resting order's price.

The one number that justifies the design: on 1,000,000 strictly ascending keys a
plain BST reaches height **1,000,000** (a linked list); the Treap reaches **52**,
about 2.6× the ideal `log2(1,000,000) = 20`.

---

## 1. Abstract

> **Design and implementation of a price-time priority matching engine using a
> randomised balanced search tree**
>
> Electronic exchanges must match buy and sell orders in strict price-time
> priority while sustaining high message rates. The central data-structure
> problem is that the price ladder needs ordered operations — insert a price
> level, delete an emptied level, find the best price, find an exact price — and
> the obvious structure, a binary search tree, degrades to O(N) per operation
> when keys arrive sorted. Market prices do exactly that: they trend. This
> project implements a complete limit-order matching engine in Java with no
> external libraries, using a Treap for each side of each order book. A Treap is
> a binary search tree on price that simultaneously maintains a max-heap on a
> random priority drawn at node creation; the tree's shape therefore depends on
> the random priorities and not on insertion order, giving expected O(log N)
> operations even for strictly ascending input. Orders resting at the same price
> are held in an array-backed circular queue, giving O(1) enqueue, dequeue and
> peek and preserving strict time priority; the queue's fixed capacity (1,024
> orders per level) models an exchange refusing a saturated price level. A single
> synchronized matching loop crosses the best bid against the best ask, supports
> partial fills, multi-level sweeps and price improvement, and deletes a level
> the instant it empties. The system is exposed through a hand-written HTTP API
> over the JDK's built-in server, a browser trading terminal (candles, depth,
> live tree view, trade tape, event timeline), a console demo, a CSV persistence
> layer and a Docker image. Measured on one million simulated orders the engine
> sustains roughly 0.9–1.3 million orders per second; on one million ascending
> prices the Treap height is 52 against 1,000,000 for an unbalanced baseline,
> empirically confirming the randomised-balancing argument. The work
> demonstrates that an expected-case balanced tree plus an array ring is enough
> for a correct, auditable and fast matching core, and it states plainly where
> the design would need to change for production use: per-symbol locking,
> worst-case rather than expected balancing, and durable storage.

**Keywords:** matching engine, limit order book, price-time priority, Treap,
randomised binary search tree, circular queue, amortised analysis, Java.

Shorter version (if the form limits you to ~120 words):

> A limit-order matching engine written in Java with no external libraries. Each
> order book holds two price ladders implemented as Treaps — binary search trees
> on price that also maintain a max-heap on random priorities — so that expected
> O(log N) insert, delete and best-price lookup survive the trending, nearly
> sorted price sequences that degenerate an ordinary BST. Orders at one price sit
> in an array-backed circular queue for O(1) FIFO, giving strict time priority.
> A single synchronized loop matches best bid against best ask with partial
> fills, multi-level sweeps and price improvement. The engine is served over a
> hand-written HTTP API with a browser terminal, CSV persistence and a Docker
> image, and is validated by 123 assertions plus a one-million-order benchmark in
> which the Treap stays logarithmic (height 52) while a plain BST degenerates to
> height 1,000,000.

---

## 2. Problem statement, objectives and scope

### 2.1 Problem statement

An exchange receives limit orders — "buy 50 units at no more than 100" — and must
continuously match compatible buy and sell orders. Two rules define the result:

1. **Price priority** — the best (highest) bid trades before any worse bid, the
   best (lowest) ask before any worse ask.
2. **Time priority** — at the same price, the order that arrived first trades
   first.

The implementation must answer, on *every* incoming order: what is the best bid,
what is the best ask, do they cross, and which order is first in line at that
price. A naive implementation answers these with a scan, which is O(N) per order;
the project's purpose is to show that the right pair of data structures makes
each of them O(log N) or O(1) — and to prove it by measurement rather than
assertion.

### 2.2 Objectives

| # | Objective | Where it is met |
|---|---|---|
| O1 | Correct price-time priority matching | `MatchingController.match`, `MatchingTest` |
| O2 | Expected O(log N) price-level operations on adversarial input | `Treap`, `Benchmark` part (c) |
| O3 | O(1) FIFO within a price level | `CircularQueue`, `QueueTest` |
| O4 | Partial fills, multi-level sweeps, price improvement | `MatchingController`, `MatchingTest` |
| O5 | Validate all external input; never crash on bad data | `OrderGateway` |
| O6 | Persist history across restarts | `TradeStore` |
| O7 | Visualise live engine state and structure changes | `web/` + `/api/*` |
| O8 | Reproducible measurements | seeded `OrderSimulator`, `Benchmark` |
| O9 | Zero third-party dependencies | whole project |

### 2.3 In scope

- Limit orders only, both sides, many symbols, one independent book per symbol.
- Integer tick prices, integer quantities.
- Price-time priority, partial fills, sweeps, price improvement.
- In-memory state, CSV history, browser UI, HTTP API, Docker image.
- Live reference price from a public WebSocket, with automatic fallback.

### 2.4 Out of scope (deliberate)

Market/stop/iceberg/IOC orders, fees, risk limits, settlement, user accounts,
fractional prices, multi-node replication, self-trade prevention, order
cancellation/replacement. §15 states these again with the reason.

### 2.5 Constraints

- **No external libraries.** Only the Java standard library. This is why JSON is
  assembled by hand, the HTTP server is `com.sun.net.httpserver`, and the charts
  are hand-drawn SVG/canvas.
- **JDK 17 or newer** (`String.isBlank`, `HttpClient`, `record`-era APIs are
  used; nothing newer than 17 is required).
- Runs offline. The live price feed is optional and failure is a supported
  configuration.

---

## 3. System architecture

### 3.1 Layers

```
                       ┌──────────────────────────────────────────┐
  browser              │  web/index.html  web/app.js  web/charts.js│
  (vanilla JS)         │  web/style.css   web/landing.html         │
                       └───────────────┬──────────────────────────┘
                                       │  HTTP/JSON  (poll every 0.5–1 s)
                       ┌───────────────▼──────────────────────────┐
  presentation         │  WebServer  (com.sun.net.httpserver)       │
                       │  8-thread pool · static files · CORS      │
                       └───────────────┬──────────────────────────┘
                       ┌───────────────▼──────────────────────────┐
  admission            │  OrderGateway                             │
                       │  parse · validate · stamp arrival time     │
                       └───────────────┬──────────────────────────┘
                       ┌───────────────▼──────────────────────────┐
  domain               │  MatchingController   (synchronized)      │
                       │  books: Map<ticker, OrderBook>            │
                       │  trades: ArrayDeque<Trade> (cap 10,000)   │
                       │  match() price-time loop                  │
                       └───────┬───────────────────────┬──────────┘
                       ┌───────▼─────────┐     ┌───────▼──────────┐
  structures           │  OrderBook      │     │  EventLog         │
                       │  bids: Treap    │     │  ring, 5,000      │
                       │  asks: Treap    │     └───────────────────┘
                       └───────┬─────────┘
                       ┌───────▼──────────────────────────────────┐
                       │  Treap → PriceNode → CircularQueue → Order│
                       └──────────────────────────────────────────┘
                       ┌──────────────────────────────────────────┐
  infrastructure       │ TradeStore (CSV) · Seeder · PriceFeeder   │
                       │ RandomWalkFeeder · LiveFeeder · Main      │
                       └──────────────────────────────────────────┘
```

Dependency rule: arrows only point downwards. The domain never imports the web
layer; the data structures never import the controller. `Main` is the only class
that decides between the server and the console demo.

### 3.2 Lifecycle of one order

1. **Arrival.** A raw line `alice,BTCUSDT,BUY,1200,10` arrives at
   `OrderGateway.parse` (from `POST /api/order`, the simulator, or the console
   demo).
2. **Validation.** Field count, empty fields, identifier charset and length,
   side spelling, integer parse, price ≤ 100,000,000, quantity ≤ 1,000,000. Any
   failure throws `IllegalArgumentException`; the HTTP layer turns it into 400.
3. **Stamping.** The gateway stamps `System.nanoTime()` as the arrival time. This
   is what time priority is measured from.
4. **Admission.** `MatchingController.processOrder` takes the engine lock, finds
   (or creates) the book for the ticker, clears the per-order trade list, and
   inserts the order into the correct Treap.
5. **Level decision.** `Treap.insert` looks for an existing node at that price.
   Found → enqueue at the tail of its ring. Not found → allocate a `PriceNode`
   with a random priority, enqueue, then insert into the tree with rotations.
6. **Capacity check.** If the level already holds 1,024 orders, `enqueue` returns
   `false`, the controller increments `rejectedCount`, emits `REJECT`, and the
   order is refused. It never partially enters the book.
7. **Matching.** `match` reads best bid and best ask and loops while
   `bestBid.price >= bestAsk.price`: peek the front order on each side, trade
   `min(qty)`, reduce both, dequeue filled orders, delete levels that empty.
8. **Recording.** Each execution appends an immutable `Trade` to the capped tape,
   to the request-scoped `lastTrades` list, to the `TradeStore` (CSV buffer +
   trader stats + per-ticker totals) and to the event log.
9. **Response.** `POST /api/order` returns `{"accepted":…,"trades":[…]}` where the
   trades are exactly the ones this order caused (the caller holds the engine
   lock across submit-and-read, so a concurrent order cannot clear the list in
   between).

### 3.3 Runtime topology

```
Main
 ├─ resolvePort()                      PORT env var, default 8080
 ├─ WebServer(port, seedingEnabled)
 │   ├─ EventLog         (ring, 5,000 events)
 │   ├─ TradeStore       (loads data/*.csv, 1 s flush task, shutdown hook)
 │   ├─ MatchingController(eventLog, tradeStore)
 │   ├─ OrderGateway(controller)
 │   ├─ LiveFeeder       (Binance BTCUSDT trade stream → RandomWalkFeeder fallback)
 │   ├─ Seeder(42)       (only when no book exists yet)
 │   └─ HttpServer       (0.0.0.0:port, 8 threads, 15 contexts)
 └─ or runDemo()        (worked example + two 1,000,000-order runs)
```

### 3.4 Concurrency model (read this before answering threading questions)

| State | Guard |
|---|---|
| Books, Treaps, queues, counters, trade tape | `synchronized` on the `MatchingController` instance |
| Order admission + reading its trades | `synchronized (controller)` in `OrderGateway.submit` and again in `WebServer.handleOrder` |
| `/api/simulate` runs | a dedicated `simulationLock`, so two simulations never interleave |
| `EventLog.append` / `since` | `synchronized` methods on `EventLog` |
| `TradeStore.recordTrade`, `flush`, `close` | `synchronized` methods on `TradeStore` |
| `traderStats`, `tickerTotals` maps | `ConcurrentHashMap` |
| Orders-per-second sampling | `synchronized` on the `WebServer` |

There is exactly **one** engine-wide lock, and that is a deliberate design
choice: correctness and readability over sharding. The matching work per order is
a few microseconds, so contention is not the bottleneck at this scale. Every read
endpoint (`/api/book`, `/api/stats/{ticker}`, `/api/treap`, `/api/depth`) also
takes the controller lock while it copies or walks the structure, so a poll can
never observe a half-rotated tree.

Caveat worth knowing: `EngineEvent` assigns ids from a static `long` counter
incremented outside `EventLog`'s lock. In this codebase events are only
constructed while the controller lock is held, so ids are consistent in practice;
a strictly atomic counter (`AtomicLong`) would be the hardening step if events
were ever produced from another thread.

---

## 4. Source map

| File | Responsibility | Owner |
|---|---|---|
| [src/Order.java](src/Order.java) | One limit order: trader, ticker, side, price, qty, arrival time. Only `quantity` is mutable. | Giri Prasath |
| [src/CircularQueue.java](src/CircularQueue.java) | Array-backed ring: FIFO at one price, fixed capacity 1,024, doubling backing array from 8. | Giri Prasath |
| [src/PriceNode.java](src/PriceNode.java) | Treap node = price level: key, priority, children, queue. Defines `LEVEL_CAPACITY`. | Naren Kaarthi |
| [src/Treap.java](src/Treap.java) | The price ladder: insert, remove, find, findMin/findMax, height, JSON snapshot, `Listener` hook. | Naren Kaarthi |
| [src/PlainBst.java](src/PlainBst.java) | Unbalanced baseline used only by the benchmark to demonstrate degeneration. | Naren Kaarthi |
| [src/Trade.java](src/Trade.java) | Immutable execution record; hand-built JSON. | Giri Prasath |
| [src/OrderBook.java](src/OrderBook.java) | One symbol: bid Treap + ask Treap, best-price queries, top-N level walks, snapshot. | Giri Prasath |
| [src/MatchingController.java](src/MatchingController.java) | The exchange: books, matching loop, counters, trade tape, event emission. | Mithun Prasath K |
| [src/OrderGateway.java](src/OrderGateway.java) | Parse + validate the wire format; accept/reject counters. | Mithun Prasath K |
| [src/OrderSimulator.java](src/OrderSimulator.java) | Seeded load generator in RANDOM / SEQUENTIAL modes; statistics bundle. | Mithun Prasath K |
| [src/Main.java](src/Main.java) | Entry point: server or `demo`; `--no-seed`; `PORT` resolution. | Mithun Prasath K |
| [src/PriceFeeder.java](src/PriceFeeder.java) | Reference-price interface. | Gathin |
| [src/RandomWalkFeeder.java](src/RandomWalkFeeder.java) | Per-symbol random walk, step ±2, floor 1. | Gathin |
| [src/LiveFeeder.java](src/LiveFeeder.java) | Public exchange WebSocket, 5 s staleness, automatic fallback. | Gathin |
| [src/EngineEvent.java](src/EngineEvent.java) | Immutable structural event with escaped JSON. | Gathin |
| [src/EventLog.java](src/EventLog.java) | Ring buffer of the last 5,000 events; `since(id, ticker, max)`. | Gathin |
| [src/TradeStore.java](src/TradeStore.java) | Append-only CSV, load on startup, trader stats, per-ticker totals. | Gathin |
| [src/Seeder.java](src/Seeder.java) | ~500 demo orders through the gateway when the book is empty. | Gathin |
| [src/TraderStats.java](src/TraderStats.java) | Per-trader aggregates and leaderboard sorting. | Gathin |
| [src/WebServer.java](src/WebServer.java) | HTTP API, static files, CORS, JSON assembly, 8-thread pool. | Gathin |
| [src/tests/QueueTest.java](src/tests/QueueTest.java) | 33 assertions on the ring. | Giri Prasath |
| [src/tests/TreapTest.java](src/tests/TreapTest.java) | 43 assertions incl. invariants and JSON depth boundary. | Naren Kaarthi |
| [src/tests/MatchingTest.java](src/tests/MatchingTest.java) | 47 assertions on matching semantics. | Mithun Prasath K |
| [src/tests/Benchmark.java](src/tests/Benchmark.java) | Two 1M-order runs + Treap vs PlainBst height comparison. | Mithun Prasath K |
| [web/index.html](web/index.html) | The trading terminal markup. | Gathin |
| [web/style.css](web/style.css) | Terminal theme; blue = bid/buy, orange = ask/sell. | Gathin |
| [web/app.js](web/app.js) | State, polling, order form, simulation, event timeline. | Gathin |
| [web/charts.js](web/charts.js) | Candles, depth chart, SVG tree view, leaderboard bars. | Gathin |
| [web/landing.html](web/landing.html) | Standalone overview page with a live engine pulse. | Gathin |
| [Dockerfile](Dockerfile) | Two-stage build: JDK compile → JRE runtime. | Gathin |

All classes are in the **default package** on purpose: `javac` needs no
directory-to-package mapping, and the project stays a folder of `.java` files
that any machine can compile.

---

## 5. Data structures and why they were chosen

### 5.1 `Order` — the unit of work

Six fields, five `final`. `quantity` is the only mutable field because a partial
fill shrinks the order **in place** while it stays at the front of its queue.

- `price` is `int`, not `double`. Two orders "at the same price" must land on the
  same Treap node; `1200.0 == 1199.9999999` is exactly the bug that silently
  creates duplicate levels. Integers are also the natural tick representation.
- `timestamp` is supplied by the caller so tests can build a deterministic
  arrival sequence instead of depending on the wall clock.

### 5.2 `CircularQueue` — time priority at one price

```
orders: [ B ][ C ][ _ ][ _ ][ _ ][ _ ][ _ ][ _ ]
          ^head      ^tail
          count = 2, capacity = 1024 (backing array 8)
```

- `enqueue` writes at `tail`, advances `tail = (tail+1) % orders.length`.
- `dequeue` reads `head`, nulls the slot (so the array does not pin filled orders
  in memory), advances `head`.
- `peek` returns the head without consuming it — required because a partial fill
  must leave the order exactly where it is.
- The backing array starts at 8 and doubles up to 1,024. This is purely a memory
  decision: one million distinct levels each eagerly allocating 1,024 slots would
  be ~8 GB of mostly empty arrays. Externally nothing changes — `isFull()` is
  true at exactly 1,024.
- Fixed capacity is behaviour, not a limitation: a real exchange refuses orders
  at a saturated level, so `enqueue` returning `false` **is** the specification.

Complexity: enqueue O(1) amortised, dequeue O(1), peek O(1), `totalQuantity()`
O(k) (used only for the UI, never on the hot path).

### 5.3 `PriceNode` — one price level

```
PriceNode
 ├─ price    (final int)      BST key
 ├─ priority (final int)      max-heap key, random, fixed for life
 ├─ left, right (PriceNode)   BST children
 └─ queue    (CircularQueue)  FIFO of orders at this price, capacity 1,024
```

Carrying the queue **inside** the node is what makes "best price, then earliest
order" a single lookup: once the tree has found the best price, the best order is
one pointer away. A separate `price → queue` map would be a second structure that
can fall out of sync.

### 5.4 `Treap` — the price ladder

A binary search tree on `price` that simultaneously satisfies the max-heap
property on `priority`. Because priorities are random and independent of
insertion order, the tree's shape is the same as if the keys had arrived in
random order.

Operations:

| Operation | Method | Complexity |
|---|---|---|
| Insert level | `insert(order)` | expected O(log N) |
| Delete level | `remove(price)` | expected O(log N) |
| Exact lookup | `find(price)` | expected O(log N) |
| Best bid (max) | `findMax()` | expected O(log N) |
| Best ask (min) | `findMin()` | expected O(log N) |
| Height | `height()` | O(N), iterative |
| Size | `size()` | O(1) |
| UI snapshot | `toJson(depth)` | O(min(N, 2^depth)) |

`insert` is also where an order joins an existing level, so the common case
(repeat price) is one `find` plus one `enqueue` — no allocation, no rotation.

### 5.5 `OrderBook` — two ladders per symbol

Best bid is the **highest** buy price; best ask the **lowest** sell price. Two
trees make each query a straight walk down one side. One combined tree would turn
"best bid" into "the largest key that happens to be a bid", which is no longer a
rightmost walk — it degenerates into a scan. Splitting also halves N per side.

Crossing condition: `bestBid.price >= bestAsk.price` — two O(log N) lookups and
one comparison.

`topBids(n)` / `topAsks(n)` produce ordered level lists for the UI using an
iterative in-order walk with an explicit stack, stopping after n levels. That is
O(n + log N), not a full traversal.

### 5.6 `MatchingController` collections

| Field | Type | Why |
|---|---|---|
| `books` | `HashMap<String, OrderBook>` | O(1) average symbol lookup per order; a book is created on first sight of a symbol. The UML's `OrderBook[]` would force a linear scan and a fixed symbol set. |
| `trades` | `ArrayDeque<Trade>`, cap 10,000 | Append at back, drop from front: amortised O(1) at both ends, no per-element node. `ArrayList` removal from the front is O(n), which at a million orders would dominate. |
| `lastTrades` | `ArrayList<Trade>` | Only the executions of the order currently being processed, so the HTTP response can say exactly what that order did. |

Lifetime totals (`acceptedCount`, `rejectedCount`, `totalTrades`) are separate
`long` counters, so dropping old tape never corrupts the reported totals.

### 5.7 `EventLog` — bounded audit trail

A 5,000-slot array ring of `EngineEvent`. `append` overwrites the oldest;
`since(id, ticker, max)` walks oldest → newest and filters by id and ticker. The
bound is what keeps a million-order simulation from growing memory without limit.

Event types: `NODE_INSERT`, `ROTATE_LEFT`, `ROTATE_RIGHT`, `NODE_DELETE`,
`ENQUEUE`, `DEQUEUE`, `ORDER_UPDATE`, `TRADE`, `REJECT`.

### 5.8 `TradeStore` — durability without a database

| Structure | Purpose |
|---|---|
| `allTrades` | Full in-memory tape loaded from `data/trades.csv` at startup |
| `tradeBuffer` / `orderBuffer` | Rows waiting to be appended (flushed every 1 s and on shutdown) |
| `traderStats` | `ConcurrentHashMap<traderId, TraderStats>` rebuilt from loaded trades |
| `tickerTotals` | Per-symbol lifetime volume, trade count and latest price, updated on the write path so `/api/stats/{ticker}` never rescans the tape |

Important behaviours:

- CSVs are **append-only, no header**; lines starting with `#` and malformed
  lines are skipped on load.
- Open-book state is **not** persisted — only history. After a restart the books
  are empty and the `Seeder` recreates demo liquidity.
- `recordOrder` exists but nothing calls it, so `data/orders.csv` stays empty.
  Only `trades.csv` carries data.

### 5.9 Alternatives considered (the comparison table examiners like)

| Structure | Why not chosen |
|---|---|
| AVL / red-black tree | Same O(log N) but worst-case; costs height/colour fields and several rebalancing cases. A Treap rebalances with one comparison and one rotation per level. |
| Skip list | Similar expected bounds, but multiple nodes and forward-pointer arrays per key — far more memory at a million levels. |
| Hash map `price → queue` | O(1) exact lookup but cannot answer "what is the best price", which the matcher asks on every order. |
| Sorted array | O(log N) search, O(N) insert/delete from shifting. |
| `LinkedList` per level | FIFO works, but a node object and two pointer chases per order, scattered across the heap; the matcher touches a queue head on every trade. |
| One Treap for both sides | "Best bid" becomes "largest bid key", i.e. a scan. |

---

## 6. Algorithms, in pseudocode

### 6.1 Treap insert

```
insert(order):
    node = find(order.price)
    if node != null:
        return node.queue.enqueue(order)      # repeat price: no allocation

    node = new PriceNode(order.price, random.nextInt())
    if not node.queue.enqueue(order): return false
    root = insertNode(root, node)
    size++
    emit NODE_INSERT
    return true

insertNode(current, node):
    if current == null: return node
    if node.price < current.price:
        current.left = insertNode(current.left, node)
        if current.left.priority > current.priority:
            current = rotateRight(current)     # restore max-heap
    else:
        current.right = insertNode(current.right, node)
        if current.right.priority > current.priority:
            current = rotateLeft(current)
    return current
```

### 6.2 Rotations

```
rotateRight(p):            rotateLeft(p):
    n = p.left                 n = p.right
    p.left = n.right           p.right = n.left
    n.right = p                n.left = p
    return n                   return n
```

A rotation is O(1) and preserves the in-order sequence, so BST order is never
broken — only the shape changes.

### 6.3 Treap delete (rotate the node down, then unlink)

```
remove(price):
    target = find(price)
    if target != null: emit NODE_DELETE
    root = removeNode(root, price)
    if hit: size--

removeNode(current, price):
    if current == null: return null
    if price < current.price: current.left  = removeNode(current.left,  price); return current
    if price > current.price: current.right = removeNode(current.right, price); return current

    # found it; rotate it down until it is a leaf
    if current.left == null and current.right == null: hit = true; return null
    if current.left  == null: current = rotateLeft(current);  current.left  = removeNode(current.left,  price)
    elif current.right == null: current = rotateRight(current); current.right = removeNode(current.right, price)
    elif current.left.priority > current.right.priority:
        current = rotateRight(current); current.right = removeNode(current.right, price)
    else:
        current = rotateLeft(current);  current.left  = removeNode(current.left,  price)
    return current
```

Choosing the higher-priority child to rotate up keeps the heap property intact
throughout the deletion.

### 6.4 Best price

```
findMax(): walk right while right != null      # best bid
findMin(): walk left  while left  != null      # best ask
```

### 6.5 The matching loop

```
processOrder(order):                       # synchronized — the only mutation entry
    book = books.get(order.ticker) or create it
    lastTrades.clear()

    if not book.addOrder(order):           # Treap.insert returned false
        rejectedCount++; emit REJECT; return

    acceptedCount++; emit ENQUEUE
    match(book, order.ticker)

match(book, ticker):
    bid = book.bestBid(); ask = book.bestAsk()
    while bid != null and ask != null and bid.price >= ask.price:
        buy  = bid.queue.peek()
        sell = ask.queue.peek()
        if buy  == null: book.removeBidLevel(bid.price); bid = book.bestBid(); continue
        if sell == null: book.removeAskLevel(ask.price); ask = book.bestAsk(); continue

        qty = min(buy.quantity, sell.quantity)
        recordTrade(ticker, buy, sell, qty)

        if buy.isFilled():
            bid.queue.dequeue(); emit DEQUEUE
            if bid.queue.isEmpty(): book.removeBidLevel(bid.price); bid = book.bestBid()
            else: emit ORDER_UPDATE
        if sell.isFilled():
            ask.queue.dequeue(); emit DEQUEUE
            if ask.queue.isEmpty(): book.removeAskLevel(ask.price); ask = book.bestAsk()
            else: emit ORDER_UPDATE
```

Note the two `null` guards: a level can exist with an empty queue only transiently,
but the guard makes the loop robust rather than relying on that invariant.

### 6.6 Price improvement

```
price = (buy.timestamp <= sell.timestamp) ? buy.price : sell.price
```

The **older** order is the one that was resting; the execution prints at its limit
price. An aggressor willing to pay 120 who hits an ask resting at 100 pays 100 —
the benefit goes to the taker, as on a real exchange. Timestamps are monotonic
`System.nanoTime()` values from the gateway; an exact tie resolves to the buy
price (deterministic, and only reachable with hand-built orders in tests).

### 6.7 Level lifecycle

- **Created** by the first order at a price (one `PriceNode` + one ring).
- **Deleted** the moment its queue empties, inside the same matching iteration.
  This is why `bestBid`/`bestAsk` can never return a dead level and why the tree
  never accumulates ghosts.
- A partially filled order **stays at the front** of its queue and keeps its
  original time priority, so it still trades before anything that arrived later
  at the same price.

### 6.8 Circular queue operations

```
enqueue(o):
    if count == capacity: return false
    if count == orders.length: grow()
    orders[tail] = o; tail = (tail+1) % orders.length; count++; return true

dequeue():
    if count == 0: return null
    o = orders[head]; orders[head] = null
    head = (head+1) % orders.length; count--; return o

grow():                                  # only while length < capacity
    bigger = new Order[min(capacity, length*2)]
    for i in 0..count-1: bigger[i] = orders[(head+i) % orders.length]
    orders = bigger; head = 0; tail = count
```

Copying straightens the ring, so `head = 0` and `tail = count` afterwards is
exact. Growth happens at most `log2(1024) = 10` times per level.

### 6.9 Iterative height

Recursion on a tree that can be a million levels deep would `StackOverflowError`
long before it was slow. Both `Treap.height()` and `PlainBst.height()` use an
explicit `(node, depth)` stack that doubles when full — O(N) time,
O(height) space, no call stack.

### 6.10 Worked trace (this is exactly what `Main demo` prints)

Start: empty book for `DEMO`.

| Step | Action | Trades | Book afterwards |
|---|---|---|---|
| 1 | `ALICE SELL 102 x50` | — | asks 102×50 |
| 2 | `BOB SELL 101 x40` | — | asks 101×40, 102×50 |
| 3 | `CAROL BUY 99 x30` | — | bids 99×30; spread 101−99 = 2 |
| 4 | `DAVE BUY 101 x25` | 25 @101 (BOB) | ask 101×15 remains, ask 102×50, bid 99×30 |
| 5 | `ERIN BUY 102 x100` | 15 @101 (BOB), 50 @102 (ALICE) | both asks deleted; 35 rests at bid 102 |
| 6 | `FRANK SELL 103 x10` | — | asks 103×10 |
| 7 | `GRACE BUY 120 x10` | **10 @103** | prints at the resting ask's price, not 120 |
| 8 | `HANK HOLD 100 10` | rejected | side must be BUY or SELL |
| 9 | `HANK BUY 100 0` | rejected | qty must be between 1 and 1,000,000 |
| 10 | `HANK BUY abc 10` | rejected | price must be a whole number |
| 11 | `HANK BUY 100` | rejected | expected 5 fields, got 4 |

Step 5 is the sweep: one order consumed two levels and left a remainder resting
at its own limit. Step 7 is price improvement. Steps 8–11 are four distinct
validation failures.

---

## 7. Class reference

### `Order`

| Member | Meaning |
|---|---|
| `Order(traderId, ticker, isBuy, price, quantity, timestamp)` | Full constructor; nothing is derived |
| `reduceQuantity(qty)` | Subtract a fill, clamped at 0 |
| `isFilled()` | `quantity <= 0` |
| `getTraderId/getTicker/isBuy/getPrice/getQuantity/getTimestamp` | Accessors |
| `toString()` | `alice BUY 10 BTCUSDT @1200` |

### `CircularQueue`

`CircularQueue(capacity)` (throws on non-positive capacity), `enqueue`, `dequeue`,
`peek`, `isEmpty`, `isFull`, `size`, `capacity`, `totalQuantity`.

### `PriceNode`

`LEVEL_CAPACITY = 1024`; `PriceNode(price, priority)`; `getPrice`,
`getPriority`, `getQueue`, `orderCount`, `totalQuantity`, `toString`.

### `Treap`

| Member | Meaning |
|---|---|
| `Treap()`, `Treap(seed)`, `Treap(seed, listener)` | Seed makes runs reproducible |
| `insert(Order)` | `true` if accepted, `false` if the level is full |
| `remove(int price)` | Deletes the level; no-op if absent |
| `find(int price)` | `PriceNode` or `null` |
| `findMin()`, `findMax()` | Best ask / best bid, or `null` |
| `height()` | Longest root-to-leaf path in nodes (0 when empty) |
| `size()` | Distinct levels |
| `isEmpty()`, `getRoot()` | Inspection |
| `setListener(Listener)` | Receives `(type, price, detail)` structural events |
| `toJson(int depth)` | `null` for an empty ladder, else nested nodes capped at `depth` |

`toJson` emits `left`/`right` as `null` for every node on the last visible level.
Skipping them produced `"left":,"right":}` — invalid JSON — which made
`response.json()` reject and silently froze the live tree view.

### `OrderBook`

`OrderBook(ticker, eventLog)` / `OrderBook(ticker, seed, eventLog)`,
`addOrder`, `bestBid`, `bestAsk`, `removeBidLevel`, `removeAskLevel`, `getTicker`,
`getBids`, `getAsks`, `snapshot(depth)`, `topBids(depth)`, `topAsks(depth)`.
`Level` holds `price`, `quantity`, `orders`; `Snapshot` holds two level lists.

### `MatchingController`

`MatchingController()` / `MatchingController(eventLog, tradeStore)`,
`processOrder(Order)` (synchronized), `getLastTrades`, `getTrades(last)`,
`getBook`, `getOrCreateBook`, `getTickers`, `getAcceptedCount`,
`getRejectedCount`, `getTotalTrades`, `setEventLog`, `setTradeStore`.

### `OrderGateway`

`parse(String raw)` → `Order` or `IllegalArgumentException`;
`submit(Order)` → boolean accepted; `submitRaw(String)`;
`lastTrades()`; `getAcceptedCount()`, `getRejectedCount()`.

### `OrderSimulator`

`OrderSimulator(seed, orderCount, feeder, mode[, ticker])`,
`run(gateway)`, `getStats()`, `generateRawOrders(n)`, `getTicker`, `getSeed`,
`getOrderCount`. `Stats` exposes `getOrders`, `getMode`, `getTotalTimeMs`,
`getOrdersPerSecond`, `getTrades`, `getRejected`, `getBidHeight`, `getAskHeight`,
`getBidLevels`, `getAskLevels`, plus `toString()` and `toJson()`.
Constants: `DEFAULT_SEED = 42`, `DEFAULT_TICKER = "BTCUSDT"`, 100 traders
(`T0`–`T99`), spread ±5, quantity 1–100.

### `WebServer`

`DEFAULT_PORT = 8080`; `WebServer(port[, enableSeeding])`, `start()`, `stop()`,
`getPort()`, `getController()`. Private constants: `BOOK_DEPTH = 10`,
`DEFAULT_TRADE_COUNT = 50`, `MAX_SIMULATE_ORDERS = 1_000_000`,
`THREAD_POOL_SIZE = 8`.

### `Main`

`main(String[])`; flags `demo`, `--no-seed`; `PORT` env var. Invalid or
out-of-range `PORT` falls back to 8080 (with a message for non-numeric values).

### `PriceFeeder` / `RandomWalkFeeder` / `LiveFeeder`

`nextPrice(ticker)` is the whole interface. `RandomWalkFeeder` also offers
`currentPrice(ticker)` (read without advancing) and
`RandomWalkFeeder(startPrice, seed)`. `LiveFeeder` exposes `getStatus()` and
`getStreamSymbol()`; the live stream carries **BTCUSDT only**, with a 5 s
staleness window.

### `TradeStore`

`recordTrade(Trade)`, `recordOrder(Order)`, `getTrades()`, `getOrders()`,
`getTraderStats(id)`, `getAllTraderStats()`, `getTickerTotals(ticker)`, `flush()`,
`close()`. Nested types: `TradeRecord`, `OrderRecord`, `TickerTotals`.

### `EventLog` / `EngineEvent`

`append(event)`, `since(id, ticker, max)`, `size()`. `EngineEvent` public final
fields: `id`, `ts`, `ticker`, `side`, `type`, `price`, `traderId`, `detail`;
`toJson()` escapes quotes, backslashes, newlines and control characters.

### `TraderStats` / `Seeder`

`addTrade(isBuy, price, qty, ts)`, `getTrades`, `getBoughtQty`, `getSoldQty`,
`getNetPosition`, `getAvgBuy`, `getAvgSell`, `getCashFlow`, `getLastActive`,
`toJson()`, static `leaderboard(map, byCashFlow)`. Averages are formatted with
`Locale.ROOT` so a comma-decimal locale cannot emit invalid JSON.
`Seeder(gateway, feeder, seed).seedIfEmpty()` submits 500 orders across
`AAPL`, `TSLA`, `BTCUSDT` from five traders **only if no book exists**.

---

## 8. Data formats and persistence

### 8.1 Order wire format

```
traderId,ticker,BUY|SELL,price,qty
alice,BTCUSDT,BUY,1200,10
```

| Field | Rule | On violation |
|---|---|---|
| count | exactly 5, comma separated | `expected 5 fields … but got N` |
| `traderId` | non-empty, ≤ 20 chars, `[A-Za-z0-9._-]` | descriptive rejection |
| `ticker` | same rules, upper-cased | descriptive rejection |
| side | `BUY` or `SELL`, case-insensitive | `side must be BUY or SELL` |
| `price` | integer, 1 … 100,000,000 | `price must be between 1 and 100000000` |
| `qty` | integer, 1 … 1,000,000 | `qty must be between 1 and 1000000` |

JSON bodies are accepted on `POST /api/order` and converted to the same line:
`{"traderId":"alice","ticker":"AAPL","side":"BUY","price":100,"qty":10}`
(`quantity` is accepted as an alias for `qty`; all five fields are required).

The charset restriction is not cosmetic: identifiers are interpolated into
hand-built JSON, so refusing quotes and control characters is what makes that
JSON safe.

### 8.2 CSV persistence

`data/trades.csv` — one execution per line, no header:

```
ts,ticker,buyerId,sellerId,price,qty
1759000000000,AAPL,alice,bob,187,10
```

`data/orders.csv` — same idea for accepted orders (`ts,trader,ticker,side,price,qty`).
The writer exists (`recordOrder`) but is not wired into the controller, so this
file stays empty in practice.

Loading rules: missing file → skip; blank line or `#` → skip; fewer than six
fields or a parse failure → skip that line only. Writes are buffered and flushed
every second and once more during shutdown (flush happens **before** the closed
flag is set, so the last second of trades is not lost).

### 8.3 JSON response shapes (all fields, exactly as emitted)

```jsonc
// Trade
{"buyerId":"alice","sellerId":"bob","ticker":"AAPL","price":187,"quantity":10,"timestamp":1759000000000}

// Book level
{"price":187,"qty":40,"orders":3}

// Engine event
{"id":8123,"ts":1759000000000,"ticker":"AAPL","side":"BID","type":"NODE_INSERT","price":187,"traderId":"","detail":"priority=12345"}

// Treap node (recursive, null past ?depth=)
{"price":187,"priority":12345,"orders":3,"qty":40,"left":null,"right":{...}}

// Candle
{"ts":1759000000000,"open":187,"high":190,"low":185,"close":188,"volume":420}

// Trader stats
{"traderId":"alice","trades":12,"boughtQty":300,"soldQty":180,"netPosition":120,
 "avgBuy":187.50,"avgSell":190.00,"cashFlow":-21600,"lastActive":1759000000000}
```

---

## 9. HTTP API reference

Base URL: `http://localhost:<PORT>` (default 8080). All responses are
`application/json; charset=utf-8` unless noted. CORS is `*`, `OPTIONS` returns
204. Every path below is exact — routing is prefix-based on the JDK context, and
each handler validates the tail itself.

| Method | Path | Query / body | Returns |
|---|---|---|---|
| GET | `/` | — | `web/index.html`, then any file under `web/` |
| POST | `/api/order` | CSV line or JSON object | `{"accepted":bool,"trades":[…]}`; 400 with `{"accepted":false,"error":…}` |
| GET | `/api/book/{ticker}` | — | `{"ticker":…,"bids":[…],"asks":[…]}` top 10, bids high→low, asks low→high |
| GET | `/api/trades` | `n` (default 50, clamp 1–500) | array of trades, newest first |
| POST | `/api/simulate` | `n` (default 100,000, clamp 1–1,000,000), `mode=random\|sequential`, `ticker` (default `BTCUSDT`) | simulation stats object; 405 on GET, 400 on bad ticker |
| GET | `/api/price/{ticker}` | — | `{"ticker":…,"price":…,"source":…}`; empty ticker → default ticker |
| GET | `/api/health` | — | plain text `ok` |
| GET | `/api/stats` | — | `{"accepted":…,"rejected":…,"trades":…}` (engine-wide) |
| GET | `/api/stats/{ticker}` | — | best bid/ask, spread, last price, volume, trades, both Treap heights, accepted/rejected, orders/s |
| GET | `/api/events` | `since` (default −1), `ticker` (default all), `max` (default 200, clamp 1–500) | array of events with `id > since`, oldest first; 400 if `since` is not an integer |
| GET | `/api/treap/{ticker}/{bids\|asks}` | `depth` (default 5, clamp 1–20) | nested node JSON, or the literal `null`; 400 on a bad side |
| GET | `/api/candles/{ticker}` | `bucket` seconds (default 5, clamp 1–3600) | up to 120 OHLCV buckets, oldest first |
| GET | `/api/depth/{ticker}` | — | `{"bids":[{"price","cumQty"}],"asks":[…]}` top 100, cumulative |
| GET | `/api/traders` | — | leaderboard array, highest cash flow first |
| GET | `/api/trader/{id}` | — | that trader's stats + `recentTrades` (from the last 50 tape entries); 404 if unknown |

Behaviour details that are easy to get wrong when writing a client:

- A ticker the exchange has never seen is **created on demand** by
  `/api/book`, `/api/treap`, `/api/stats/{ticker}` and `/api/depth` — you get an
  empty book, not a 404.
- `bestBid`/`bestAsk` are the JSON literal `null` when that side is empty; guard
  for it in any client.
- `/api/stats/{ticker}` reports **lifetime** volume/trades/last price from
  `TradeStore`, and **global** accepted/rejected counters — not per-symbol counts.
- `ordersPerSec` is measured over a ≥1 s window of accepted orders; it is 0 until
  the first window closes.
- `since` defaults to −1, so a bare `/api/events` returns the newest 200 events.
- `/api/price` advances the random walk when the live feed is unavailable (that is
  what makes the UI price move); it does not touch the book.
- Static file serving normalises the path and refuses anything outside `web/`
  (e.g. `/../src/Main.java` → 404), and sends `Cache-Control: no-store` so a
  refresh always shows current files.
- GET on a POST-only route returns 405 with a JSON message.

### curl examples

```bash
curl -s localhost:8080/api/health
# ok

curl -s -X POST localhost:8080/api/order -d 'alice,AAPL,SELL,100,10'
# {"accepted":true,"trades":[]}

curl -s -X POST localhost:8080/api/order -d 'bob,AAPL,BUY,100,10'
# {"accepted":true,"trades":[{"buyerId":"bob","sellerId":"alice",...}]}

curl -s -X POST localhost:8080/api/order \
  -H 'Content-Type: application/json' \
  -d '{"traderId":"carol","ticker":"AAPL","side":"BUY","price":101,"qty":5}'

curl -s 'localhost:8080/api/book/AAPL'
curl -s 'localhost:8080/api/trades?n=20'
curl -s 'localhost:8080/api/stats/AAPL'
curl -s 'localhost:8080/api/events?since=0&ticker=AAPL'
curl -s 'localhost:8080/api/treap/AAPL/bids?depth=3'
curl -s 'localhost:8080/api/candles/AAPL?bucket=5'
curl -s 'localhost:8080/api/depth/AAPL'
curl -s localhost:8080/api/traders
curl -s localhost:8080/api/trader/alice
curl -s -X POST 'localhost:8080/api/simulate?n=50000&mode=random&ticker=AAPL'
```

PowerShell: use `curl.exe` to avoid the `curl` alias for `Invoke-WebRequest`, and
quote the URL (`curl.exe -s "localhost:8080/api/book/AAPL"`).

---

## 10. Web interface

`web/` is plain HTML, CSS and JavaScript. No framework, no build step, no CDN.

| File | Role |
|---|---|
| `index.html` | Full terminal: top bar, stat tiles, four charts, book table, order form, trades, event timeline, simulation panel |
| `app.js` | State, `fetch` polling, form submission, event timeline with filter/pause/clear, simulation runner |
| `charts.js` | Candlestick + volume, cumulative depth step chart, SVG Treap view, leaderboard bars — all hand-drawn |
| `style.css` | Dark terminal theme; **bid/buy = blue `#2563eb`, ask/sell = orange `#ea580c`** everywhere, plus `BID`/`ASK` text and `B`/`S` badges so colour is never the only signal |
| `landing.html` | Standalone overview page; links to the terminal and shows a live pulse when the API answers |

Polling cadence: price/stats/book/trades/candles/depth/trees every 1 s; event
timeline every 500 ms with `?since=lastId`. The Treap view asks for
`depth=5` and flashes nodes that appear in new structural events.

Panels and what they prove:

1. **Top bar** — ticker selector, live price with direction arrow, trader name
   remembered in `localStorage`, feed status (`LIVE` or `SIMULATED`).
2. **Stat tiles** — best bid, best ask, spread, last price, volume, trades,
   bid/ask Treap height, orders/s, accepted/rejected.
3. **Charts** — 5 s candles with volume; cumulative depth with a mid-price line;
   two SVG trees (bids, asks) whose node radius scales with quantity and whose
   badges show orders at the level; trader leaderboard by cash flow.
4. **Book table** — asks above bids, price/qty/orders columns, depth-fill bar
   behind each row.
5. **Order form** — trader, ticker, BUY/SELL toggle, price, qty, submit; the
   response's trades are shown immediately.
6. **Recent trades** — time, ticker, price, qty, buyer, seller.
7. **Event timeline** — `NODE_INSERT`, `ROTATE_*`, `ENQUEUE`, `DEQUEUE`,
   `ORDER_UPDATE`, `TRADE`, `REJECT`, with type filter chips and pause/clear.
   This is the panel that makes the data structure visible rather than implied.
8. **Simulation panel** — n, mode, Run, result stats.

`landing.html` is reachable at `/landing.html` and from the terminal's
"About the engine" link.

---

## 11. Build and run anywhere

### 11.1 Requirements

- A **JDK 17 or newer** (not just a JRE — you need `javac`).
- Any OS: Windows, Linux, macOS, or a container.
- No network, no Maven, no Gradle, no environment variables besides optional
  `PORT`.

Check what you have:

```bash
java -version      # must say 17 or higher
javac -version     # must exist and match
```

If `java -version` says 1.8 but a newer JDK is installed, point at it explicitly:

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-21
# Windows Git Bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9+10"
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9+10"
```

A `jdk21.zip` sits in this folder as a portable fallback: unzip it anywhere and use
`<unzipped>/bin/java` and `<unzipped>/bin/javac`. Nothing needs to be installed
system-wide.

> The engine uses `com.sun.net.httpserver` (module `jdk.httpserver`) and
> `java.net.http`. Standard JDK builds include both. On a stripped runtime image,
> check with `java --list-modules | grep -E "httpserver|java.net.http"`.

### 11.2 Compile

From the project root (the folder containing `src/` and `web/`):

```bash
mkdir -p out
javac -d out src/*.java src/tests/*.java
```

Windows `cmd.exe`:

```bat
if not exist out mkdir out
javac -d out src\*.java src\tests\*.java
```

If your shell does not expand globs, use an argument file (works everywhere):

```bash
# Git Bash / Linux / macOS
printf '%s\n' src/*.java src/tests/*.java > sources.txt
javac -d out @sources.txt
```

```bat
:: cmd.exe
dir /b /s src\*.java src\tests\*.java > sources.txt
javac -d out @sources.txt
```

A clean compile produces only warnings-free output and `.class` files under
`out/`. Classes stay in the default package, so `-cp out` is all you need.

### 11.3 Run the web server

```bash
java -cp out Main                 # http://localhost:8080
PORT=9000 java -cp out Main       # custom port (bash / Git Bash / macOS / Linux)
```

```powershell
# PowerShell
$env:PORT = "9000"; java -cp out Main
```

```bat
:: cmd.exe
set PORT=9000
java -cp out Main
```

Flags:

| Flag | Effect |
|---|---|
| `--no-seed` | Do not generate the 500 demo orders at startup |
| `demo` | Run the console demonstration instead of the server |

`PORT` must be 1–65535; anything else falls back to 8080 (a non-numeric value
also prints a warning). The server binds `0.0.0.0`, so it is reachable from other
machines and from a container port mapping.

### 11.4 Run the console demo

```bash
java -cp out Main demo
```

Prints the worked example from §6.10 (resting liquidity, a cross, a sweep, price
improvement, four rejections) and then two 1,000,000-order load runs. This is the
fastest way to show the engine working with no browser at all.

### 11.5 Run the tests

```bash
java -cp out QueueTest
java -cp out TreapTest
java -cp out MatchingTest
java -cp out Benchmark 200000
```

Each suite prints one `PASS`/`FAIL` line per assertion and exits non-zero on any
failure, so they chain:

```bash
java -cp out QueueTest && java -cp out TreapTest && java -cp out MatchingTest \
  && echo "ALL SUITES PASS"
```

### 11.6 Run with Docker

```bash
docker build -t hft-engine .
docker run --rm -p 8080:8080 hft-engine
# http://localhost:8080
```

The image is two stages: `eclipse-temurin:17-jdk` compiles, then
`eclipse-temurin:17-jre` runs with `out/` and `web/`. `PORT=8080` is baked in and
can be overridden with `-e PORT=9000 -p 9000:9000`.

### 11.7 Run from an IDE

IntelliJ IDEA / Eclipse / VS Code:

1. Open the **project root** as the project (not `src/`).
2. Mark `src` as a source root (and `src/tests` too, or leave it as a plain
   folder — both work because everything is in the default package).
3. Run configuration: main class `Main`, **working directory = project root**.
   The working directory matters twice: `web/` is resolved relative to it, and
   `data/` is created there.
4. Optional program argument `demo` or `--no-seed`; optional env `PORT`.

### 11.8 Directory, port and offline caveats

- `WebServer` looks for `web/` in the current directory, then `../web/`. Run from
  the project root and this is never an issue.
- `TradeStore` writes to `data/` **relative to the current directory**, creating
  it if missing. Run from a different directory and you get a fresh, empty
  history there.
- Port already in use → `BindException` on startup. Pick another port.
- Behind a firewall, the live feed fails cleanly and the UI shows `SIMULATED`.
  Nothing else changes.
- Startup with a large `data/trades.csv` takes a moment: every line is loaded and
  trader stats are rebuilt before the server starts listening.

---

## 12. Verification: tests and benchmark

All three suites were run for this manual on a Windows machine with Temurin
JDK 21.0.9 after a clean `javac` of every source file: **123 assertions, 0
failures**.

```
QueueTest:    ALL PASS (33 checks)
TreapTest:    ALL PASS (43 checks)
MatchingTest: ALL PASS (47 checks)
```

### 12.1 `QueueTest` — 33 checks

FIFO order of three orders; rejection of a 4th into a capacity-3 queue (size
unchanged); wrap-around reuse of a freed slot with FIFO preserved; `null` from
`dequeue`/`peek` on empty; peek is non-destructive; `size`/`isEmpty`/`isFull`
track every operation; 64 enqueues across several doubles preserve order exactly;
`totalQuantity` correct including after a wrap.

### 12.2 `TreapTest` — 43 checks

| Case | Proves |
|---|---|
| 10,000 ascending prices | height < 60 (actual 32) — the headline result |
| 10,000 random prices | height < 60 (actual 33) — insensitive to arrival order |
| `findMin`/`findMax` | correct best prices, still correct after removals |
| `find` | present prices found, absent prices `null` |
| duplicate price | one node, three queued orders, FIFO preserved, quantities summed |
| remove every node | BST order **and** heap property re-verified after each of 300 removals, by full traversal |
| size tracking | exact after 1,000 inserts and 500 removals |
| remove missing price | no-op |
| empty treap | every query returns `null`/0 without throwing |
| level capacity | 1,024 accepted, 1,025th refused |
| JSON depth boundary | depths 1–6 emit no empty value, depth 3 is capped at 7 nodes, empty ladder serialises as `null` |

The two invariants are checked structurally, not by spot checks: an in-order walk
must be strictly ascending (BST), and every node's priority must be ≥ both
children's (max-heap).

### 12.3 `MatchingTest` — 47 checks

Exact cross (both levels deleted); partial fill on either side (remainder at the
queue front); no cross (both rest, spread intact); FIFO at one price (`EARLY`
trades, `LATE` does not); multi-level sweep (3 trades at 100/105/110, remainder
rests at its own limit); price improvement both directions; queue-full rejection
(1,024 accepts then a refusal, a different price still works); self-cross trades
(documents the absence of self-trade prevention); two symbols never match.
Timestamps are supplied explicitly and increase by one per order, so no test
depends on the wall clock.

### 12.4 Benchmark

```bash
java -Xmx1g -cp out Benchmark        # 1,000,000 orders per engine run
java -cp out Benchmark 200000        # quick check (minimum 1,000)
```

It fails (exit 1) if `PlainBst` height ≠ key count on ascending input, or if the
Treap height exceeds `4·log2(N)`.

Measured for this manual, JDK 21, Windows, `-Xmx1g`, 200,000 orders:

```
| Scenario             |  Orders | Time (ms) | Orders/sec | Max height |
| Treap, random        | 200,000 |       499 |    400,219 |         18 |
| Treap, sequential    | 200,000 |       267 |    747,807 |         18 |
| PlainBst, sequential |  10,000 |         - |          - |     10,000 |

Trades: random=185,768  sequential=197,708
Rejected (level full): 0 / 0
PASS  Treap stayed logarithmic where the plain BST degenerated.
```

Reference run recorded in the earlier report (Linux container, JDK 21,
`--release 17`, seed 42, `-Xmx1g`, 1,000,000 orders):

| Scenario | Orders | Time | Throughput | Max height |
|---|---:|---:|---:|---:|
| Engine, random prices | 1,000,000 | 1,126 ms | 887,785 /s | 25 |
| Engine, sequential prices | 1,000,000 | 754 ms | 1,325,475 /s | 25 |
| Treap, ascending keys (no matching) | 1,000,000 | — | — | 52 |
| PlainBst, ascending keys | 10,000 | — | — | 10,000 |

Two explanations examiners ask for:

- Engine heights (~25) are **lower** than the pure insert test (52) because
  matching continuously empties and deletes levels, so the live book never holds
  all one million prices at once. The second measurement isolates the tree.
- Sequential is **faster** than random here — not because the tree is faster, but
  because strictly ascending prices cross almost immediately, so orders execute
  and leave instead of accumulating. Both ladders stay small.

Absolute throughput depends on the machine; because the seed is fixed, trade
counts and tree heights are identical across machines, which is what makes the
run comparable.

---

## 13. Edge cases and failure behaviour

| # | Case | Behaviour |
|---|---|---|
| 1 | `dequeue`/`peek` on an empty level | returns `null`, never throws |
| 2 | `enqueue` at capacity 1,024 | returns `false`; controller counts a rejection |
| 3 | Ring wrap-around after dequeue then enqueue | freed slot reused, FIFO preserved |
| 4 | Backing array doubles mid-life | order preserved, capacity limit unchanged |
| 5 | Duplicate price | joins the existing node's queue; no second node |
| 6 | Strictly ascending prices | Treap height stays ~3·log2(N) |
| 7 | Remove a price never inserted | no-op, size unchanged |
| 8 | Query an empty ladder | `findMin`/`findMax`/`find` return `null`, height 0 |
| 9 | Exact cross, equal quantities | one trade, **both** levels deleted |
| 10 | Partial fill | remainder stays at the queue front, level kept |
| 11 | No cross (bid < ask) | zero trades, both rest, spread intact |
| 12 | Two orders at one price | the earlier one trades first |
| 13 | Order crossing several levels | sweeps in price order, each level at its own price |
| 14 | Aggressor crossing far | prints at the resting price, not its own |
| 15 | Remainder after a full sweep | rests at its own limit price |
| 16 | Same trader on both sides | trades; no self-trade prevention by design |
| 17 | Crossing prices on different symbols | never match; books are independent |
| 18 | Zero or negative quantity | rejected at the gateway |
| 19 | Non-numeric price or quantity | rejected, message names the field |
| 20 | Wrong field count, empty id, bad side | rejected with an explanation |
| 21 | Quotes/control characters in an identifier | rejected, so hand-built JSON stays valid |
| 22 | Identifier longer than 20 characters | rejected |
| 23 | Mis-sized fill | quantity clamps at 0, never negative |
| 24 | Tape beyond 10,000 trades | oldest dropped; lifetime counter unaffected |
| 25 | Live feed unreachable / DNS blocked | silently falls back to the random walk |
| 26 | Live feed connects then silent > 5 s | treated as no data, falls back |
| 27 | Malformed or fragmented WebSocket frame | buffered or ignored; engine unaffected |
| 28 | `/api/book/{ticker}` for an unseen symbol | empty book, not 404 |
| 29 | `/api/trades?n=` missing or non-numeric | 50, clamped to 1–500 |
| 30 | `/api/simulate?n=` above 1,000,000 | clamped to 1,000,000 |
| 31 | Two concurrent `/simulate` calls | serialised on their own lock |
| 32 | Concurrent `/api/order` calls | each response carries exactly its own trades |
| 33 | Path traversal `/../src/Main.java` | normalised and refused with 404 |
| 34 | GET on a POST-only route | 405 with a JSON message |
| 35 | Malformed `PORT` | logged, falls back to 8080 |
| 36 | Very deep tree during `height()`/snapshot | iterative with an explicit stack; no stack overflow |
| 37 | `/api/events?since=abc` | 400 `since must be a whole number` |
| 38 | `/api/treap/X/bidz` | 400 `side must be bids or asks` |
| 39 | `/api/simulate?ticker=` invalid identifier | 400 with the gateway's message, not a dead connection |
| 40 | Empty ticker typed into the UI | field reverts to the previous ticker instead of blanking every panel |
| 41 | `data/trades.csv` absent or partially corrupt | skipped line by line; server still starts |
| 42 | Shutdown while trades are buffered | flush runs before the store is marked closed |

---

## 14. Base version vs fixed version

The repository also contains the earlier, faulty snapshot under
`hft-engine_base/hft-engine/`. It was kept so the commit history shows real
progression. These are the defects that version had, and the fix in the current
tree — useful to be able to explain precisely, because "what did you debug?" is
the most likely interview question.

| # | Symptom in the base version | Root cause | Fix |
|---|---|---|---|
| 1 | Treap visualisation often stopped updating; charts froze with no error | `Treap.toJson` wrote the `"left"`/`"right"` key and then returned early at the depth cap, emitting `"left":,"right":}` — invalid JSON. `response.json()` rejected and the front end swallowed the error. | Emit `null` for any child beyond `maxDepth` (`canDescend`), and return the literal `null` for an empty ladder. Covered by a TreapTest assertion at depths 1–6. |
| 2 | Structural events (inserts, rotations, deletes) never appeared in the timeline | The `EventLog` listener was created without the ticker, so events had a `null` ticker and `/api/events?ticker=X` filtered every one of them out. | Pass `ticker` and `side` into the listener factory in `OrderBook`. |
| 3 | The event list's **Clear** button did nothing | A local `const clearEvents = $('clearEvents')` shadowed the `clearEvents()` function, so the click handler was bound to the button element itself. | Rename the locals (`pauseBtn`, `clearBtn`) so the function name resolves. |
| 4 | **Pause** and **Simulation** buttons never changed their label | The code passed `'#pauseEvents'` to a helper that expects a bare id (`$('pauseEvents')`), so it returned `null` and the label update was skipped. | Use the id-only helper consistently at every call site. |
| 5 | `/api/events?since=abc` killed the request with no response | `Long.parseLong` was unguarded inside the handler; the exception escaped and the connection closed. | Catch `NumberFormatException` and return 400 with a JSON error. |

Related fixes in the current tree that are worth mentioning as robustness work:

- `Treap` and `PlainBst` height are iterative, so a degenerate tree cannot cause
  `StackOverflowError`.
- `/api/stats/{ticker}` uses lifetime per-ticker totals from `TradeStore` instead
  of re-summing the last 100 tape entries (which made volume fall and returned the
  *oldest* match as "last price", because the tape is newest-first).
- `WebServer` measures accepted orders/second over a real sampling window
  (`ordersPerSec` was previously the constant 1000).
- `TradeStore.close()` flushes **before** setting `closed`, so the final buffered
  second of trades is not lost.
- `/api/simulate` catches an invalid `?ticker=` and answers 400 instead of
  dropping the connection.
- `OrderBook`/`TraderStats` avoid duplicate event emission and locale-dependent
  decimal formatting (`Locale.ROOT`, so `12,5` can never be emitted into JSON).

---

## 15. Limitations and future work

State these plainly in a report or viva; they are true and they are the natural
next steps.

| Limitation | Why it exists | What would change it |
|---|---|---|
| Treap bound is **expected**, not worst case | Simplicity: one comparison and one rotation per level, no colour/height bookkeeping | AVL or red-black tree if a worst-case guarantee is required |
| One global lock | Correct and ample at this scale; matching is microseconds | Shard locks per symbol; then a lock-free queue per book |
| CSV storage, no database | No dependencies allowed; history is a teaching artefact | SQLite/Postgres with an append-only trade table |
| Open-book state is not persisted | Restart is meant to be a cold start plus seeded demo data | Snapshot the book on shutdown and replay it on boot |
| Limit orders only | Scope | Market, stop, iceberg, IOC/FOK types |
| No self-trade prevention, fees, risk limits, settlement | Scope | Trader-side checks before admission, fee schedule on the trade |
| Integer ticks only | Avoids floating-point drift and duplicate price levels | Fixed-point decimals with an explicit scale |
| Tape keeps the last 10,000 executions | Bounded memory during a million-order run | Durable store with paging |
| `orders.csv` is never written | `recordOrder` is not wired in | Call it from the controller's accepted path |
| Live feed is BTCUSDT only | One public stream, no API key | Per-symbol subscriptions, or a REST poll |
| No order cancel/replace | Scope | Order-id index per book; cancel is a queue removal |
| Event ids from a static non-atomic counter | Fine because events are produced under the engine lock | `AtomicLong` |
| `TradeStore.getTrades()` returns a copy without synchronising | `/api/candles` may copy the list at the moment a trade is appended | Synchronise the getter, or publish an immutable snapshot on write |

Academic honesty note for the report: the engine is a faithful model of the
matching *rules*, not production trading software. It is not connected to any
market, it sends no orders anywhere, and the "live price" is only a reference
number used to centre simulated flow.

---

## 16. Viva / interview question bank

Short answers, in the order they are usually asked.

**1. What does the project do, in one sentence?**
It is an in-memory limit-order exchange: it accepts buy and sell orders, keeps
them ordered by price and arrival time, and matches crossing orders into trades,
with a browser UI and an HTTP API on top.

**2. Why a Treap and not a plain BST?**
A plain BST is only O(log N) if keys arrive in random order. Prices trend, so they
arrive nearly sorted, and a BST fed sorted keys becomes a linked list — height N,
every operation O(N). A Treap gives each node a random priority and maintains a
max-heap on it, so the shape depends on the random priorities, not on arrival
order. Expected height ~3·log2(N) even for ascending input.

**3. Why not AVL or red-black?**
They give the same bound but worst-case instead of expected, at the cost of
height/colour fields and several rebalancing cases. A Treap rebalances with one
comparison and one rotation per level, which is far less code to get right. The
trade-off I state honestly: the Treap bound is expected, not guaranteed.

**4. Prove the Treap actually helps.**
`Benchmark` part (c) inserts the same ascending keys into both trees. At 10,000
keys the plain BST has height exactly 10,000 (a linked list); at 1,000,000 keys
the Treap has height 52, against an ideal of 20.

**5. Why is height the right metric?**
Every search, insert and delete costs one comparison per level, so height
determines the cost of every operation. Comparing throughput alone would mix in
allocation and GC noise.

**6. Why a circular queue inside the node?**
Time priority is FIFO within a price. A ring gives O(1) enqueue, dequeue and peek
with no per-operation allocation, and the freed slot is reused. A `LinkedList` is
also FIFO but costs a node object and two pointer chases per order, and it
scatters orders across the heap — the matcher touches a queue head on every trade,
so locality matters. A plain array shifted on removal is O(n) per dequeue.

**7. Why is the queue capacity fixed at 1,024?**
Because refusing an order at a saturated level is correct exchange behaviour, not
a bug. It also bounds memory per level. `enqueue` returning `false` is how the
engine says "reject", and the controller increments `rejectedCount`.

**8. Why does the backing array start at 8 and grow?**
One million distinct levels each eagerly allocating 1,024 slots would be about
8 GB of mostly empty arrays. Growth is amortised O(1) and happens at most ten
times per level; externally nothing changes — `isFull()` is true at exactly 1,024.

**9. Why two Treaps per book instead of one?**
Best bid is the highest buy, best ask the lowest sell — opposite ends. Two trees
make each a single-side walk (`findMax`/`findMin`). One combined tree would make
"best bid" mean "largest key that happens to be a bid", which is a scan, and it
would double N per side.

**10. Walk through the matching loop.**
While best bid ≥ best ask: peek the front order of each side, trade
`min(qty)`, reduce both, dequeue whichever filled, and delete any level whose
queue emptied — then re-read the best prices. Continue until they no longer cross.

**11. How is the trade price decided?**
At the **resting** (older) order's limit price: the aggressor gets price
improvement. A buy willing to pay 120 hitting a resting ask at 100 prints at 100.
The older order is the one with the smaller arrival timestamp.

**12. What happens to a partially filled order?**
It stays at the **front** of its level's queue with its reduced quantity, keeping
its original time priority — so it still trades before anything that arrived later
at that same price. `peek` before `dequeue` is what makes this possible.

**13. Show a multi-level sweep.**
Asks 30@100, 30@105, 30@110; a BUY 100 @110 produces three trades at 100, 105 and
110 — 90 filled — and 10 rests at the bid price 110. Each level is consumed at its
own price, never at the aggressor's.

**14. Where is arrival time stamped, and why there?**
In `OrderGateway.parse`, using `System.nanoTime()`. Time priority must reflect
when the exchange received the order, not when the caller built it, and
`nanoTime` is monotonic so a clock adjustment cannot reorder the book.

**15. Why is the tape an `ArrayDeque` with a cap?**
New trades append at one end, and once 10,000 are held the oldest is dropped from
the other — amortised O(1) at both ends, no per-element node. `ArrayList` removal
from the front is O(n), which at a million orders would dominate the run. A
separate counter keeps lifetime totals exact.

**16. How is thread safety achieved?**
One engine-wide lock: `processOrder` is synchronized, and it is the only mutation
entry point. `/api/order` additionally holds that lock across submit-and-read so
the trades it reports belong to that request. `/simulate` has its own lock.
`EventLog` and `TradeStore` synchronize their own methods; the trader and
per-ticker maps are `ConcurrentHashMap`.

**17. Is one lock a problem?**
At this scale, no: the matched work per order is microseconds, and correctness is
worth more than parallelism in an academic engine. The real fix in production is
per-symbol locking or a single-threaded matching core per symbol with a lock-free
hand-off queue.

**18. Why is `price` an `int`?**
Two orders "at the same price" must hit the same node. Floating point makes
`1200.0` and `1199.9999999` distinct keys, silently creating duplicate levels;
integers also model ticks exactly and keep the key comparison cheap.

**19. What stops a malicious order from breaking the JSON?**
The gateway restricts trader and ticker to letters, digits, `.`, `_`, `-` and at
most 20 characters, so an identifier can never contain a quote or control
character. That is what makes hand-built JSON safe without a library.

**20. How does persistence work?**
`TradeStore` appends trades and orders to CSV, flushing every second and once on
shutdown. On startup it loads both files into memory and rebuilds trader stats and
per-ticker totals, so history, candles and the leaderboard survive a restart.
Open-book state is deliberately not persisted.

**21. Why doesn't the chart go blank on a fresh start?**
`Seeder` submits ~500 orders through the normal gateway when no book exists, so
there is always something to draw. It is disabled with `--no-seed`.

**22. What does the event timeline add?**
It is the data structure made observable: `NODE_INSERT`, `ROTATE_LEFT`,
`ROTATE_RIGHT`, `NODE_DELETE`, `ENQUEUE`, `DEQUEUE`, `ORDER_UPDATE`, `TRADE`,
`REJECT` — a live log of structural changes bounded by a 5,000-slot ring. It is
how you *see* that a rotation happened rather than trusting the claim.

**23. What is the live price feed, and what if it fails?**
A public WebSocket for BTCUSDT. The latest price sits in an `AtomicInteger` with
a timestamp; if it is older than 5 s the feeder falls back to the per-symbol random
walk. No network is a fully supported configuration, and the UI shows whether the
price is `LIVE` or `SIMULATED`. It only centres simulated order flow — nothing is
routed to a real market.

**24. How did you test it?**
123 assertions across three suites: the ring (33), the Treap incl. full BST and
heap invariant re-verification after each of 300 removals and the JSON depth
boundary (43), and matching semantics (47). Plus a benchmark that fails the build
if the plain BST does not degenerate or the Treap stops being logarithmic.

**25. What was the hardest bug?**
The frozen tree view: the JSON was invalid only for nodes on the last visible
level, so it looked like a rendering problem. The real cause was `toJson` writing
a key and then returning early at the depth cap. The fix emits `null` for children
past the depth, and a test now pins the behaviour at depths 1–6.

**26. What would you do with two more weeks?**
Per-symbol locking and a matching thread per symbol; cancel/replace with an
order-id index; a proper storage layer with replay-based recovery; AVL as a
compile-time alternative to compare worst-case behaviour; and fractional pricing
with an explicit decimal scale.

---

## 17. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `UnsupportedClassVersionError` / `class file version 61` | Running with an older JVM than the compiler | Use the same JDK for `javac` and `java`; set `JAVA_HOME` |
| `javac: invalid target release` | JDK older than 17 | Install JDK 17+ (or unzip the bundled `jdk21.zip`) |
| `Error: Could not find or load main class Main` | Wrong working directory or `-cp out` omitted | Run from the project root with `-cp out` |
| `Address already in use` / `BindException` | Port occupied (often a previous run) | `PORT=8100 java -cp out Main`, or stop the old process |
| Page loads but no styles/charts (404s) | `web/` not found from the working directory | Start from the project root, or keep `web/` beside it |
| `data/trades.csv` appears in an unexpected place | `data/` is relative to the working directory | Run from the project root |
| UI shows `SIMULATED` instead of `LIVE` | No outbound network, or the stream is blocked/stale | Expected; the random walk is the fallback |
| Book looks empty after a restart | Open-book state is not persisted | Expected; `Seeder` recreates demo liquidity (or send orders) |
| Numbers in `/api/stats/{ticker}` look global | Accepted/rejected are engine-wide, not per-symbol | Compare with `/api/stats` |
| `bestBid` is `null` in JSON | That side of the book is empty | Handle `null` in the client |
| Old history keeps growing | CSV is append-only | Delete `data/trades.csv` for a clean slate |
| Benchmark fails with an exit code | PlainBst did not degenerate, or the Treap exceeded `4·log2(N)` | Re-run; if it repeats, the tree code changed |
| Chinese/Unicode identifiers rejected | Gateway allows only `[A-Za-z0-9._-]` | By design, for JSON safety |
| `curl` in PowerShell behaves oddly | `curl` is an alias for `Invoke-WebRequest` | Use `curl.exe` |

---

## 18. Evaluation demo script

A five-minute run that shows every claim, in an order that cannot go wrong.

1. **Show the tree, not the slides.**
   ```bash
   java -cp out Main demo
   ```
   Point at the printout: resting liquidity → cross → sweep → price improvement
   prints at 103 not 120 → four distinct rejections.

2. **Show the tests.**
   ```bash
   java -cp out QueueTest && java -cp out TreapTest && java -cp out MatchingTest
   ```
   `ALL PASS` three times, 33 + 43 + 47 assertions.

3. **Show the structural claim.**
   ```bash
   java -cp out Benchmark 200000
   ```
   Read the last table aloud: Treap height 18 against `log2(200,000) = 18`; the
   plain BST at 10,000 keys has height 10,000.

4. **Start the server and show the terminal.**
   ```bash
   PORT=8080 java -cp out Main
   ```
   Open `http://localhost:8080`. Walk the panels: stat tiles, candles, depth,
   **two SVG trees with live heights**, book table, order form, tape.

5. **Submit a crossing pair by hand.** Sell `alice,SIM,SELL,100,10`, then buy
   `bob,SIM,BUY,100,10`. The trade appears in the tape, both levels disappear from
   the book, and the event timeline shows `ENQUEUE` → `TRADE` → `DEQUEUE` →
   `NODE_DELETE`.

6. **Fire the event timeline.** Run a 100,000-order simulation and watch the
   timeline update every 500 ms while the tree view redraws — this is the panel
   that used to freeze, and it is the direct evidence of fix #1.

7. **Show persistence.** Stop the server (Ctrl+C), start it again: trades,
   candles and the trader leaderboard are still there. Then `--no-seed` to show
   the engine starting empty on request.

8. **Open `http://localhost:8080/landing.html`** for the project overview page.

If asked "what is the architecture?" draw the five boxes from §3.1 from memory:
web → gateway → controller → (Treap → node → ring → order) → store.

---

## 19. Glossary

| Term | Meaning here |
|---|---|
| **Aggressor / taker** | The incoming order that crosses the spread |
| **Ask** | A sell order; the lowest ask is the best ask |
| **Bid** | A buy order; the highest bid is the best bid |
| **Book** | All resting orders for one symbol, split into bids and asks |
| **Cross** | `bestBid ≥ bestAsk`, the condition that triggers matching |
| **Expected O(log N)** | Average over random priorities, not a worst-case guarantee |
| **Level** | All orders at one price; one `PriceNode` + one `CircularQueue` |
| **Limit order** | "Buy/sell at this price or better" |
| **Max-heap property** | Every node's priority ≥ both children's |
| **Price improvement** | Executing better than the aggressor's limit, at the resting price |
| **Price-time priority** | Best price first; earliest arrival first within a price |
| **Priority (Treap)** | Random number fixed at node creation; drives the tree shape |
| **Resting order / maker** | An order sitting in the book waiting to be hit |
| **Spread** | `bestAsk − bestBid` |
| **Sweep** | One order consuming several price levels |
| **Tape** | The chronological list of executions |
| **Tick** | The smallest price increment; here one integer unit |
| **Treap** | Tree + heap: BST on price, max-heap on random priority |
| **Time priority** | FIFO within a price level |

---

*This manual describes the fixed version of the engine in `src/` and `web/`. The
earlier faulty snapshot under `hft-engine_base/hft-engine/` is kept only so the
commit history shows the progression documented in §14.*
