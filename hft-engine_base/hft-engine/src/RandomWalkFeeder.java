import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/*
 * RandomWalkFeeder.java
 * Owner: Gathin
 *
 * A synthetic price source: each call nudges the last price by a small random
 * step.
 *
 * Why a random walk rather than a fixed price:
 *   A constant price would make every simulated order land on one price level,
 *   so the Treap would hold a single node and the benchmark would measure the
 *   circular queue alone. A drifting price spreads orders across many levels,
 *   which is what actually exercises the tree. And because a walk trends - it
 *   wanders up or down rather than jumping about - it reproduces the very input
 *   pattern that destroys an unbalanced BST, which is the behaviour this project
 *   set out to measure.
 *
 * Why a ConcurrentHashMap of per-symbol prices:
 *   The web server polls /price from a pool of threads while the simulator may
 *   be running on another. The map gives safe concurrent access without a lock
 *   around the whole feeder, and each symbol walks independently so two books
 *   do not move in lockstep.
 */
public class RandomWalkFeeder implements PriceFeeder {

    /* Default starting level for a symbol the feeder has not seen before. */
    public static final int DEFAULT_START_PRICE = 1200;
    /* Largest move per call, in ticks, in either direction. */
    private static final int MAX_STEP = 2;

    private final int startPrice;
    private final Random random;
    private final Map<String, Integer> prices = new ConcurrentHashMap<>();

    /*
     * Builds a feeder starting at the default price with time-seeded randomness.
     * Complexity: O(1).
     */
    public RandomWalkFeeder() {
        this(DEFAULT_START_PRICE, System.nanoTime());
    }

    /*
     * Builds a feeder with a chosen start price and a fixed seed, so a
     * benchmark or test produces the same price path on every run.
     * Complexity: O(1).
     */
    public RandomWalkFeeder(int startPrice, long seed) {
        this.startPrice = Math.max(1, startPrice);
        this.random = new Random(seed);
        }

    /*
     * Advances this symbol's walk by a step in [-2, +2] and returns the new
     * price. Floored at 1 because a price of zero or less is not a tradable
     * price and would be refused by the gateway.
     * Synchronized on the Random because java.util.Random is shared across the
     * server's threads and its sequence should not be interleaved mid-draw.
     * Complexity: O(1).
     */
    @Override
    public int nextPrice(String ticker) {
        String key = ticker == null ? "" : ticker.toUpperCase();
        int step;
        synchronized (random) {
            step = random.nextInt(MAX_STEP * 2 + 1) - MAX_STEP;
        }
        Integer current = prices.get(key);
        int next = (current == null ? startPrice : current) + step;
        if (next < 1) {
            next = 1;
        }
        prices.put(key, next);
        return next;
    }

    /*
     * Reads the latest price without advancing the walk, so the UI polling
     * /price does not itself drive the market.
     * Complexity: O(1).
     */
    public int currentPrice(String ticker) {
        String key = ticker == null ? "" : ticker.toUpperCase();
        Integer current = prices.get(key);
        return current == null ? startPrice : current;
    }
}
