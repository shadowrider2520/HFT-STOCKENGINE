# HFT Order Matching Engine

An in-memory stock-exchange matching engine with price-time priority, a console
runner, a browser UI and a container image. Java 17, JDK only, no external
libraries anywhere in the project.

```
Price levels ........ Treap (randomised BST)   expected O(log N)
Orders per level .... array-based circular queue   O(1) FIFO
Matching ............ price-time priority
```

---

## 1. Contents

```
hft-engine/
├── src/
│   ├── Order.java                 one limit order
│   ├── CircularQueue.java         FIFO queue of orders at one price
│   ├── PriceNode.java             a price level (Treap node + its queue)
│   ├── Treap.java                 the price ladder (with TreapListener for events)
│   ├── Trade.java                 an execution record
│   ├── OrderBook.java             two ladders (bids, asks) for one symbol
│   ├── MatchingController.java    the exchange: books, matching loop, tape
│   ├── OrderGateway.java          parsing and validation
│   ├── PriceFeeder.java           reference-price interface
│   ├── RandomWalkFeeder.java      synthetic price source
│   ├── LiveFeeder.java            exchange WebSocket, falls back to the walk
│   ├── OrderSimulator.java        load generator (RANDOM / SEQUENTIAL)
│   ├── PlainBst.java              unbalanced baseline, benchmark only
│   ├── EngineEvent.java           event record: structure changes
│   ├── EventLog.java              ring buffer of EngineEvent
│   ├── TradeStore.java            CSV persistence + history load
│   ├── Seeder.java                demo history when store is empty
│   ├── TraderStats.java           per-trader positions
│   ├── WebServer.java             HTTP API + static files
│   ├── Main.java                  entry point (server, or `demo`)
│   └── tests/
│       ├── QueueTest.java
│       ├── TreapTest.java
│       ├── MatchingTest.java
│       └── Benchmark.java
├── web/
│   ├── index.html                 the UI (vanilla HTML + JS, no framework)
│   ├── style.css
│   ├── app.js                     polling + state
│   ├── charts.js                  canvas/SVG charts, no libraries
│   └── landing.html               marketing/overview page, links to the terminal
├── data/                          (created at runtime: trades.csv, orders.csv)
├── Dockerfile
└── README.md
```

Everything is in the default package, so there are no package statements and no
directory-to-package mapping to maintain.

---

## 2. Build and run

### Requirements
JDK 17 or newer. Nothing else: no Maven, no Gradle, no network access at build
time.

### Compile

```bash
cd hft-engine
mkdir -p out
javac -d out src/*.java src/tests/*.java
```

### Run the web server

```bash
java -cp out Main
# then open http://localhost:8080
```

The port comes from the `PORT` environment variable and defaults to 8080:

```bash
PORT=9000 java -cp out Main
```

Disable demo-data seeding on startup:

```bash
java -cp out Main --no-seed
```

### Run the console demo

```bash
java -cp out Main demo
```

Prints a short worked example (resting liquidity, a simple cross, a multi-level
sweep, the price-improvement rule, four kinds of rejected input), then two
one-million-order load runs.

### Run the tests

```bash
java -cp out QueueTest
java -cp out TreapTest
java -cp out MatchingTest
java -cp out Benchmark          # add a number for a smaller run: Benchmark 200000
```

Each prints one line per assertion and exits non-zero if anything fails, so they
chain:

```bash
java -cp out QueueTest && java -cp out TreapTest && java -cp out MatchingTest && echo "ALL SUITES PASS"
```

On a machine with a small default heap, give the benchmark room:

```bash
java -Xmx1g -cp out Benchmark
```

---

## 3. Why these data structures

### Treap for the price ladder

The matcher needs four operations on prices: insert a level, delete an emptied
level, find the best price, and find an exact price so an order joins the level
that already exists. A plain BST gives all four in O(log N) **only if keys
arrive in random order**. Market prices do not arrive randomly; they trend. Feed
a BST ascending keys and it becomes a linked list of height N, and every
operation degrades to O(N).

A Treap gives each node a random priority at birth and maintains a max-heap on
those priorities alongside the BST order on price. The shape of the tree is then
determined by the random priorities, **not by the insertion order**, so sorted
input produces the same expected height as shuffled input: about 3·log2(N).

Alternatives considered:

| Structure | Why not |
|---|---|
| AVL / red-black | Same bound but worst-case; costs extra fields and several rebalancing cases. A Treap rebalances with one comparison and one rotation per level. |
| Skip list | Similar expected bounds, but multiple nodes and pointer arrays per key — far more memory at a million levels. |
| Hash map price→queue | O(1) exact lookup but cannot answer "what is the best price", which the matcher asks on every order. Finding a max in a hash map is O(N). |
| Sorted array | O(log N) search, but O(N) insert and delete from shifting. |

The honest caveat for the report: the Treap bound is **expected**, not worst
case. A pathological draw of priorities could still build a tall tree. The
probability is vanishingly small, but unlike AVL it is not zero.

### Circular queue for orders at one price

Time priority requires strict FIFO within a price level. A linked list is FIFO
too, but costs a node object and two pointer chases per order and scatters
orders across the heap; the matcher touches a queue head on every trade, so
locality matters. A plain array shifted on removal is O(n) per dequeue. Wrapping
the indices modulo the array length turns the array into a ring: the head simply
advances and freed slots are reused. O(1) enqueue, dequeue and peek, no
allocation per operation.

The capacity is fixed on purpose. A real exchange refuses an order when a level
is saturated rather than growing without bound, so `enqueue` returning `false` at
`LEVEL_CAPACITY` (1024) **is** the required behaviour.

Implementation detail: the backing array starts at 8 slots and doubles up to the
capacity. Nothing observable changes — `isFull()` still becomes true at exactly
1024 — but one million distinct price levels each eagerly allocating a 1024-slot
array would be roughly 8 GB of mostly empty arrays.

### Two Treaps per book, not one

Best bid is the **highest** buy price, best ask the **lowest** sell. With two
trees each is a straight walk down one side (`findMax`, `findMin`). One combined
tree would turn "best bid" into "the largest key that happens to be a bid",
which degenerates into a scan. Splitting also halves N on each side.

---

## 4. Benchmark results

Measured on a Linux container, JDK 21 (`--release 17`), seed 42, `-Xmx1g`.
Re-run with `java -Xmx1g -cp out Benchmark`.

| Scenario | Orders | Time | Throughput | Max Treap height |
|---|---:|---:|---:|---:|
| Engine, random prices | 1,000,000 | 1,126 ms | 887,785 orders/s | 25 |
| Engine, sequential prices | 1,000,000 | 754 ms | 1,325,475 orders/s | 25 |

Tree height on strictly ascending keys — the result that justifies the design:

| Structure | Keys | Height | vs log2(N) |
|---|---:|---:|---|
| **Treap** | 1,000,000 | **52** | 2.6× the ideal 20 |
| **PlainBst** | 10,000 | **10,000** | height == N, a linked list |
| Treap (same 10,000 keys) | 10,000 | 32 | 2.4× the ideal 14 |

`PlainBst` is measured at only 10,000 keys because at one million its height
would be one million. 10,000 is already enough to show `height == N` exactly.

Trades: 945,140 (random) and 988,009 (sequential). Rejections: 0 in both.

Two things worth explaining in the report:

- The **engine** heights in the first table (around 25) are much lower than the
  52 in the second, because matching continuously empties and deletes levels, so
  the live book never holds all one million prices at once. The second table
  isolates the tree by inserting without matching, which is the fair structural
  comparison.
- Sequential mode is *faster* than random mode here. That is not the tree being
  faster; it is that strictly ascending prices cross almost immediately, so
  orders execute and leave rather than accumulating, keeping both ladders small.

---

## 5. Functional features

**Engine**

- Limit orders on both sides, many symbols, each with an independent book.
- Price-time priority: best price first, and within a price, earliest arrival
  first.
- Partial fills. The remainder stays at the **front** of its queue and keeps its
  original time priority.
- Multi-level sweeps: one large order walks up (or down) as many levels as it
  needs, filling at each level's own price.
- Price improvement: an execution always prints at the **resting** (older)
  order's limit price, so an aggressor crossing at 120 into an ask resting at 100
  pays 100.
- Automatic level lifecycle: a level is created on first order and deleted from
  the Treap the moment its queue empties, so `bestBid`/`bestAsk` never skip dead
  levels.
- Capacity rejection: a level at 1024 resting orders refuses further orders and
  increments a rejection counter.
- Capped trade tape: the last 10,000 executions are retained; older ones are
  dropped so a long run cannot exhaust memory. A separate uncapped counter keeps
  the lifetime total.
- Thread safety: all state mutation goes through one synchronized entry point.

**Gateway**

- Wire format `traderId,ticker,BUY|SELL,price,qty`, or an equivalent JSON object.
- Validates field count, empty fields, side spelling, integer parsing, positive
  price and quantity, upper bounds, and a safe character set for identifiers.
- Arrival timestamps are stamped at the gateway, which is what time priority is
  measured from.

**Price feeds**

- `RandomWalkFeeder`: per-symbol random walk, step in [-2, +2], floored at 1.
- `LiveFeeder`: public exchange WebSocket, latest price in an `AtomicInteger`,
  automatic fallback to the random walk on any error or after 5 s of silence.
- The reference price only **centres simulated order flow**. Nothing is ever
  routed to a real market; the engine in this repository is itself the exchange.

**Simulator**

- `RANDOM` mode (realistic flow) and `SEQUENTIAL` mode (the adversarial ascending
  case).
- Seeded (default 42) so every run is reproducible.
- Reports time, throughput, trades, rejections, and both ladder heights.

**Persistence & History**

- `TradeStore`: append-only CSV (`data/trades.csv`, `data/orders.csv`). On startup
  loads both into memory so history, candles and trader stats survive restarts.
- `Seeder`: if the store is empty at startup, generates ~500 demo orders through
  the normal gateway so charts and tables are never blank on first open.
- `TraderStats`: per-trader positions computed from trades (trades, bought/sold
  qty, net position, avg buy/sell, cash flow, last active).

**Event system**

- `EngineEvent`: records structural changes (NODE_INSERT, ROTATE_LEFT,
  ROTATE_RIGHT, ENQUEUE, DEQUEUE, NODE_DELETE, ORDER_UPDATE, TRADE, REJECT).
- `EventLog`: synchronized ring buffer (last 5,000 events), queryable via
  `/api/events?since=ID&ticker=X`.
- `TreapListener` hook in `Treap` emits events at insert, rotate, enqueue and
  delete points. **Listener is `null` during Benchmark and `/simulate` runs** so
  logging 1M orders does not distort timings.

**Web UI v2 (trading-terminal style, vanilla JS/SVG/canvas, no libraries)**

1. **Top bar**: ticker selector, live price (with up/down arrow), name box
   (remembered in `localStorage`), feed status (LIVE or SIMULATED).
2. **Stat tiles**: best bid, best ask, spread, last price, volume, trades, bid/ask
   Treap height, orders/s, accepted/rejected.
3. **Charts** (all poll every 1 s):
   - **Price chart**: candlesticks (5 s buckets) with volume bars below.
   - **Depth chart**: cumulative step areas, bids left and asks right, mid-price line.
   - **Treap view**: two SVG trees (bids, asks), top 5 levels, node label = price,
     badge = orders at that level, radius scaled by quantity. Nodes touched by
     newest events flash for 1 s. Shows live height.
   - **Trader leaderboard**: horizontal bars of net position or cash flow per trader.
4. **Order book table**: two panels side by side, asks (orange) and bids (blue),
   columns Price, Qty, Orders, with a depth-fill bar behind each row.
5. **Order form**: trader name, ticker, BUY/SELL toggle, price, qty, submit.
6. **Recent trades table**: time, ticker, price, qty, buyer, seller.
7. **Engine event timeline**: live table of data-structure changes, polls
   `/events?since=lastId` every 500 ms, newest on top, max 200 rows, type filter
   chips, pause/resume button.
8. **Simulation panel**: n input, mode select (random/sequential), Run button,
   result stats. While a simulation runs, event logging is off.
9. **Landing page**: `web/landing.html`, a standalone overview of the engine that
   links back to the terminal and shows a live engine pulse when the API is up.
   Also reachable from the terminal's "About the engine" link.

**Contrast rules**: Buy/bid = **blue** `#2563eb`, sell/ask = **orange** `#ea580c`
everywhere. Blue/orange stays distinguishable for color-blind users. Also label
with "BID"/"ASK" text and `B`/`S` badges.

---

## 6. Edge cases handled

| # | Case | Behaviour |
|---|---|---|
| 1 | Dequeue or peek on an empty level | returns `null`, never throws |
| 2 | Enqueue into a level at capacity | returns `false`; counted as a rejection |
| 3 | Ring wrap-around after dequeue then enqueue | freed slot is reused, FIFO preserved |
| 4 | Backing array doubling mid-life | order preserved, capacity limit unchanged |
| 5 | Duplicate price | joins the existing node's queue; no second node |
| 6 | Strictly ascending prices (the BST killer) | Treap height stays ~3·log2(N) |
| 7 | Removing a price that was never inserted | no-op; size unchanged |
| 8 | Queries against an empty ladder | `findMin`/`findMax`/`find` return `null`, `height()` is 0 |
| 9 | Exact cross, equal quantities | one trade; **both** levels deleted |
| 10 | Partial fill | remainder stays at the queue front, level kept |
| 11 | No cross (bid < ask) | zero trades, both orders rest, spread intact |
| 12 | Two orders at the same price | earlier one trades first |
| 13 | One order crossing several levels | sweeps them in price order, filling at each level's price |
| 14 | Aggressive order crossing far through | executes at the resting price, not its own |
| 15 | Unfilled remainder after a full sweep | rests at its own limit price |
| 16 | Self-cross (same trader both sides) | trades; there is **no** self-trade prevention, by design |
| 17 | Crossing prices on different symbols | never match; books are independent |
| 18 | Zero or negative quantity | rejected at the gateway (would otherwise trade forever) |
| 19 | Non-numeric price or quantity | rejected with a message naming the field |
| 20 | Wrong field count, empty trader/ticker, bad side | rejected with an explanatory message |
| 21 | Identifier with quotes or control characters | rejected, so hand-built JSON cannot be broken |
| 22 | Over-long identifier (>20 chars) | rejected |
| 23 | Over-fill of an order by a mis-sized fill | quantity clamps at 0, never negative |
| 24 | Trade tape exceeding 10,000 | oldest dropped; lifetime counter still accurate |
| 25 | Live feed unreachable, or DNS/socket blocked | silently falls back to the random walk |
| 26 | Live feed connects then goes silent >5 s | treated as no data; falls back |
| 27 | Malformed or fragmented WebSocket frame | buffered or ignored; engine unaffected |
| 28 | `/book/{ticker}` for an unseen symbol | empty book, not a 404 |
| 29 | `/trades?n=` missing or non-numeric | falls back to 50, clamped to 1..500 |
| 30 | `/simulate?n=` above 1,000,000 | clamped to 1,000,000 |
| 31 | Two concurrent `/simulate` calls | serialised on their own lock |
| 32 | Concurrent `/order` calls | each response reports exactly its own trades |
| 33 | Path traversal (`/../src/Main.java`) | normalised and refused with 404 |
| 34 | GET on a POST-only route | 405 with a JSON message |
| 35 | Malformed `PORT` env var | logged, falls back to 8080 |
| 36 | Deep tree during `height()` or a snapshot | iterative with an explicit stack; no stack overflow |

---

## 7. Test cases and how to run them

### QueueTest — 33 checks

```bash
java -cp out QueueTest
```

| Case | What it proves |
|---|---|
| FIFO order | three orders come back in arrival order |
| Full rejection | the 4th order into a capacity-3 queue returns `false`, size stays 3 |
| Wrap-around | after one dequeue, a new enqueue reuses index 0 and still comes out last |
| Empty queue | `dequeue()` and `peek()` return `null` |
| Peek is non-destructive | size unchanged, same object returned twice |
| Flags | `size`, `isEmpty`, `isFull` track every operation |
| Growth | 64 enqueues across several array doublings preserve FIFO exactly |
| Total quantity | sums correctly, including after a wrap |

### TreapTest — 43 checks

```bash
java -cp out TreapTest
```

| Case | What it proves |
|---|---|
| 10,000 ascending prices | height < 60 (actual: 32) — the headline result |
| 10,000 random prices | height < 60 (actual: 33) — insensitive to input order |
| findMin / findMax | correct best prices, and correct again after removals |
| find | locates present prices, returns `null` for absent ones |
| Duplicate price | one node, three queued orders, FIFO preserved, quantities summed |
| Remove every node | BST order **and** heap property re-verified after *each* of 300 removals |
| Size tracking | exact after 1,000 inserts and 500 removals |
| Remove a missing price | no-op |
| Empty treap | every query returns `null`/0 without throwing |
| Level capacity | 1024 accepted, the 1025th refused |
| JSON depth boundary | depths 1..6 emit no empty value, depth 3 is capped at 7 nodes, an empty ladder serialises as `null` |

The two invariants are checked by full traversals: an in-order walk must be
strictly ascending (BST), and every node's priority must be ≥ both children's
(heap).

### MatchingTest — 47 checks

```bash
java -cp out MatchingTest
```

| Case | Setup | Expected |
|---|---|---|
| Exact cross | SELL 50 @100, then BUY 50 @100 | 1 trade @100; **both** levels removed |
| Partial fill (buy larger) | SELL 30 @100, then BUY 80 @100 | 1 trade of 30; ask level gone; 50 rests at the bid queue front |
| Partial fill (sell larger) | SELL 90 @100, then BUY 40 @100 | bid level gone; 50 rests on the ask |
| No cross | BUY @99, SELL @101 | 0 trades, both rest, spread = 2 |
| FIFO at one price | SELL "EARLY" then "LATE" @100, then BUY 20 | EARLY trades; LATE still resting |
| Multi-level sweep | asks 30@100, 30@105, 30@110; BUY 100 @110 | 3 trades at 100, 105, 110; 90 filled; 10 rests @110 |
| Price improvement | SELL 10 @100, then BUY 10 @120 | trade prints @100 |
| Price improvement (mirror) | BUY 10 @120, then SELL 10 @100 | trade prints @120 |
| Queue-full rejection | 1024 buys @100, then one more | rejection counter = 1; accepted count unchanged; a different price still works |
| Self-cross | same trader both sides | trades (documents the absence of self-trade prevention) |
| Two symbols | SELL @100 on AAA, BUY @100 on BBB | 0 trades; books independent |

Timestamps in this suite are supplied explicitly and increase by one per order,
so arrival order never depends on the wall clock.

### Benchmark

```bash
java -Xmx1g -cp out Benchmark          # 1,000,000 orders
java -cp out Benchmark 200000          # quicker run
```

Fails with a non-zero exit if `PlainBst` height ≠ key count, or if the Treap
height exceeds 4·log2(N).

---

## 8. HTTP API

All API paths are under `/api` (except `GET /` which serves the UI).

| Method | Path | Behaviour |
|---|---|---|
| GET | `/` | serves `web/index.html` and other files under `web/` |
| POST | `/api/order` | body `traderId,ticker,BUY\|SELL,price,qty` or JSON; returns `{"accepted":bool,"trades":[...]}`; 400 on bad input |
| GET | `/api/book/{ticker}` | `{"ticker":"X","bids":[{"price":..,"qty":..,"orders":..}],"asks":[...]}` top 10 levels, bids high→low, asks low→high |
| GET | `/api/trades?n=50` | last n trades JSON array, newest first |
| POST | `/api/simulate?n=100000&mode=random\|sequential` | runs the simulator, returns stats JSON (cap n at 1,000,000) |
| GET | `/api/price/{ticker}` | latest feeder price and source |
| GET | `/api/health` | `ok` |
| GET | `/api/stats` | engine-wide counters |
| GET | `/api/events?since=ID&ticker=X` | engine events with id > ID (max 200), oldest first |
| GET | `/api/treap/{ticker}/{bids\|asks}?depth=5` | nested JSON of the Treap top levels: `{price,priority,orders,left,right}` |
| GET | `/api/stats/{ticker}` | best bid/ask, spread, last price, volume, trades, bid/ask Treap height, accepted/rejected, orders/s |
| GET | `/api/candles/{ticker}?bucket=5` | OHLCV buckets (seconds), last 120 |
| GET | `/api/depth/{ticker}` | cumulative bid and ask arrays for the depth chart |
| GET | `/api/traders` | leaderboard: per-trader stats |
| GET | `/api/trader/{id}` | one trader: stats + their last 50 trades |

---

## 9. Docker

```bash
cd hft-engine
docker build -t hft-engine .
docker run --rm -p 8080:8080 hft-engine
# http://localhost:8080
```

The image is a two-stage build: a JDK stage compiles `src/*.java` and `src/tests/*.java`,
and the runtime stage is a JRE plus the class files and `web/`. The server binds
`0.0.0.0` and reads `PORT`, which is what container platforms require.

Note: the Dockerfile compiles tests too, but the production image only needs `Main`
and the core classes.

---

## 10. Deployment

### 10.1 Read this first: Vercel cannot host the engine

Vercel has no Java runtime, and its functions are stateless and short-lived.
This engine is a long-running process whose entire state — every book, every
Treap, the trade tape — lives in memory. On Vercel each request would hit a cold,
empty exchange, so there would be no book to match against.

So the deployment is split:

```
  Vercel  ──────────────►  Render (or Railway / Fly.io)
  web/index.html           the Docker container running Main
  (static hosting)         holds all engine state
```

If you only need one URL and do not specifically need Vercel, **skip to 10.3**:
Render serves the UI and the API together, which is simpler and is what the
Dockerfile is built for.

### 10.2 Backend on Render, frontend on Vercel

**Step 1 — push to GitHub**

```bash
cd hft-engine
git init
git add .
git commit -m "HFT order matching engine"
git branch -M main
git remote add origin https://github.com/<you>/hft-engine.git
git push -u origin main
```

**Step 2 — deploy the engine on Render**

1. render.com → **New** → **Web Service**.
2. Connect the repository.
3. Settings:
   - Language / Runtime: **Docker**
   - Dockerfile Path: `./Dockerfile`
   - Instance Type: Free is enough
   - Health Check Path: `/api/health`
   - Do **not** set a Docker command; the image already has one.
4. **Create Web Service**. The first build takes a few minutes.
5. Note the URL, e.g. `https://hft-engine-a1b2.onrender.com`.
6. Verify: `curl https://hft-engine-a1b2.onrender.com/api/health` → `ok`.

Free Render instances sleep after inactivity. The first request after a sleep
takes 30–60 s and **the engine restarts empty**, because all state is in memory.
That is expected behaviour for this project, not a bug. The Seeder repopulates
demo history automatically.

**Step 3 — create `vercel.json`**

The UI calls `/api/order`, `/api/book/...` and so on as same-origin paths. On Vercel
those paths must be proxied through to Render. Create `web/vercel.json`:

```json
{
  "rewrites": [
    { "source": "/api/order",           "destination": "https://hft-engine-a1b2.onrender.com/api/order" },
    { "source": "/api/book/:ticker",    "destination": "https://hft-engine-a1b2.onrender.com/api/book/:ticker" },
    { "source": "/api/price/:ticker",   "destination": "https://hft-engine-a1b2.onrender.com/api/price/:ticker" },
    { "source": "/api/trades",          "destination": "https://hft-engine-a1b2.onrender.com/api/trades" },
    { "source": "/api/simulate",        "destination": "https://hft-engine-a1b2.onrender.com/api/simulate" },
    { "source": "/api/stats",           "destination": "https://hft-engine-a1b2.onrender.com/api/stats" },
    { "source": "/api/health",          "destination": "https://hft-engine-a1b2.onrender.com/api/health" },
    { "source": "/api/events",          "destination": "https://hft-engine-a1b2.onrender.com/api/events" },
    { "source": "/api/treap/:ticker/:side", "destination": "https://hft-engine-a1b2.onrender.com/api/treap/:ticker/:side" },
    { "source": "/api/stats/:ticker",   "destination": "https://hft-engine-a1b2.onrender.com/api/stats/:ticker" },
    { "source": "/api/candles/:ticker", "destination": "https://hft-engine-a1b2.onrender.com/api/candles/:ticker" },
    { "source": "/api/depth/:ticker",   "destination": "https://hft-engine-a1b2.onrender.com/api/depth/:ticker" },
    { "source": "/api/traders",         "destination": "https://hft-engine-a1b2.onrender.com/api/traders" },
    { "source": "/api/trader/:id",      "destination": "https://hft-engine-a1b2.onrender.com/api/trader/:id" }
  ]
}
```

Replace the hostname with your own. Query strings (`?n=`, `?mode=`) are forwarded
automatically.

**Step 4 — deploy the UI on Vercel**

1. vercel.com → **Add New** → **Project** → import the same repository.
2. Settings:
   - Framework Preset: **Other**
   - **Root Directory: `web`**  ← the important one
   - Build Command: leave empty
   - Output Directory: leave empty
3. **Deploy**.

Or from the CLI:

```bash
npm i -g vercel
cd hft-engine/web
vercel --prod
```

**Step 5 — verify**

Open the Vercel URL. The book, trades and reference price should populate within
a second, and **Run simulation** should return statistics.

**Fallback if the rewrites misbehave.** `index.html` also accepts an `?api=`
parameter, which needs no `vercel.json` at all (the server already sends
permissive CORS headers):

```
https://your-project.vercel.app/?api=https://hft-engine-a1b2.onrender.com
```

### 10.3 Everything on Render (simplest)

Do Step 1 and Step 2 above and stop. Render serves `web/index.html` and the API
from the same origin, so no `vercel.json`, no CORS and no `?api=` parameter.
Open the Render URL directly.

### 10.4 Other container hosts

The same image works anywhere that runs Docker and sets `PORT`:

```bash
# Railway
railway init && railway up

# Fly.io
fly launch --dockerfile Dockerfile    # set internal_port = 8080
fly deploy
```

### 10.5 Deployment checklist

- [ ] `javac -d out src/*.java src/tests/*.java` compiles with no errors
- [ ] All three test suites print PASS
- [ ] `docker build -t hft-engine .` succeeds
- [ ] `docker run --rm -p 8080:8080 hft-engine` serves the page
- [ ] `/api/health` returns `ok` on the deployed URL
- [ ] An order submitted from the form appears in the book or the trade tape
- [ ] **Run simulation** returns stats for 100,000 orders
- [ ] Restart the server: previous trades, candles and trader stats are still shown

---

## 11. Limitations

Worth stating plainly in the report:

- The Treap bound is **expected**, not worst case. AVL or red-black would give a
  worst-case guarantee; the trade-off taken here is simplicity for an
  overwhelmingly likely bound.
- One global lock. Correct, and ample at this scale, but a production exchange
  would shard per symbol.
- CSV files only, no databases. On Render's free tier the filesystem resets on
  redeploy, so the Seeder repopulates history.
- Limit orders only. No market, stop, iceberg or IOC order types.
- No self-trade prevention, no fees, no position or risk limits, no settlement.
- Integer tick prices only, no fractional pricing.
- The trade tape keeps only the last 10,000 executions.
- This is an academic model of an exchange, not production trading software.

---

## 12. Team

| Member | Owns |
|---|---|
| Naren Kaarthi | `PriceNode`, `Treap`, `PlainBst`, `EngineEvent`, `EventLog`, `TreapTest`, `charts.js` |
| Giri Prasath | `Order`, `CircularQueue`, `Trade`, `OrderBook`, `TraderStats`, `QueueTest`, `index.html`, `style.css` |
| Mithun Prasath K | `MatchingController`, `OrderGateway`, `OrderSimulator`, `Main`, `Seeder`, `app.js`, `MatchingTest`, `Benchmark`, `MANUAL.md`, repo/merge |
| Gathin | `PriceFeeder`, `RandomWalkFeeder`, `LiveFeeder`, `TradeStore`, `WebServer`, `landing.html`, `Dockerfile`, README, deploy |

### UML alignment

`Order`, `CircularQueue`, `PriceNode`, `Treap`, `OrderBook`, `Trade`,
`MatchingController`, `OrderGateway` and `OrderSimulator` match the class diagram
in the project abstract.

`PriceFeeder`, `RandomWalkFeeder`, `LiveFeeder`, `PlainBst`, `WebServer`,
`Main`, `EngineEvent`, `EventLog`, `TradeStore`, `Seeder`, `TraderStats` are
support classes outside the diagram, as are the helper methods `size()`,
`height()`, `snapshot()`, `removeBidLevel()`, `removeAskLevel()`,
`getLastTrades()`, `getStats()`. Either add them to the diagram as extra boxes,
or state in the report that the diagram covers the core classes only.
