/*
 * QueueTest.java
 * Owner: Giri Prasath
 *
 * Verifies the circular queue contract that time priority depends on:
 * strict FIFO, correct behaviour at the capacity boundary, correct index
 * wrap-around when the ring reuses freed slots, and a safe empty case.
 *
 * Run: java -cp out QueueTest
 */
public class QueueTest {

    private static int failures = 0;
    private static int checks = 0;

    /*
     * Entry point. Runs every case, prints one line per case and exits non-zero
     * if anything failed, so the shell / CI can tell success from failure.
     * Complexity: O(1) overall (all cases use tiny fixed-size queues).
     */
    public static void main(String[] args) {
        testFifoOrder();
        testEnqueueOnFullReturnsFalse();
        testWrapAroundAfterDequeue();
        testEmptyDequeueReturnsNull();
        testPeekDoesNotRemove();
        testSizeAndFlags();
        testGrowthPreservesFifo();
        testTotalQuantity();

        System.out.println();
        System.out.println(failures == 0
                ? "QueueTest: ALL PASS (" + checks + " checks)"
                : "QueueTest: " + failures + " FAILED of " + checks + " checks");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /* Orders must come back out in exactly the order they went in. Complexity: O(n). */
    private static void testFifoOrder() {
        CircularQueue q = new CircularQueue(5);
        q.enqueue(order("A", 10));
        q.enqueue(order("B", 10));
        q.enqueue(order("C", 10));
        check("FIFO 1st out is A", "A".equals(q.dequeue().getTraderId()));
        check("FIFO 2nd out is B", "B".equals(q.dequeue().getTraderId()));
        check("FIFO 3rd out is C", "C".equals(q.dequeue().getTraderId()));
        check("FIFO queue now empty", q.isEmpty());
    }

    /* A level at capacity must refuse, not grow and not throw. Complexity: O(capacity). */
    private static void testEnqueueOnFullReturnsFalse() {
        CircularQueue q = new CircularQueue(3);
        check("full: 1st accepted", q.enqueue(order("A", 1)));
        check("full: 2nd accepted", q.enqueue(order("B", 1)));
        check("full: 3rd accepted", q.enqueue(order("C", 1)));
        check("full: isFull true at capacity", q.isFull());
        check("full: 4th rejected", !q.enqueue(order("D", 1)));
        check("full: size still 3 after rejection", q.size() == 3);
    }

    /* After freeing a slot at the head, a new tail write must wrap to index 0. Complexity: O(capacity). */
    private static void testWrapAroundAfterDequeue() {
        CircularQueue q = new CircularQueue(3);
        q.enqueue(order("A", 1));
        q.enqueue(order("B", 1));
        q.enqueue(order("C", 1));
        check("wrap: head out is A", "A".equals(q.dequeue().getTraderId()));
        check("wrap: tail write reuses freed slot", q.enqueue(order("D", 1)));
        check("wrap: still full", q.isFull());
        check("wrap: next out is B", "B".equals(q.dequeue().getTraderId()));
        check("wrap: next out is C", "C".equals(q.dequeue().getTraderId()));
        check("wrap: wrapped element D comes last", "D".equals(q.dequeue().getTraderId()));
        check("wrap: empty at end", q.isEmpty());
    }

    /* Dequeue on an empty queue returns null rather than throwing. Complexity: O(1). */
    private static void testEmptyDequeueReturnsNull() {
        CircularQueue q = new CircularQueue(4);
        check("empty: dequeue null", q.dequeue() == null);
        check("empty: peek null", q.peek() == null);
        check("empty: isEmpty true", q.isEmpty());
        check("empty: size 0", q.size() == 0);
    }

    /* Peek must leave the order in place; partial fills rely on this. Complexity: O(1). */
    private static void testPeekDoesNotRemove() {
        CircularQueue q = new CircularQueue(4);
        q.enqueue(order("A", 50));
        check("peek: returns head", "A".equals(q.peek().getTraderId()));
        check("peek: size unchanged", q.size() == 1);
        check("peek: same object twice", q.peek() == q.peek());
    }

    /* size(), isEmpty() and isFull() must track every operation. Complexity: O(capacity). */
    private static void testSizeAndFlags() {
        CircularQueue q = new CircularQueue(2);
        check("flags: empty at start", q.isEmpty() && !q.isFull() && q.size() == 0);
        q.enqueue(order("A", 1));
        check("flags: one element", !q.isEmpty() && !q.isFull() && q.size() == 1);
        q.enqueue(order("B", 1));
        check("flags: full", !q.isEmpty() && q.isFull() && q.size() == 2);
        q.dequeue();
        check("flags: back to one", !q.isFull() && q.size() == 1);
    }

    /* The internal array doubling must not disturb FIFO order. Complexity: O(capacity). */
    private static void testGrowthPreservesFifo() {
        CircularQueue q = new CircularQueue(64);
        for (int i = 0; i < 64; i++) {
            q.enqueue(order("T" + i, 1));
        }
        check("growth: accepted all 64", q.size() == 64 && q.isFull());
        boolean ordered = true;
        for (int i = 0; i < 64; i++) {
            Order out = q.dequeue();
            if (out == null || !("T" + i).equals(out.getTraderId())) {
                ordered = false;
                break;
            }
        }
        check("growth: FIFO preserved across resizes", ordered);
        check("growth: rejects past capacity after refill", refillAndOverflow(q));
    }

    /* Helper: fills a 64-slot queue again and confirms the 65th is refused. Complexity: O(capacity). */
    private static boolean refillAndOverflow(CircularQueue q) {
        for (int i = 0; i < 64; i++) {
            q.enqueue(order("R" + i, 1));
        }
        return !q.enqueue(order("X", 1));
    }

    /* Depth snapshot totals must sum outstanding quantity, including after a wrap. Complexity: O(k). */
    private static void testTotalQuantity() {
        CircularQueue q = new CircularQueue(3);
        q.enqueue(order("A", 10));
        q.enqueue(order("B", 20));
        q.enqueue(order("C", 30));
        check("total: sum of all three", q.totalQuantity() == 60);
        q.dequeue();
        q.enqueue(order("D", 5));
        check("total: correct after wrap", q.totalQuantity() == 55);
    }

    /* Builds a throwaway buy order for queue tests. Complexity: O(1). */
    private static Order order(String trader, int qty) {
        return new Order(trader, "TEST", true, 100, qty, 0L);
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
