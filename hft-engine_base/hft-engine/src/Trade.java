/*
 * Trade.java
 * Owner: Giri Prasath
 *
 * An immutable record of one execution: a buyer and a seller agreed on a price
 * and a quantity at a point in time.
 *
 * Why immutable:
 *   A trade is history. Once printed it must never change, because the trade
 *   tape is the audit record the UI and the report read from. Making every field
 *   final removes a whole class of bug (a later fill mutating an earlier trade)
 *   and makes the object safe to hand to the web server's reader threads without
 *   any copying or locking.
 *
 *   Orders are mutable and trades are not, which is the right split: orders are
 *   live state that shrinks as it fills, trades are append-only facts.
 */
public class Trade {

    private final String buyerId;
    private final String sellerId;
    private final String ticker;
    private final int price;
    private final int quantity;
    private final long timestamp;

    /*
     * Builds one execution record.
     * Complexity: O(1).
     */
    public Trade(String buyerId, String sellerId, String ticker, int price, int quantity, long timestamp) {
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.ticker = ticker;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    /* Trader id on the buy side of this execution. Complexity: O(1). */
    public String getBuyerId() {
        return buyerId;
    }

    /* Trader id on the sell side of this execution. Complexity: O(1). */
    public String getSellerId() {
        return sellerId;
    }

    /* Symbol the execution happened on. Complexity: O(1). */
    public String getTicker() {
        return ticker;
    }

    /* Execution price, always the resting (older) order's limit price. Complexity: O(1). */
    public int getPrice() {
        return price;
    }

    /* Number of units exchanged. Complexity: O(1). */
    public int getQuantity() {
        return quantity;
    }

    /* Wall-clock time the execution was recorded. Complexity: O(1). */
    public long getTimestamp() {
        return timestamp;
    }

    /*
     * Console form used by the demo and by test failure messages.
     * Complexity: O(1).
     */
    @Override
    public String toString() {
        return "TRADE " + ticker + " " + quantity + " @" + price + "  " + buyerId + " <- " + sellerId;
    }

    /*
     * Hand-rolled JSON for the web API. Written by hand on purpose: the project
     * allows no external libraries, and the field set is fixed and contains no
     * characters needing escaping beyond the ids, which the gateway already
     * restricts to a safe character set.
     * Complexity: O(1).
     */
    public String toJson() {
        return "{\"buyerId\":\"" + buyerId
                + "\",\"sellerId\":\"" + sellerId
                + "\",\"ticker\":\"" + ticker
                + "\",\"price\":" + price
                + ",\"quantity\":" + quantity
                + ",\"timestamp\":" + timestamp + "}";
    }
}
