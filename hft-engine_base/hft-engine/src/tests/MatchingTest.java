import java.util.List;

/*
 * MatchingTest.java
 * Owner: Mithun Prasath K
 *
 * Verifies price-time priority end to end: that crossing orders trade, that the
 * execution price is the resting order's price, that partial fills keep their
 * place in the queue, that emptied levels disappear from the Treap, and that a
 * saturated level is rejected rather than silently dropped.
 *
 * Timestamps are supplied explicitly and increase by one per order, so arrival
 * order is deterministic and never depends on the wall clock.
 *
 * Run: java -cp out MatchingTest
 */
public class MatchingTest {

    private static final String TICKER = "TEST";
    private static int failures = 0;
    private static int checks = 0;
    private static long clock = 0;

    /*
     * Entry point: runs every matching case and exits non-zero on any failure.
     * Complexity: O(LEVEL_CAPACITY) overall, dominated by the rejection case.
     */
    public static void main(String[] args) {
        testExactCross();
        testPartialFillBuyLarger();
        testPartialFillSellLarger();
        testNoCross();
        testFifoAtSamePrice();
        testSweepMultipleLevels();
        testTradePriceIsRestingOrderPrice();
        testQueueFullRejectionCounted();
        testSelfCrossStillTrades();
        testSeparateTickersDoNotInteract();

        System.out.println();
        System.out.println(failures == 0
                ? "MatchingTest: ALL PASS (" + checks + " checks)"
                : "MatchingTest: " + failures + " FAILED of " + checks + " checks");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /*
     * Equal quantities at a crossing price: exactly one trade, and both price
     * levels must be deleted from their Treaps because both queues emptied.
     * Complexity: O(log N).
     */
    private static void testExactCross() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("S1", 100, 50));
        engine.processOrder(buy("B1", 100, 50));

        List<Trade> trades = engine.getLastTrades();
        check("exact cross: one trade", trades.size() == 1);
        check("exact cross: quantity 50", trades.size() == 1 && trades.get(0).getQuantity() == 50);
        check("exact cross: price 100", trades.size() == 1 && trades.get(0).getPrice() == 100);

        OrderBook book = engine.getBook(TICKER);
        check("exact cross: bid level removed", book.bestBid() == null);
        check("exact cross: ask level removed", book.bestAsk() == null);
        check("exact cross: both ladders empty",
                book.getBids().size() == 0 && book.getAsks().size() == 0);
    }

    /*
     * Buyer wants more than the resting seller has: one trade for the seller's
     * size, the ask level disappears, and the buyer's remainder stays resting at
     * the FRONT of its own level with its original time priority.
     * Complexity: O(log N).
     */
    private static void testPartialFillBuyLarger() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("S1", 100, 30));
        engine.processOrder(buy("B1", 100, 80));

        check("partial(buy): one trade", engine.getLastTrades().size() == 1);
        check("partial(buy): traded the smaller size",
                engine.getLastTrades().get(0).getQuantity() == 30);

        OrderBook book = engine.getBook(TICKER);
        check("partial(buy): ask level removed", book.bestAsk() == null);
        check("partial(buy): bid level kept", book.bestBid() != null);
        check("partial(buy): remainder is 50",
                book.bestBid() != null && book.bestBid().getQueue().peek().getQuantity() == 50);
        check("partial(buy): remainder is at the queue front",
                book.bestBid() != null && "B1".equals(book.bestBid().getQueue().peek().getTraderId()));
        check("partial(buy): only one order resting",
                book.bestBid() != null && book.bestBid().getQueue().size() == 1);
    }

    /*
     * Mirror case: the resting seller is larger than the incoming buyer, so the
     * bid level empties and the ask remainder rests.
     * Complexity: O(log N).
     */
    private static void testPartialFillSellLarger() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("S1", 100, 90));
        engine.processOrder(buy("B1", 100, 40));

        OrderBook book = engine.getBook(TICKER);
        check("partial(sell): one trade", engine.getLastTrades().size() == 1);
        check("partial(sell): bid level removed", book.bestBid() == null);
        check("partial(sell): ask remainder is 50",
                book.bestAsk() != null && book.bestAsk().getQueue().peek().getQuantity() == 50);
    }

    /*
     * Bid below ask: no overlap, so nothing trades and both orders rest.
     * Complexity: O(log N).
     */
    private static void testNoCross() {
        MatchingController engine = new MatchingController();
        engine.processOrder(buy("B1", 99, 10));
        engine.processOrder(sell("S1", 101, 10));

        check("no cross: zero trades", engine.getLastTrades().isEmpty());
        check("no cross: lifetime trade count zero", engine.getTotalTrades() == 0);

        OrderBook book = engine.getBook(TICKER);
        check("no cross: bid resting at 99", book.bestBid() != null && book.bestBid().getPrice() == 99);
        check("no cross: ask resting at 101", book.bestAsk() != null && book.bestAsk().getPrice() == 101);
        check("no cross: spread intact",
                book.bestAsk().getPrice() - book.bestBid().getPrice() == 2);
    }

    /*
     * Two sellers at the SAME price: the one that arrived first must trade
     * first. This is the time half of price-time priority and is supplied by the
     * circular queue inside the price level.
     * Complexity: O(log N).
     */
    private static void testFifoAtSamePrice() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("EARLY", 100, 20));
        engine.processOrder(sell("LATE", 100, 20));
        engine.processOrder(buy("B1", 100, 20));

        List<Trade> trades = engine.getLastTrades();
        check("FIFO: one trade", trades.size() == 1);
        check("FIFO: earlier seller traded", trades.size() == 1 && "EARLY".equals(trades.get(0).getSellerId()));

        OrderBook book = engine.getBook(TICKER);
        check("FIFO: later seller still resting",
                book.bestAsk() != null && "LATE".equals(book.bestAsk().getQueue().peek().getTraderId()));
        check("FIFO: ask level kept with one order",
                book.bestAsk() != null && book.bestAsk().getQueue().size() == 1);
    }

    /*
     * One large buy crossing three ask levels at once. The matcher must sweep
     * upwards level by level, deleting each emptied level and re-reading the new
     * best ask, filling at each level's own price.
     * Complexity: O(T log N) for T = 3 executions.
     */
    private static void testSweepMultipleLevels() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("S1", 100, 30));
        engine.processOrder(sell("S2", 105, 30));
        engine.processOrder(sell("S3", 110, 30));
        engine.processOrder(buy("BIG", 110, 100));

        List<Trade> trades = engine.getLastTrades();
        check("sweep: three trades", trades.size() == 3);
        check("sweep: filled cheapest level first",
                trades.size() == 3 && trades.get(0).getPrice() == 100);
        check("sweep: then the middle level",
                trades.size() == 3 && trades.get(1).getPrice() == 105);
        check("sweep: then the top level",
                trades.size() == 3 && trades.get(2).getPrice() == 110);
        check("sweep: 90 units filled in total",
                trades.stream().mapToInt(Trade::getQuantity).sum() == 90);

        OrderBook book = engine.getBook(TICKER);
        check("sweep: all ask levels consumed", book.bestAsk() == null && book.getAsks().size() == 0);
        check("sweep: 10 unfilled units rest as a bid",
                book.bestBid() != null && book.bestBid().getQueue().peek().getQuantity() == 10);
        check("sweep: remainder rests at its own limit price",
                book.bestBid() != null && book.bestBid().getPrice() == 110);
    }

    /*
     * An aggressive buy crossing far above a resting ask must execute at the
     * RESTING order's price, giving the aggressor the price improvement. Tested
     * in both directions so the rule is not accidentally side-specific.
     * Complexity: O(log N).
     */
    private static void testTradePriceIsRestingOrderPrice() {
        MatchingController buySideAggressor = new MatchingController();
        buySideAggressor.processOrder(sell("S1", 100, 10));
        buySideAggressor.processOrder(buy("B1", 120, 10));
        check("trade price: aggressive buy pays the resting ask price 100",
                buySideAggressor.getLastTrades().get(0).getPrice() == 100);

        MatchingController sellSideAggressor = new MatchingController();
        sellSideAggressor.processOrder(buy("B2", 120, 10));
        sellSideAggressor.processOrder(sell("S2", 100, 10));
        check("trade price: aggressive sell receives the resting bid price 120",
                sellSideAggressor.getLastTrades().get(0).getPrice() == 120);
    }

    /*
     * Filling one price level to LEVEL_CAPACITY and pushing one more order in:
     * the extra order must be refused and counted as a rejection, not silently
     * dropped or allowed to grow the level.
     * Complexity: O(LEVEL_CAPACITY).
     */
    private static void testQueueFullRejectionCounted() {
        MatchingController engine = new MatchingController();
        for (int i = 0; i < PriceNode.LEVEL_CAPACITY; i++) {
            engine.processOrder(buy("T" + i, 100, 1));
        }
        check("full level: all accepted so far", engine.getAcceptedCount() == PriceNode.LEVEL_CAPACITY);
        check("full level: no rejections yet", engine.getRejectedCount() == 0);

        engine.processOrder(buy("OVERFLOW", 100, 1));
        check("full level: overflow order rejected", engine.getRejectedCount() == 1);
        check("full level: accepted count unchanged",
                engine.getAcceptedCount() == PriceNode.LEVEL_CAPACITY);

        OrderBook book = engine.getBook(TICKER);
        check("full level: level still holds exactly capacity",
                book.bestBid() != null && book.bestBid().getQueue().size() == PriceNode.LEVEL_CAPACITY);
        check("full level: a different price is still accepted", acceptsAt(engine, 101));
    }

    /*
     * The engine applies no self-trade prevention, so the same trader on both
     * sides still crosses. Asserted explicitly so the behaviour is a documented
     * decision rather than an accident.
     * Complexity: O(log N).
     */
    private static void testSelfCrossStillTrades() {
        MatchingController engine = new MatchingController();
        engine.processOrder(sell("SAME", 100, 10));
        engine.processOrder(buy("SAME", 100, 10));
        List<Trade> trades = engine.getLastTrades();
        check("self cross: trades (no self-trade prevention)", trades.size() == 1);
        check("self cross: both sides are the same trader",
                trades.size() == 1 && trades.get(0).getBuyerId().equals(trades.get(0).getSellerId()));
    }

    /*
     * Crossing prices on two different symbols must not match each other; each
     * symbol gets its own independent book.
     * Complexity: O(log N).
     */
    private static void testSeparateTickersDoNotInteract() {
        MatchingController engine = new MatchingController();
        engine.processOrder(new Order("S1", "AAA", false, 100, 10, clock++));
        engine.processOrder(new Order("B1", "BBB", true, 100, 10, clock++));

        check("two tickers: no cross-symbol trade", engine.getTotalTrades() == 0);
        check("two tickers: AAA holds the ask", engine.getBook("AAA").bestAsk() != null);
        check("two tickers: BBB holds the bid", engine.getBook("BBB").bestBid() != null);
        check("two tickers: AAA has no bid", engine.getBook("AAA").bestBid() == null);
    }

    /* Helper: submits one buy at a price and reports whether it was accepted. Complexity: O(log N). */
    private static boolean acceptsAt(MatchingController engine, int price) {
        long before = engine.getRejectedCount();
        engine.processOrder(buy("OTHER", price, 1));
        return engine.getRejectedCount() == before;
    }

    /* Builds a buy order with the next deterministic timestamp. Complexity: O(1). */
    private static Order buy(String trader, int price, int qty) {
        return new Order(trader, TICKER, true, price, qty, clock++);
    }

    /* Builds a sell order with the next deterministic timestamp. Complexity: O(1). */
    private static Order sell(String trader, int price, int qty) {
        return new Order(trader, TICKER, false, price, qty, clock++);
    }

    /* Records one assertion and prints its result. Complexity: O(1). */
    private static void check(String name, boolean condition) {
        checks++;
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
