/*
 * CircularQueue.java
 * Owner: Giri Prasath
 *
 * FIFO queue of orders resting at ONE price level, backed by an array.
 *
 * Why a circular queue and not a LinkedList / ArrayDeque:
 *   Time priority says the order that arrived first at a price must trade first,
 *   so the structure has to be a strict FIFO: append at the tail, match from the
 *   head. That is exactly a queue.
 *
 *   A java.util.LinkedList would also be FIFO, but every order would cost an
 *   extra node object and two pointer chases, and the nodes end up scattered
 *   across the heap. The matcher touches the head of a queue on every single
 *   trade, so cache locality matters: an array keeps consecutive orders in
 *   consecutive memory.
 *
 *   A plain array used as a queue degrades: removing the head by shifting every
 *   remaining element left is O(n) per dequeue. Wrapping the indices with modulo
 *   turns the array into a ring, so the head simply advances and the freed slot
 *   is reused by a later enqueue. That gives O(1) enqueue, dequeue and peek with
 *   zero allocation per operation and zero shifting.
 *
 *   The capacity is fixed on purpose. A real exchange refuses an order when a
 *   level is saturated rather than growing without bound, so a full level
 *   returning false IS the required behaviour (the gateway counts it as a
 *   rejection). See PriceNode.LEVEL_CAPACITY.
 *
 * Implementation note on the backing array:
 *   The logical capacity is fixed, but the backing array starts small and
 *   doubles until it reaches that capacity. This is purely a memory decision and
 *   changes nothing an outside caller can observe: isFull() still becomes true at
 *   exactly `capacity` orders and enqueue still returns false there. The reason
 *   is the benchmark: one million distinct price levels each eagerly allocating a
 *   1024-slot array would be about 8 GB of mostly empty arrays. Levels almost
 *   always hold a handful of orders, so paying for 1024 slots up front is waste.
 *   Growth is amortised O(1) and happens at most log2(1024) = 10 times per level.
 */
public class CircularQueue {

    /* First array size. Levels are usually shallow, so start small and grow. */
    private static final int INITIAL_SLOTS = 8;

    private Order[] orders;
    private int head;      // index of the oldest order (next to be dequeued)
    private int tail;      // index of the next free slot to write
    private int count;     // number of orders currently held
    private final int capacity;

    /*
     * Creates an empty level queue that will accept at most `capacity` orders.
     * Complexity: O(1) time, O(min(capacity, INITIAL_SLOTS)) space.
     */
    public CircularQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.orders = new Order[Math.min(capacity, INITIAL_SLOTS)];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /*
     * Adds an order at the tail of the level (worst time priority).
     * Returns false when the level is already at capacity, which is how the
     * engine signals a rejection instead of throwing.
     * Complexity: O(1) amortised (the doubling copy is O(n) but happens at most
     * 10 times over the whole life of a level).
     */
    public boolean enqueue(Order order) {
        if (count == capacity) {
            return false;
        }
        if (count == orders.length) {
            grow();
        }
        orders[tail] = order;
        tail = (tail + 1) % orders.length;
        count++;
        return true;
    }

    /*
     * Removes and returns the oldest order (best time priority), or null when the
     * level is empty. The slot is nulled so the array does not pin filled orders
     * in memory.
     * Complexity: O(1).
     */
    public Order dequeue() {
        if (count == 0) {
            return null;
        }
        Order order = orders[head];
        orders[head] = null;
        head = (head + 1) % orders.length;
        count--;
        return order;
    }

    /*
     * Returns the oldest order without removing it, or null when empty.
     * The matcher peeks first because a partial fill must leave the order exactly
     * where it is, at the front of the queue.
     * Complexity: O(1).
     */
    public Order peek() {
        return count == 0 ? null : orders[head];
    }

    /* True when no orders rest at this level; the level is then deleted from the Treap. Complexity: O(1). */
    public boolean isEmpty() {
        return count == 0;
    }

    /* True when the level has hit its capacity and must reject further orders. Complexity: O(1). */
    public boolean isFull() {
        return count == capacity;
    }

    /* Number of orders resting at this level, shown as "orders" in the UI depth table. Complexity: O(1). */
    public int size() {
        return count;
    }

    /* The fixed logical capacity of this level. Complexity: O(1). */
    public int capacity() {
        return capacity;
    }

    /*
     * Sums the outstanding quantity across every order at this level. Used only
     * to build the depth snapshot for the UI (top 10 levels), never on the
     * matching hot path.
     * Complexity: O(k) where k is the number of resting orders at the level.
     */
    public long totalQuantity() {
        long total = 0;
        int index = head;
        for (int i = 0; i < count; i++) {
            total += orders[index].getQuantity();
            index = (index + 1) % orders.length;
        }
        return total;
    }

    /*
     * Doubles the backing array (never past the logical capacity) and re-lays the
     * ring out from index 0, so head becomes 0 and tail becomes count.
     * Complexity: O(n) in the current element count, amortised O(1) per enqueue.
     */
    private void grow() {
        int newLength = Math.min(capacity, orders.length * 2);
        Order[] bigger = new Order[newLength];
        for (int i = 0; i < count; i++) {
            bigger[i] = orders[(head + i) % orders.length];
        }
        orders = bigger;
        head = 0;
        tail = count;
    }
}
