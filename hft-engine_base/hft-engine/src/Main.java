import java.io.IOException;
import java.util.List;

/*
 * Main.java
 * Owner: Mithun Prasath K
 *
 * Two entry points into the same engine:
 *
 *   java -cp out Main          starts the HTTP server and the web UI
 *   java -cp out Main demo     runs the console demonstration and benchmark
 *
 * The demo path is deliberately self-contained and does not reference anything
 * under src/tests, because the container image compiles only src/*.java. The
 * fuller measurement, including the PlainBst comparison, lives in Benchmark.
 */
public class Main {

    private static final String DEMO_TICKER = "DEMO";
    private static final int BENCHMARK_ORDERS = 1_000_000;
    private static final long SEED = 42L;

    public static void main(String[] args) throws IOException {
        boolean noSeed = false;
        String mode = null;

        for (String arg : args) {
            if ("--no-seed".equals(arg)) {
                noSeed = true;
            } else if ("demo".equalsIgnoreCase(arg)) {
                mode = "demo";
            }
        }

        if ("demo".equals(mode)) {
            runDemo();
            return;
        }

        new WebServer(resolvePort(), !noSeed).start();
    }

    private static int resolvePort() {
        String configured = System.getenv("PORT");
        if (configured == null || configured.isBlank()) {
            return WebServer.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configured.trim());
            return (port > 0 && port < 65536) ? port : WebServer.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            System.out.println("PORT '" + configured + "' is not a number, using " + WebServer.DEFAULT_PORT);
            return WebServer.DEFAULT_PORT;
        }
    }

    private static void runDemo() {
        System.out.println("HFT Matching Engine - console demo");
        System.out.println("==================================");
        System.out.println();
        runSampleTrades();
        runLoadBenchmark();
    }

    private static void runSampleTrades() {
        MatchingController controller = new MatchingController();
        OrderGateway gateway = new OrderGateway(controller);

        System.out.println("1) Resting liquidity (nothing crosses yet)");
        send(gateway, "ALICE," + DEMO_TICKER + ",SELL,102,50");
        send(gateway, "BOB," + DEMO_TICKER + ",SELL,101,40");
        send(gateway, "CAROL," + DEMO_TICKER + ",BUY,99,30");
        printBook(controller);

        System.out.println("2) A buy at 101 crosses the best ask (BOB at 101)");
        send(gateway, "DAVE," + DEMO_TICKER + ",BUY,101,25");
        printBook(controller);

        System.out.println("3) A large buy at 102 sweeps both remaining ask levels");
        send(gateway, "ERIN," + DEMO_TICKER + ",BUY,102,100");
        printBook(controller);

        System.out.println("4) Price-improvement check: a buy at 120 into a resting ask at 103");
        send(gateway, "FRANK," + DEMO_TICKER + ",SELL,103,10");
        send(gateway, "GRACE," + DEMO_TICKER + ",BUY,120,10");
        System.out.println("   (the trade prints at 103, the resting order's price, not 120)");
        System.out.println();

        System.out.println("5) Rejecting bad input");
        tryBadOrder(gateway, "HANK," + DEMO_TICKER + ",HOLD,100,10");
        tryBadOrder(gateway, "HANK," + DEMO_TICKER + ",BUY,100,0");
        tryBadOrder(gateway, "HANK," + DEMO_TICKER + ",BUY,abc,10");
        tryBadOrder(gateway, "HANK," + DEMO_TICKER + ",BUY,100");
        System.out.println();

        System.out.println("Totals: accepted=" + controller.getAcceptedCount()
                + "  rejected=" + controller.getRejectedCount()
                + "  trades=" + controller.getTotalTrades());
        System.out.println();
    }

    private static void send(OrderGateway gateway, String raw) {
        gateway.submitRaw(raw);
        System.out.println("   > " + raw);
        for (Trade trade : gateway.lastTrades()) {
            System.out.println("     " + trade);
        }
    }

    private static void tryBadOrder(OrderGateway gateway, String raw) {
        try {
            gateway.submitRaw(raw);
            System.out.println("   > " + raw + "   ACCEPTED (unexpected)");
        } catch (IllegalArgumentException e) {
            System.out.println("   > " + raw + "   rejected: " + e.getMessage());
        }
    }

    private static void printBook(MatchingController controller) {
        OrderBook book = controller.getBook(DEMO_TICKER);
        if (book == null) {
            System.out.println("   (no book yet)");
            System.out.println();
            return;
        }
        List<OrderBook.Level> bids = book.topBids(5);
        List<OrderBook.Level> asks = book.topAsks(5);

        System.out.println("   ---- book ----------------------------");
        System.out.println("        BIDS                 ASKS");
        int rows = Math.max(bids.size(), asks.size());
        if (rows == 0) {
            System.out.println("        (empty)              (empty)");
        }
        for (int i = 0; i < rows; i++) {
            String bid = i < bids.size()
                    ? String.format("%6d x %-6d", bids.get(i).getPrice(), bids.get(i).getQuantity())
                    : "              ";
            String ask = i < asks.size()
                    ? String.format("%6d x %-6d", asks.get(i).getPrice(), asks.get(i).getQuantity())
                    : "              ";
            System.out.println("     " + bid + "       " + ask);
        }
        System.out.println("   --------------------------------------");
        System.out.println();
    }

    private static void runLoadBenchmark() {
        System.out.println("Benchmark: " + String.format("%,d", BENCHMARK_ORDERS) + " orders per run");
        System.out.println("JVM max heap: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println();

        System.out.println(runOne(OrderSimulator.Mode.RANDOM));
        System.out.println(runOne(OrderSimulator.Mode.SEQUENTIAL));
        System.out.println();
        System.out.println("Run 'java -cp out Benchmark' for the full comparison against PlainBst.");
    }

    private static String runOne(OrderSimulator.Mode mode) {
        MatchingController controller = new MatchingController();
        OrderGateway gateway = new OrderGateway(controller);
        PriceFeeder feeder = new RandomWalkFeeder(RandomWalkFeeder.DEFAULT_START_PRICE, SEED);

        OrderSimulator simulator = new OrderSimulator(SEED, BENCHMARK_ORDERS, feeder, mode);
        simulator.run(gateway);
        String line = simulator.getStats().toString();
        System.gc();
        return line;
    }
}