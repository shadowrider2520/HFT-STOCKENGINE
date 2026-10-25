import java.util.Random;

/*
 * OrderSimulator.java
 * Owner: Mithun Prasath K
 *
 * Generates synthetic order flow and pushes it through the gateway, so the
 * engine can be measured under load.
 *
 * Why two modes:
 *   RANDOM reproduces a realistic market: prices cluster around a reference
 *   price that itself drifts, so orders spread over a band of levels and plenty
 *   of them cross and trade. This is the throughput number.
 *
 *   SEQUENTIAL is the adversarial case. Prices go 1, 2, 3, ... strictly
 *   ascending, which is precisely the input that turns an unbalanced BST into a
 *   linked list. Running it proves the Treap's randomised priorities break the
 *   correlation between arrival order and tree shape. Without this mode the
 *   benchmark would only ever show the easy case.
 *
 * Why a seeded java.util.Random:
 *   A performance claim that cannot be reproduced is not a claim. Fixing the
 *   seed (42 by default) means the same run produces the same orders, the same
 *   trades and the same tree heights on any machine, so the numbers in the
 *   report can be re-checked.
 */
public class OrderSimulator {

    /* Default seed, so an unseeded run is still reproducible. */
    public static final long DEFAULT_SEED = 42L;
    /* Symbol used when the caller does not name one. */
    public static final String DEFAULT_TICKER = "BTCUSDT";
    /* Number of distinct simulated traders, T0..T99. */
    private static final int TRADER_COUNT = 100;
    /* Largest offset from the reference price in RANDOM mode. */
    private static final int MAX_SPREAD = 5;
    /* Largest simulated order size. */
    private static final int MAX_QUANTITY = 100;

    /* Price generation strategy. */
    public enum Mode { RANDOM, SEQUENTIAL }

    private final long seed;
    private final int orderCount;
    private final PriceFeeder feeder;
    private final Mode mode;
    private final String ticker;

    // Filled in by run(), read back by getStats().
    private long totalTimeMs;
    private double ordersPerSecond;
    private long tradesMade;
    private long rejected;
    private int bidHeight;
    private int askHeight;
    private int bidLevels;
    private int askLevels;

    /*
     * Builds a simulator on the default symbol.
     * Complexity: O(1).
     */
    public OrderSimulator(long seed, int orderCount, PriceFeeder feeder, Mode mode) {
        this(seed, orderCount, feeder, mode, DEFAULT_TICKER);
    }

    /*
     * Builds a simulator for a named symbol, so several books can be loaded
     * independently.
     * Complexity: O(1).
     */
    public OrderSimulator(long seed, int orderCount, PriceFeeder feeder, Mode mode, String ticker) {
        this.seed = seed;
        this.orderCount = orderCount;
        this.feeder = feeder;
        this.mode = mode;
        this.ticker = ticker == null ? DEFAULT_TICKER : ticker.toUpperCase();
    }

    /*
     * Produces n raw order lines in the gateway's wire format. Used by the
     * console demo and by anything that wants to inspect the flow before
     * submitting it.
     *
     * Note this materialises the whole batch in an array, so it is meant for
     * modest n; run() deliberately avoids it for million-order benchmarks and
     * generates the same sequence one line at a time instead.
     * Complexity: O(n) time and O(n) memory.
     */
    public String[] generateRawOrders(int n) {
        Random rng = new Random(seed);
        int[] sequential = {0};
        String[] raw = new String[n];
        for (int i = 0; i < n; i++) {
            raw[i] = buildRawOrder(rng, sequential);
        }
        return raw;
    }

    /*
     * Runs the load test: generates orderCount orders, parses and submits each
     * one, and times the whole loop. Statistics (throughput, trades, rejections,
     * final Treap heights) are stored in fields for getStats().
     *
     * Orders are generated inside the loop rather than up front so a one-million
     * order run does not first allocate a one-million entry String array. The
     * RNG is a single instance walked once, so the sequence is identical to
     * generateRawOrders(orderCount).
     *
     * The timer covers generation, parsing and matching together, which is the
     * honest end-to-end number: that is the work the exchange actually does per
     * order.
     * Complexity: expected O(n log N).
     */
    public void run(OrderGateway gateway) {
        Random rng = new Random(seed);
        int[] sequential = {0};
        MatchingController controller = gateway.getController();

        long tradesBefore = controller.getTotalTrades();
        long rejectedBefore = controller.getRejectedCount();

        long startNanos = System.nanoTime();
        for (int i = 0; i < orderCount; i++) {
            gateway.submit(gateway.parse(buildRawOrder(rng, sequential)));
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        totalTimeMs = elapsedNanos / 1_000_000L;
        ordersPerSecond = elapsedNanos == 0 ? 0 : orderCount * 1_000_000_000.0 / elapsedNanos;
        tradesMade = controller.getTotalTrades() - tradesBefore;
        rejected = controller.getRejectedCount() - rejectedBefore;

        OrderBook book = controller.getBook(ticker);
        if (book != null) {
            bidHeight = book.getBids().height();
            askHeight = book.getAsks().height();
            bidLevels = book.getBids().size();
            askLevels = book.getAsks().size();
        }
    }

    /*
     * Builds one raw order line. The sequential counter is passed in a
     * single-element array so the same helper can serve both the array-building
     * and the streaming paths while sharing one RNG stream.
     *
     * RANDOM mode: the reference price from the feeder, offset by up to
     * MAX_SPREAD ticks either way, floored at 1.
     * SEQUENTIAL mode: 1, 2, 3, ... ignoring the feeder entirely, which is the
     * worst case for an unbalanced tree.
     * Complexity: O(1).
     */
    private String buildRawOrder(Random rng, int[] sequential) {
        int price;
        if (mode == Mode.SEQUENTIAL) {
            sequential[0]++;
            price = sequential[0];
        } else {
            int reference = feeder.nextPrice(ticker);
            price = reference + rng.nextInt(MAX_SPREAD * 2 + 1) - MAX_SPREAD;
            if (price < 1) {
                price = 1;
            }
        }
        String trader = "T" + rng.nextInt(TRADER_COUNT);
        String side = rng.nextBoolean() ? "BUY" : "SELL";
        int quantity = rng.nextInt(MAX_QUANTITY) + 1;
        return trader + "," + ticker + "," + side + "," + price + "," + quantity;
    }

    /*
     * Results of the last run(), ready for printing or for the /simulate JSON
     * response.
     * Complexity: O(1).
     */
    public Stats getStats() {
        return new Stats(orderCount, mode, totalTimeMs, ordersPerSecond, tradesMade, rejected,
                bidHeight, askHeight, bidLevels, askLevels);
    }

    /* The symbol this simulator loads. Complexity: O(1). */
    public String getTicker() {
        return ticker;
    }

    /* The seed driving this simulator's RNG. Complexity: O(1). */
    public long getSeed() {
        return seed;
    }

    /* How many orders a run() will submit. Complexity: O(1). */
    public int getOrderCount() {
        return orderCount;
    }

    /*
     * Immutable result bundle from one simulator run.
     */
    public static final class Stats {
        private final int orders;
        private final Mode mode;
        private final long totalTimeMs;
        private final double ordersPerSecond;
        private final long trades;
        private final long rejected;
        private final int bidHeight;
        private final int askHeight;
        private final int bidLevels;
        private final int askLevels;

        /* Captures one run's measurements. Complexity: O(1). */
        Stats(int orders, Mode mode, long totalTimeMs, double ordersPerSecond, long trades,
              long rejected, int bidHeight, int askHeight, int bidLevels, int askLevels) {
            this.orders = orders;
            this.mode = mode;
            this.totalTimeMs = totalTimeMs;
            this.ordersPerSecond = ordersPerSecond;
            this.trades = trades;
            this.rejected = rejected;
            this.bidHeight = bidHeight;
            this.askHeight = askHeight;
            this.bidLevels = bidLevels;
            this.askLevels = askLevels;
        }

        /* Orders submitted in the run. Complexity: O(1). */
        public int getOrders() {
            return orders;
        }

        /* Price generation mode used. Complexity: O(1). */
        public Mode getMode() {
            return mode;
        }

        /* Wall-clock duration of the run in milliseconds. Complexity: O(1). */
        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        /* End-to-end throughput in orders per second. Complexity: O(1). */
        public double getOrdersPerSecond() {
            return ordersPerSecond;
        }

        /* Executions produced during the run. Complexity: O(1). */
        public long getTrades() {
            return trades;
        }

        /* Orders refused because a price level was full. Complexity: O(1). */
        public long getRejected() {
            return rejected;
        }

        /* Final height of the bid ladder, the balance evidence. Complexity: O(1). */
        public int getBidHeight() {
            return bidHeight;
        }

        /* Final height of the ask ladder. Complexity: O(1). */
        public int getAskHeight() {
            return askHeight;
        }

        /* Distinct bid price levels left in the book. Complexity: O(1). */
        public int getBidLevels() {
            return bidLevels;
        }

        /* Distinct ask price levels left in the book. Complexity: O(1). */
        public int getAskLevels() {
            return askLevels;
        }

        /* Console summary line used by the demo and the benchmark. Complexity: O(1). */
        @Override
        public String toString() {
            return String.format(
                    "%-10s orders=%,d  time=%,d ms  rate=%,.0f orders/s  trades=%,d  rejected=%,d  "
                            + "bidHeight=%d  askHeight=%d  bidLevels=%,d  askLevels=%,d",
                    mode, orders, totalTimeMs, ordersPerSecond, trades, rejected,
                    bidHeight, askHeight, bidLevels, askLevels);
        }

        /* Hand-built JSON for the /simulate endpoint. Complexity: O(1). */
        public String toJson() {
            return "{\"orders\":" + orders
                    + ",\"mode\":\"" + mode + "\""
                    + ",\"totalTimeMs\":" + totalTimeMs
                    + ",\"ordersPerSecond\":" + Math.round(ordersPerSecond)
                    + ",\"trades\":" + trades
                    + ",\"rejected\":" + rejected
                    + ",\"bidHeight\":" + bidHeight
                    + ",\"askHeight\":" + askHeight
                    + ",\"bidLevels\":" + bidLevels
                    + ",\"askLevels\":" + askLevels + "}";
        }
    }
}
