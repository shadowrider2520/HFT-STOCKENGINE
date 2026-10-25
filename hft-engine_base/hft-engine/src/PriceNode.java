/*
 * PriceNode.java
 * Owner: Naren Kaarthi
 *
 * One price level in the book: a Treap node that also owns the FIFO queue of
 * every order resting at that price.
 *
 * Why the node carries the queue:
 *   The book is organised by price-time priority, which is two orderings at
 *   once. Price ordering is handled by the tree (the key), time ordering by the
 *   queue inside the node. Keeping them in one object means that once the tree
 *   has found the best price, the best order at that price is one pointer away:
 *   no second lookup, no secondary map from price to queue to fall out of sync.
 *
 * Why `priority` exists:
 *   This is the randomised half of the Treap. The node is a binary SEARCH tree
 *   node on `price` and simultaneously a max-HEAP node on `priority`. Because
 *   the priority is drawn at random and is independent of the insertion order,
 *   the resulting shape is the same as if the prices had been inserted in random
 *   order, which gives expected O(log N) depth even when the real prices arrive
 *   sorted. That is the whole point: market prices arrive in trends, and a plain
 *   BST fed a trending sequence degenerates into a linked list.
 */
public class PriceNode {

    /*
     * Maximum number of orders that may rest at a single price level.
     * A finite bound is deliberate: it bounds memory per level and makes the
     * "exchange is saturated" case explicit (enqueue returns false, the gateway
     * counts a rejection) instead of letting one price swallow unlimited orders.
     */
    public static final int LEVEL_CAPACITY = 1024;

    final int price;          // BST key
    final int priority;       // max-heap key, random and fixed for the node's life
    PriceNode left;
    PriceNode right;
    final CircularQueue queue;

    /*
     * Creates a price level with a given random heap priority and an empty
     * order queue.
     * Complexity: O(1).
     */
    public PriceNode(int price, int priority) {
        this.price = price;
        this.priority = priority;
        this.left = null;
        this.right = null;
        this.queue = new CircularQueue(LEVEL_CAPACITY);
    }

    /* Returns this level's price (the Treap key). Complexity: O(1). */
    public int getPrice() {
        return price;
    }

    /* Returns the random heap priority; used by tests to verify the heap property. Complexity: O(1). */
    public int getPriority() {
        return priority;
    }

    /* Returns the FIFO queue of orders resting at this price. Complexity: O(1). */
    public CircularQueue getQueue() {
        return queue;
    }

    /* Number of distinct orders resting here (the "orders" column in the UI). Complexity: O(1). */
    public int orderCount() {
        return queue.size();
    }

    /* Total outstanding size at this level (the "qty" column in the UI). Complexity: O(k). */
    public long totalQuantity() {
        return queue.totalQuantity();
    }

    /* Compact description used in demo output and test failure messages. Complexity: O(k). */
    @Override
    public String toString() {
        return "@" + price + " qty=" + totalQuantity() + " orders=" + queue.size();
    }
}
