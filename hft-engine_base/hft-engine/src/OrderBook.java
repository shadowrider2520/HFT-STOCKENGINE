import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/*
 * OrderBook.java
 * Owner: Giri Prasath
 *
 * The book for ONE symbol: two price ladders, one for buyers and one for
 * sellers.
 *
 * Why two separate Treaps instead of one:
 *   Bids and asks are searched from opposite ends. The best bid is the HIGHEST
 *   buy price; the best ask is the LOWEST sell price. With two trees each query
 *   is a straight walk down one side (findMax on bids, findMin on asks), which
 *   is expected O(log N) and touches no other side's data.
 *
 *   A single tree holding both sides would need every node tagged with a side
 *   and "best bid" would become "the largest key that happens to be a bid",
 *   which is no longer a simple rightmost walk - it degenerates into a scan.
 *   Splitting also halves N on each side, and lets the two sides be reasoned
 *   about (and locked, if this ever grew past one lock) independently.
 *
 *   The crossing condition is then simply bestBid.price >= bestAsk.price, which
 *   is two O(log N) lookups and one comparison.
 */
public class OrderBook {

    private final String ticker;
    private final Treap bids;
    private final Treap asks;

    public OrderBook(String ticker, EventLog eventLog) {
        this.ticker = ticker;
        this.bids = new Treap(System.nanoTime(), createListener(eventLog, "BID"));
        this.asks = new Treap(System.nanoTime() + 1, createListener(eventLog, "ASK"));
    }

    public OrderBook(String ticker, long seed, EventLog eventLog) {
        this.ticker = ticker;
        this.bids = new Treap(seed, createListener(eventLog, "BID"));
        this.asks = new Treap(seed + 1, createListener(eventLog, "ASK"));
    }

    private static Treap.Listener createListener(EventLog eventLog, String side) {
        if (eventLog == null) return null;
        return (type, price, detail) -> {
            eventLog.append(new EngineEvent(null, side, type, price, "", detail));
        };
    }

    public boolean addOrder(Order order) {
        return order.isBuy() ? bids.insert(order) : asks.insert(order);
    }

    public PriceNode bestBid() {
        return bids.findMax();
    }

    public PriceNode bestAsk() {
        return asks.findMin();
    }

    public void removeBidLevel(int price) {
        bids.remove(price);
    }

    public void removeAskLevel(int price) {
        asks.remove(price);
    }

    public String getTicker() {
        return ticker;
    }

    public Treap getBids() {
        return bids;
    }

    public Treap getAsks() {
        return asks;
    }

    public Snapshot snapshot(int depth) {
        return new Snapshot(topBids(depth), topAsks(depth));
    }

    public List<Level> topBids(int depth) {
        return walk(bids.getRoot(), depth, true);
    }

    public List<Level> topAsks(int depth) {
        return walk(asks.getRoot(), depth, false);
    }

    private List<Level> walk(PriceNode root, int depth, boolean descending) {
        List<Level> levels = new ArrayList<>();
        if (root == null || depth <= 0) {
            return levels;
        }
        ArrayDeque<PriceNode> stack = new ArrayDeque<>();
        PriceNode current = root;
        while ((current != null || !stack.isEmpty()) && levels.size() < depth) {
            while (current != null) {
                stack.push(current);
                current = descending ? current.right : current.left;
            }
            current = stack.pop();
            levels.add(new Level(current.getPrice(), current.totalQuantity(), current.orderCount()));
            current = descending ? current.left : current.right;
        }
        return levels;
    }

    public static final class Level {
        private final int price;
        private final long quantity;
        private final int orders;

        Level(int price, long quantity, int orders) {
            this.price = price;
            this.quantity = quantity;
            this.orders = orders;
        }

        public int getPrice() { return price; }
        public long getQuantity() { return quantity; }
        public int getOrders() { return orders; }

        public String toJson() {
            return "{\"price\":" + price + ",\"qty\":" + quantity + ",\"orders\":" + orders + "}";
        }
    }

    public static final class Snapshot {
        private final List<Level> bids;
        private final List<Level> asks;

        Snapshot(List<Level> bids, List<Level> asks) {
            this.bids = bids;
            this.asks = asks;
        }

        public List<Level> getBids() { return bids; }
        public List<Level> getAsks() { return asks; }
    }
}