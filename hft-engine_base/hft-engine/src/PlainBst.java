/*
 * PlainBst.java
 * Owner: Naren Kaarthi
 *
 * An ordinary unbalanced binary search tree. It is NOT used by the engine; it
 * exists purely as the control group in the benchmark.
 *
 * Why it is here:
 *   The claim this project makes is that randomised balancing is what keeps the
 *   price ladder fast on realistic input. A claim like that needs a baseline.
 *   This class is the same structure as the Treap with the random priority and
 *   the rotations taken away, so the benchmark can insert the same ascending key
 *   sequence into both and show the difference in height directly:
 *
 *       sequential keys -> PlainBst height = N        (a linked list)
 *       sequential keys -> Treap    height ~ 3*log2 N (about 60 at N = 1,000,000)
 *
 *   Height is the honest metric to compare, because every search, insert and
 *   delete in either tree costs one comparison per level.
 *
 * Both methods are iterative on purpose. A tree of height one million would
 * blow the call stack long before it ran slowly, and a StackOverflowError would
 * hide the very result the benchmark is trying to show.
 */
public class PlainBst {

    /* Node of the baseline tree: a key and two children, nothing else. */
    private static final class Node {
        final int key;
        Node left;
        Node right;

        /* Creates a leaf. Complexity: O(1). */
        Node(int key) {
            this.key = key;
        }
    }

    private Node root;
    private int size;

    /*
     * Inserts a key, ignoring duplicates. There is no rebalancing at all, which
     * is exactly the point: ascending keys walk right every time and the tree
     * grows one level per insert.
     * Complexity: O(height). O(log N) on random input, O(N) on sorted input.
     */
    public void insert(int key) {
        if (root == null) {
            root = new Node(key);
            size++;
            return;
        }
        Node current = root;
        while (true) {
            if (key < current.key) {
                if (current.left == null) {
                    current.left = new Node(key);
                    size++;
                    return;
                }
                current = current.left;
            } else if (key > current.key) {
                if (current.right == null) {
                    current.right = new Node(key);
                    size++;
                    return;
                }
                current = current.right;
            } else {
                return; // duplicate key, nothing to do
            }
        }
    }

    /*
     * Longest root-to-leaf path in nodes - the number the benchmark reports.
     * Uses an explicit stack because the whole point of this class is to produce
     * trees far too deep for recursion.
     * Complexity: O(N) time, O(height) space.
     */
    public int height() {
        if (root == null) {
            return 0;
        }
        Node[] nodeStack = new Node[64];
        int[] depthStack = new int[64];
        int top = 0;
        nodeStack[top] = root;
        depthStack[top] = 1;
        top++;
        int best = 0;

        while (top > 0) {
            top--;
            Node node = nodeStack[top];
            int depth = depthStack[top];
            if (depth > best) {
                best = depth;
            }
            if (top + 2 > nodeStack.length) {
                Node[] grownNodes = new Node[nodeStack.length * 2];
                int[] grownDepths = new int[depthStack.length * 2];
                System.arraycopy(nodeStack, 0, grownNodes, 0, top);
                System.arraycopy(depthStack, 0, grownDepths, 0, top);
                nodeStack = grownNodes;
                depthStack = grownDepths;
            }
            if (node.left != null) {
                nodeStack[top] = node.left;
                depthStack[top] = depth + 1;
                top++;
            }
            if (node.right != null) {
                nodeStack[top] = node.right;
                depthStack[top] = depth + 1;
                top++;
            }
        }
        return best;
    }

    /* Number of distinct keys stored. Complexity: O(1). */
    public int size() {
        return size;
    }
}
