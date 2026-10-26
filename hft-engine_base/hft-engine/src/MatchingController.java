import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * MatchingController.java
 * Owner: Mithun Prasath K
 *
 * The exchange itself: it owns every book, accepts orders and runs the matching
 * loop that turns crossing orders into trades.
 *
 * Data structures chosen here and why:
 *
 *   books: Map<String, OrderBook>
 *     The UML shows `books: OrderBook[]`, but an array forces a linear scan to
 *     find the book for a ticker, and a fixed set of symbols decided at compile
 *     time. A hash map gives O(1) average lookup by symbol and lets a book be
 *     created the first time a symbol is seen. Symbol lookup happens once per
 *     order, so O(1) here matters.
 *
 *   trades: ArrayDeque<Trade>, capped at 10,000
 *     The trade tape is append-at-one-end, drop-from-the-other: new trades go to
 *     the back, and once the cap is reached the oldest is dropped from the
 *     front. That is a deque, and ArrayDeque does both ends in amortised O(1)
 *     with no per-element node object. An ArrayList would be O(n) per removal
 *     from the front, which at a million orders would dominate the whole run.
 *     The cap is what keeps a long benchmark from exhausting memory: the engine
 *     is a live exchange, not a database, so old tape is simply discarded.
 *
 *   lastTrades: ArrayList<Trade>
 *     Only the executions caused by the order currently being processed. The web
 *     layer reads it to answer "what did my order do", so it is cleared at the
 *     start of every processOrder and is only ever a handful of entries.
 *
 * Concurrency:
 *   processOrder is synchronized, and it is the only way into the engine that
 *   mutates state. The web server runs a thread pool, so several requests can
 *   arrive at once; a single engine-wide lock keeps every book consistent and is
 *   entirely adequate at this scale, where the matching work itself is a few
 *   microseconds. Per-symbol locking would be the next step for a real system
 *   and is deliberately out of scope.
 */
public class MatchingController {

    private static final int TRADE_HISTORY_CAP = 10000;

    private final Map<String, OrderBook> books = new HashMap<>();
    private final ArrayDeque<Trade> trades = new ArrayDeque<>();
    private final List<Trade> lastTrades = new ArrayList<>();

    private long acceptedCount;
    private long rejectedCount;
    private long totalTrades;

    private EventLog eventLog;
    private TradeStore tradeStore;

    public MatchingController() {
        this(null, null);
    }

    public MatchingController(EventLog eventLog, TradeStore tradeStore) {
        this.eventLog = eventLog;
        this.tradeStore = tradeStore;
    }

    public void setEventLog(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    public void setTradeStore(TradeStore tradeStore) {
        this.tradeStore = tradeStore;
    }

    private void emit(String ticker, String side, String type, int price, String traderId, String detail) {
        if (eventLog != null) {
            eventLog.append(new EngineEvent(ticker, side, type, price, traderId, detail));
        }
    }

    public synchronized void processOrder(Order order) {
        OrderBook book = books.get(order.getTicker());
        if (book == null) {
            book = new OrderBook(order.getTicker(), eventLog);
            books.put(order.getTicker(), book);
        }
        lastTrades.clear();

        if (!book.addOrder(order)) {
            rejectedCount++;
            emit(order.getTicker(), order.isBuy() ? "BID" : "ASK", "REJECT", order.getPrice(), order.getTraderId(), "level full");
            return;
        }
        acceptedCount++;
        emit(order.getTicker(), order.isBuy() ? "BID" : "ASK", "ENQUEUE", order.getPrice(), order.getTraderId(), "qty=" + order.getQuantity());
        match(book, order.getTicker());
    }

    private void match(OrderBook book, String ticker) {
        PriceNode bestBid = book.bestBid();
        PriceNode bestAsk = book.bestAsk();

        while (bestBid != null && bestAsk != null && bestBid.getPrice() >= bestAsk.getPrice()) {
            Order buy = bestBid.getQueue().peek();
            Order sell = bestAsk.getQueue().peek();

            if (buy == null) {
                // The Treap listener already emits NODE_DELETE for this removal;
                // emitting here too duplicated every deletion in the timeline.
                book.removeBidLevel(bestBid.getPrice());
                bestBid = book.bestBid();
                continue;
            }
            if (sell == null) {
                book.removeAskLevel(bestAsk.getPrice());
                bestAsk = book.bestAsk();
                continue;
            }

            int qty = Math.min(buy.getQuantity(), sell.getQuantity());
            recordTrade(ticker, buy, sell, qty);

            if (buy.isFilled()) {
                Order filledBuy = bestBid.getQueue().dequeue();
                emit(ticker, "BID", "DEQUEUE", bestBid.getPrice(), filledBuy.getTraderId(), "filled");
                if (bestBid.getQueue().isEmpty()) {
                    book.removeBidLevel(bestBid.getPrice());
                    bestBid = book.bestBid();
                } else {
                    emit(ticker, "BID", "ORDER_UPDATE", bestBid.getPrice(), buy.getTraderId(), "partial fill remaining=" + buy.getQuantity());
                }
            }
            if (sell.isFilled()) {
                Order filledSell = bestAsk.getQueue().dequeue();
                emit(ticker, "ASK", "DEQUEUE", bestAsk.getPrice(), filledSell.getTraderId(), "filled");
                if (bestAsk.getQueue().isEmpty()) {
                    book.removeAskLevel(bestAsk.getPrice());
                    bestAsk = book.bestAsk();
                } else {
                    emit(ticker, "ASK", "ORDER_UPDATE", bestAsk.getPrice(), sell.getTraderId(), "partial fill remaining=" + sell.getQuantity());
                }
            }
        }
    }

    private void recordTrade(String ticker, Order buy, Order sell, int qty) {
        int price = (buy.getTimestamp() <= sell.getTimestamp()) ? buy.getPrice() : sell.getPrice();
        Trade trade = new Trade(buy.getTraderId(), sell.getTraderId(), ticker, price, qty,
                System.currentTimeMillis());

        trades.addLast(trade);
        if (trades.size() > TRADE_HISTORY_CAP) {
            trades.removeFirst();
        }
        lastTrades.add(trade);
        totalTrades++;

        if (tradeStore != null) {
            tradeStore.recordTrade(trade);
        }

        buy.reduceQuantity(qty);
        sell.reduceQuantity(qty);

        emit(ticker, "", "TRADE", price, buy.getTraderId(), "buyer=" + buy.getTraderId() + " seller=" + sell.getTraderId() + " qty=" + qty);
    }

    public synchronized List<Trade> getLastTrades() {
        return new ArrayList<>(lastTrades);
    }

    public synchronized List<Trade> getTrades(int last) {
        List<Trade> out = new ArrayList<>();
        java.util.Iterator<Trade> it = trades.descendingIterator();
        while (it.hasNext() && out.size() < last) {
            out.add(it.next());
        }
        return out;
    }

    public synchronized OrderBook getBook(String ticker) {
        return books.get(ticker);
    }

    public synchronized OrderBook getOrCreateBook(String ticker) {
        OrderBook book = books.get(ticker);
        if (book == null) {
            book = new OrderBook(ticker, eventLog);
            books.put(ticker, book);
        }
        return book;
    }

    public synchronized List<String> getTickers() {
        return new ArrayList<>(books.keySet());
    }

    public synchronized long getAcceptedCount() {
        return acceptedCount;
    }

    public synchronized long getRejectedCount() {
        return rejectedCount;
    }

    public synchronized long getTotalTrades() {
        return totalTrades;
    }
}
