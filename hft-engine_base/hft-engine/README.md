# HFT Matching Engine

Java implementation of an order matching engine, built as a team project for
our coursework. It behaves like a simplified exchange: limit orders come in
over HTTP, they are kept in an order book and matched using price-time
priority. Everything is held in memory, there is no database and no third
party library is used anywhere - only the JDK.

    order -> gateway (validation) -> order book -> matching -> trade

Price levels are stored in a Treap, which is a binary search tree where each
node has a random priority, so the tree stays balanced on average and lookups
cost about O(log n). The orders waiting at one price level sit in a circular
queue, so enqueue and dequeue are O(1) and time priority inside a level is
preserved.

## Folder layout

    src/            the engine
    src/tests/      test programs, plain main methods, no framework
    web/            browser UI: index.html, app.js, charts.js, style.css
    data/           csv trade history, written out when the server stops
    Dockerfile      container build

## Build

    javac -d out src/*.java src/tests/*.java

Needs JDK 17 or newer.

## Run

    java -cp out Main

Then open http://localhost:8080 in a browser. To use another port:

    PORT=9000 java -cp out Main

Two other modes:

    java -cp out Main demo        # runs the matching loop on the console
    java -cp out Main --no-seed   # start with an empty trade history

## Tests

    java -cp out QueueTest
    java -cp out TreapTest
    java -cp out MatchingTest
    java -cp out Benchmark 200000

Each of them prints ALL PASS or FAILED and returns a non zero exit code when
something is wrong. Benchmark takes the number of orders as an argument, the
default is one million.

## HTTP API

    GET  /api/health
    GET  /api/price/BTCUSDT
    GET  /api/book/BTCUSDT
    GET  /api/depth/BTCUSDT
    GET  /api/treap/BTCUSDT/bids?depth=5
    GET  /api/events?ticker=BTCUSDT&since=0&max=200
    GET  /api/trades?n=200
    GET  /api/candles/BTCUSDT?bucket=5
    GET  /api/stats
    GET  /api/traders
    GET  /api/trader/<id>
    POST /api/order
    GET  /api/simulate?ticker=BTCUSDT&n=1000&mode=random

The page at / polls these once a second and draws the book, the treap and the
event list.

## Docker

    docker build -t hft-engine .
    docker run -p 8080:8080 hft-engine

## Known problems in this version

This is the first version and there are still bugs in it:

- The treap view in the browser often does not redraw. When the tree is deeper
  than the depth that was asked for, /api/treap writes the "left"/"right" key
  and then nothing after it, so the JSON is invalid. The fetch fails and the
  error is swallowed by the frontend, which just keeps the old picture.
- The Clear button of the event list does nothing. A local variable called
  clearEvents shadows the function of the same name, so the click handler ends
  up being the button element.
- The Pause and Simulation buttons never change their label. The code passes
  "#pauseEvents" to a helper that only wants the plain id, so it returns null.
- /api/events with a non numeric "since" value throws inside the handler and
  the request never gets an answer.
- The price feed tries a real exchange websocket first. When the network
  blocks it, the engine silently falls back to a random walk and the UI shows
  SIMULATED instead of a live price.

Things we still have to do: fix the four bugs above, make sure the event log
stays readable while a simulation is pushing thousands of orders, and add a
test for the treap JSON so this kind of breakage is caught by the suite
instead of by looking at the screen.
