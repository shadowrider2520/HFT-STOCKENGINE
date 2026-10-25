import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/*
 * TreapTest.java
 * Owner: Naren Kaarthi
 *
 * Verifies the two invariants the whole engine rests on:
 *   BST  - an in-order walk of the prices is strictly ascending
 *   HEAP - every node's priority is >= both of its children's
 * plus the practical claim that randomised balancing keeps the height low even
 * when prices arrive strictly sorted (the case that destroys a plain BST).
 *
 * All Treaps here are built with a fixed seed so heights are reproducible.
 *
 * Run: java -cp out TreapTest
 */
public class TreapTest {

    private static final long SEED = 42L;
    private static int failures = 0;
    private static int checks = 0;

    /*
     * Entry point: runs every case, prints one line per assertion, exits
     * non-zero if any assertion failed.
     * Complexity: dominated by the removal case, O(n^2) on a deliberately small n.
     */
    public static void main(String[] args) {
        testSequentialInsertHeight();
        testRandomInsertHeight();
        testFindMinMax();
        testFind();
        testDuplicatePriceSharesNode();
        testRemoveKeepsInvariants();
        testSizeAfterRemoves();
        testRemoveMissingPriceIsNoOp();
        testEmptyTreap();
        testLevelCapacityRejection();

        System.out.println();
        System.out.println(failures == 0
                ? "TreapTest: ALL PASS (" + checks + " checks)"
                : "TreapTest: " + failures + " FAILED of " + checks + " checks");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /*
     * The headline case: 10,000 strictly ascending prices. A plain BST would be
     * 10,000 deep here; the Treap must stay near 3*log2(10000) ~ 40.
     * Complexity: O(n log n).
     */
    private static void testSequentialInsertHeight() {
        Treap treap = new Treap(SEED);
        for (int price = 1; price <= 10000; price++) {
            treap.insert(buy(price));
        }
        int height = treap.height();
        check("sequential 1..10000: height " + height + " < 60", height < 60);
        check("sequential: size is 10000", treap.size() == 10000);
        check("sequential: BST order holds", isInOrderAscending(treap));
        check("sequential: heap property holds", isHeapOrdered(treap.getRoot()));
    }

    /*
     * Control case: the same count of prices in random order should produce a
     * similar height, showing the Treap is insensitive to input ordering.
     * Complexity: O(n log n).
     */
    private static void testRandomInsertHeight() {
        Treap treap = new Treap(SEED);
        Random rng = new Random(7L);
        for (int i = 0; i < 10000; i++) {
            treap.insert(buy(rng.nextInt(1000000) + 1));
        }
        int height = treap.height();
        check("random 10000: height " + height + " < 60", height < 60);
        check("random: BST order holds", isInOrderAscending(treap));
        check("random: heap property holds", isHeapOrdered(treap.getRoot()));
    }

    /*
     * findMax must give the best bid and findMin the best ask; these two calls
     * are what the matcher asks on every order.
     * Complexity: O(n log n).
     */
    private static void testFindMinMax() {
        Treap treap = new Treap(SEED);
        int[] prices = {500, 120, 980, 7, 640, 331, 1203, 45};
        for (int price : prices) {
            treap.insert(buy(price));
        }
        check("findMin returns lowest price", treap.findMin().getPrice() == 7);
        check("findMax returns highest price", treap.findMax().getPrice() == 1203);

        treap.remove(7);
        treap.remove(1203);
        check("findMin updates after removal", treap.findMin().getPrice() == 45);
        check("findMax updates after removal", treap.findMax().getPrice() == 980);
    }

    /* Exact lookup must find present prices and return null for absent ones. Complexity: O(n log n). */
    private static void testFind() {
        Treap treap = new Treap(SEED);
        for (int price = 100; price <= 200; price += 10) {
            treap.insert(buy(price));
        }
        check("find locates an existing price", treap.find(150) != null && treap.find(150).getPrice() == 150);
        check("find returns null for a missing price", treap.find(155) == null);
    }

    /*
     * Two orders at the same price must share one node and queue in arrival
     * order; the tree must not grow a second node for the same key.
     * Complexity: O(1).
     */
    private static void testDuplicatePriceSharesNode() {
        Treap treap = new Treap(SEED);
        treap.insert(new Order("T1", "X", true, 100, 10, 1L));
        treap.insert(new Order("T2", "X", true, 100, 20, 2L));
        treap.insert(new Order("T3", "X", true, 100, 30, 3L));

        check("duplicate price: only one node", treap.size() == 1);
        PriceNode node = treap.find(100);
        check("duplicate price: three orders queued", node != null && node.getQueue().size() == 3);
        check("duplicate price: FIFO preserved", node != null && "T1".equals(node.getQueue().peek().getTraderId()));
        check("duplicate price: quantities summed", node != null && node.totalQuantity() == 60);
    }

    /*
     * Removes every node one at a time, re-checking both invariants after each
     * removal. Deliberately uses a small tree because the check itself is a full
     * traversal, making the case O(n^2).
     * Complexity: O(n^2) with n = 300.
     */
    private static void testRemoveKeepsInvariants() {
        Treap treap = new Treap(SEED);
        List<Integer> prices = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            prices.add(i * 3);
            treap.insert(buy(i * 3));
        }
        Collections.shuffle(prices, new Random(11L));

        boolean bstHeld = true;
        boolean heapHeld = true;
        boolean sizeHeld = true;
        int expected = 300;

        for (int price : prices) {
            treap.remove(price);
            expected--;
            if (!isInOrderAscending(treap)) {
                bstHeld = false;
            }
            if (!isHeapOrdered(treap.getRoot())) {
                heapHeld = false;
            }
            if (treap.size() != expected) {
                sizeHeld = false;
            }
            if (treap.find(price) != null) {
                bstHeld = false;
            }
        }
        check("remove-all: BST order held after every removal", bstHeld);
        check("remove-all: heap property held after every removal", heapHeld);
        check("remove-all: size decremented on every removal", sizeHeld);
        check("remove-all: tree is empty at the end", treap.size() == 0 && treap.isEmpty());
        check("remove-all: findMin/findMax null when empty",
                treap.findMin() == null && treap.findMax() == null);
    }

    /* Size must track a mixed sequence of inserts and removes exactly. Complexity: O(n log n). */
    private static void testSizeAfterRemoves() {
        Treap treap = new Treap(SEED);
        for (int price = 1; price <= 1000; price++) {
            treap.insert(buy(price));
        }
        check("size: 1000 after inserts", treap.size() == 1000);
        for (int price = 1; price <= 1000; price += 2) {
            treap.remove(price);
        }
        check("size: 500 after removing every other level", treap.size() == 500);
        check("size: odd prices gone", treap.find(501) == null);
        check("size: even prices kept", treap.find(500) != null);
        check("size: BST order survives bulk removal", isInOrderAscending(treap));
        check("size: heap property survives bulk removal", isHeapOrdered(treap.getRoot()));
    }

    /* Removing a price that was never inserted must not corrupt or resize the tree. Complexity: O(log n). */
    private static void testRemoveMissingPriceIsNoOp() {
        Treap treap = new Treap(SEED);
        treap.insert(buy(10));
        treap.insert(buy(20));
        treap.remove(15);
        check("remove missing: size unchanged", treap.size() == 2);
        check("remove missing: existing levels intact",
                treap.find(10) != null && treap.find(20) != null);
    }

    /* An empty ladder must answer every query with null rather than throwing. Complexity: O(1). */
    private static void testEmptyTreap() {
        Treap treap = new Treap(SEED);
        check("empty: size 0", treap.size() == 0);
        check("empty: height 0", treap.height() == 0);
        check("empty: findMin null", treap.findMin() == null);
        check("empty: findMax null", treap.findMax() == null);
        check("empty: find null", treap.find(1) == null);
        check("empty: isEmpty true", treap.isEmpty());
    }

    /*
     * A level that reaches LEVEL_CAPACITY must refuse further orders through the
     * Treap's insert, which is how the engine surfaces a rejection.
     * Complexity: O(LEVEL_CAPACITY).
     */
    private static void testLevelCapacityRejection() {
        Treap treap = new Treap(SEED);
        boolean allAccepted = true;
        for (int i = 0; i < PriceNode.LEVEL_CAPACITY; i++) {
            if (!treap.insert(new Order("T" + i, "X", true, 777, 1, i))) {
                allAccepted = false;
            }
        }
        check("capacity: all " + PriceNode.LEVEL_CAPACITY + " accepted", allAccepted);
        check("capacity: one node only", treap.size() == 1);
        check("capacity: next insert rejected",
                !treap.insert(new Order("OVER", "X", true, 777, 1, 99999L)));
    }

    /*
     * Iterative in-order traversal checking that prices come out strictly
     * ascending, which is the BST invariant. Iterative so a degenerate tree
     * could never overflow the stack during a test.
     * Complexity: O(n) time, O(height) space.
     */
    private static boolean isInOrderAscending(Treap treap) {
        java.util.ArrayDeque<PriceNode> stack = new java.util.ArrayDeque<>();
        PriceNode current = treap.getRoot();
        long previous = Long.MIN_VALUE;
        while (current != null || !stack.isEmpty()) {
            while (current != null) {
                stack.push(current);
                current = current.left;
            }
            current = stack.pop();
            if (current.getPrice() <= previous) {
                return false;
            }
            previous = current.getPrice();
            current = current.right;
        }
        return true;
    }

    /*
     * Checks the max-heap invariant on priorities over the whole tree, and that
     * no node is left holding an empty queue.
     * Complexity: O(n) time, O(height) space.
     */
    private static boolean isHeapOrdered(PriceNode root) {
        if (root == null) {
            return true;
        }
        java.util.ArrayDeque<PriceNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            PriceNode node = stack.pop();
            if (node.getQueue().isEmpty()) {
                return false;
            }
            if (node.left != null) {
                if (node.left.getPriority() > node.getPriority()) {
                    return false;
                }
                stack.push(node.left);
            }
            if (node.right != null) {
                if (node.right.getPriority() > node.getPriority()) {
                    return false;
                }
                stack.push(node.right);
            }
        }
        return true;
    }

    /* Builds a throwaway buy order at the given price. Complexity: O(1). */
    private static Order buy(int price) {
        return new Order("T", "X", true, price, 1, price);
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
