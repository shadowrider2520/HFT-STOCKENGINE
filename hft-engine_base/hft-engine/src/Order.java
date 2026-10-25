/*
 * Order.java
 * Owner: Giri Prasath
 *
 * A single limit order sitting in (or passing through) the exchange.
 *
 * Design note - why a plain mutable object:
 *   An order is the unit that gets copied the most in this engine: one million of
 *   them are created in the benchmark. Anything clever here (immutability with
 *   copy-on-fill, builders, wrapper types) would multiply allocation and GC work
 *   for no algorithmic gain. The only field that ever changes is `quantity`,
 *   because a partial fill shrinks the order in place while it stays parked at the
 *   front of its price level's queue. Everything else is final so an order can
 *   never silently change price or side after it has been slotted into the book.
 *
 *   `price` is an int, not a double. Money in cents (or whatever the smallest
 *   tick is) avoids floating point drift and, more importantly here, gives the
 *   Treap an exact integer key so two orders at "the same price" really do land
 *   on the same node. 1200.0 == 1199.9999999 is the kind of bug that silently
 *   creates duplicate price levels.
 */
public class Order {

    private final String traderId;
    private final String ticker;
    private final boolean isBuy;
    private final int price;
    private int quantity;          // mutable: shrinks as the order is filled
    private final long timestamp;  // arrival time, drives time priority and trade pricing

    /*
     * Builds an order. All six fields are supplied by the caller (the gateway),
     * including the timestamp, so that tests can construct a deterministic
     * arrival sequence instead of depending on the wall clock.
     * Complexity: O(1).
     */
    public Order(String traderId, String ticker, boolean isBuy, int price, int quantity, long timestamp) {
        this.traderId = traderId;
        this.ticker = ticker;
        this.isBuy = isBuy;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    /*
     * Removes `qty` units from this order after a fill. Clamped at zero so a
     * mis-sized fill can never push the quantity negative and turn one order into
     * an infinite source of liquidity.
     * Complexity: O(1).
     */
    public void reduceQuantity(int qty) {
        this.quantity -= qty;
        if (this.quantity < 0) {
            this.quantity = 0;
        }
    }

    /*
     * True once there is nothing left to trade. The matcher uses this to decide
     * whether to pop the order off the front of its queue or leave it resting.
     * Complexity: O(1).
     */
    public boolean isFilled() {
        return quantity <= 0;
    }

    /* Returns the id of the trader who submitted this order. Complexity: O(1). */
    public String getTraderId() {
        return traderId;
    }

    /* Returns the symbol this order belongs to; selects the OrderBook. Complexity: O(1). */
    public String getTicker() {
        return ticker;
    }

    /* True for a buy (bid), false for a sell (ask). Complexity: O(1). */
    public boolean isBuy() {
        return isBuy;
    }

    /* Returns the limit price in ticks; this is the Treap key. Complexity: O(1). */
    public int getPrice() {
        return price;
    }

    /* Returns the quantity still outstanding. Complexity: O(1). */
    public int getQuantity() {
        return quantity;
    }

    /* Returns the arrival timestamp used for time priority. Complexity: O(1). */
    public long getTimestamp() {
        return timestamp;
    }

    /*
     * Human readable form used by the console demo and failure messages in tests.
     * Complexity: O(1).
     */
    @Override
    public String toString() {
        return traderId + " " + (isBuy ? "BUY" : "SELL") + " " + quantity + " " + ticker + " @" + price;
    }
}
