/*
 * Benchmark.java
 * Owner: Mithun Prasath K
 *
 * Produces the numbers for the project report. Three measurements:
 *
 *   (a) 1,000,000 orders at RANDOM prices        - realistic throughput
 *   (b) 1,000,000 orders at SEQUENTIAL prices    - the adversarial case
 *   (c) the same ascending keys into a Treap and into a PlainBst - height only
 *
 * (c) is the one that justifies the whole design. Ascending keys are what a
 * trending market looks like, and they are exactly what an unbalanced BST
 * cannot survive: its height becomes N, so every operation becomes a linear
 * scan. The Treap sees the same keys and stays near 3*log2(N) because its shape
 * comes from random priorities, not from the order the keys arrived in.
 *
 * PlainBst is measured at only 10,000 keys. At one million its height would be
 * one million, and simply building it would take time quadratic in the tree
 * depth; 10,000 is already enough to show height == N exactly.
 *
 * Run: java -cp out Benchmark          (add -Xmx1g on a memory-constrained box)
 *      java -cp out Benchmark 200000   (smaller run for a quick check)
 */
public class Benchmark {

    /* Default load size for parts (a), (b) and the Treap half of (c). */
    private static final int DEFAULT_ORDERS = 1_000_000;
    /* Key count for the unbalanced baseline, kept small on purpose. */
    private static final int PLAIN_BST_KEYS = 10_000;
    /* Fixed seed so every run on every machine is comparable. */
    private static final long SEED = 42L;

    /*
     * Entry point. Accepts an optional order count so the benchmark can be run
     * smaller on a slow or memory-limited machine.
     * Complexity: expected O(n log n).
     */
    public static void main(String[] args) {
        int orders = DEFAULT_ORDERS;
        if (args.length > 0) {
            try {
                orders = Math.max(1000, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                System.out.println("Usage: java -cp out Benchmark [orderCount]");
                System.exit(1);
            }
        }

        System.out.println("HFT Matching Engine - Benchmark");
        System.out.println("JVM max heap: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println("Orders per run: " + String.format("%,d", orders));
        System.out.println();

        OrderSimulator.Stats randomStats = runEngine(orders, OrderSimulator.Mode.RANDOM);
        OrderSimulator.Stats sequentialStats = runEngine(orders, OrderSimulator.Mode.SEQUENTIAL);

        System.out.println("(a) Engine under random prices");
        System.out.println("    " + randomStats);
        System.out.println();
        System.out.println("(b) Engine under strictly ascending prices");
        System.out.println("    " + sequentialStats);
        System.out.println();

        runTreeHeightComparison(orders);
        printSummaryTable(randomStats, sequentialStats, orders);
    }

    /*
     * Builds a fresh engine and pushes `orders` simulated orders through it in
     * the given mode, returning the measured statistics.
     * The engine is local to this method so the previous run can be collected
     * before the next one starts.
     * Complexity: expected O(n log n).
     */
    private static OrderSimulator.Stats runEngine(int orders, OrderSimulator.Mode mode) {
        MatchingController controller = new MatchingController();
        OrderGateway gateway = new OrderGateway(controller);
        PriceFeeder feeder = new RandomWalkFeeder(RandomWalkFeeder.DEFAULT_START_PRICE, SEED);

        OrderSimulator simulator = new OrderSimulator(SEED, orders, feeder, mode);
        simulator.run(gateway);

        OrderSimulator.Stats stats = simulator.getStats();
        System.gc();
        return stats;
    }

    /*
     * Part (c): the same ascending key sequence into both trees, reporting
     * height. This is the direct evidence that randomised balancing is doing the
     * work.
     * Complexity: O(n log n) for the Treap, O(k^2) worst case for the baseline
     * (hence the much smaller k).
     */
    private static void runTreeHeightComparison(int treapKeys) {
        System.out.println("(c) Tree height on strictly ascending keys");

        Treap treap = new Treap(SEED);
        long start = System.nanoTime();
        for (int key = 1; key <= treapKeys; key++) {
            treap.insert(new Order("T", "BENCH", true, key, 1, key));
        }
        long treapMs = (System.nanoTime() - start) / 1_000_000L;
        int treapHeight = treap.height();
        int idealHeight = (int) Math.ceil(Math.log(treapKeys) / Math.log(2));

        PlainBst bst = new PlainBst();
        start = System.nanoTime();
        for (int key = 1; key <= PLAIN_BST_KEYS; key++) {
            bst.insert(key);
        }
        long bstMs = (System.nanoTime() - start) / 1_000_000L;
        int bstHeight = bst.height();

        System.out.printf("    Treap    keys=%,10d  height=%4d  (log2 N = %d, ratio %.1fx)  time=%,d ms%n",
                treapKeys, treapHeight, idealHeight, treapHeight / (double) idealHeight, treapMs);
        System.out.printf("    PlainBst keys=%,10d  height=%4d  (height == N, a linked list)  time=%,d ms%n",
                PLAIN_BST_KEYS, bstHeight, bstMs);
        System.out.println("    PlainBst is measured at " + String.format("%,d", PLAIN_BST_KEYS)
                + " keys only: at " + String.format("%,d", treapKeys)
                + " its height would be " + String.format("%,d", treapKeys) + ".");
        System.out.println();

        if (bstHeight != PLAIN_BST_KEYS) {
            System.out.println("FAIL  PlainBst height should equal the key count on ascending input");
            System.exit(1);
        }
        if (treapHeight > 4 * idealHeight) {
            System.out.println("FAIL  Treap height " + treapHeight + " is far above the expected ~3*log2(N)");
            System.exit(1);
        }
        System.out.println("PASS  Treap stayed logarithmic where the plain BST degenerated.");
        System.out.println();
    }

    /*
     * Prints the report-ready table that section 11 of the spec asks for.
     * Complexity: O(1).
     */
    private static void printSummaryTable(OrderSimulator.Stats random, OrderSimulator.Stats sequential,
                                          int orders) {
        int idealHeight = (int) Math.ceil(Math.log(orders) / Math.log(2));
        System.out.println("Summary table for the report");
        System.out.println("+---------------------+------------+-------------+---------------+------------+");
        System.out.println("| Scenario            |    Orders  |   Time (ms) |    Orders/sec |  Max height|");
        System.out.println("+---------------------+------------+-------------+---------------+------------+");
        System.out.printf("| Treap, random       | %,10d | %,11d | %,13.0f | %,10d |%n",
                random.getOrders(), random.getTotalTimeMs(), random.getOrdersPerSecond(),
                Math.max(random.getBidHeight(), random.getAskHeight()));
        System.out.printf("| Treap, sequential   | %,10d | %,11d | %,13.0f | %,10d |%n",
                sequential.getOrders(), sequential.getTotalTimeMs(), sequential.getOrdersPerSecond(),
                Math.max(sequential.getBidHeight(), sequential.getAskHeight()));
        System.out.printf("| PlainBst, sequential| %,10d | %11s | %13s | %,10d |%n",
                PLAIN_BST_KEYS, "-", "-", PLAIN_BST_KEYS);
        System.out.println("+---------------------+------------+-------------+---------------+------------+");
        System.out.println("log2(" + String.format("%,d", orders) + ") = " + idealHeight
                + ", so an ideally balanced tree would be " + idealHeight + " levels deep.");
        System.out.println();
        System.out.println("Trades: random=" + String.format("%,d", random.getTrades())
                + "  sequential=" + String.format("%,d", sequential.getTrades()));
        System.out.println("Rejected (level full): random=" + String.format("%,d", random.getRejected())
                + "  sequential=" + String.format("%,d", sequential.getRejected()));
    }
}
