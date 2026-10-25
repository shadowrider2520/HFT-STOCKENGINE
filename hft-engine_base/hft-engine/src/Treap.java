import java.util.Random;

/*
 * Treap.java
 * Owner: Naren Kaarthi
 *
 * The price ladder for one side of one book: a randomised binary search tree
 * keyed on price, where each node is also a price level holding a FIFO queue.
 *
 * WHY A TREAP (the central data-structure decision of this project)
 *
 *   The matching engine needs four things from the price ladder:
 *     1. insert a new price level                      -> O(log N)
 *     2. delete a price level once it is emptied        -> O(log N)
 *     3. find the best price (max bid / min ask)        -> O(log N)
 *     4. find an exact price so a new order joins its existing level -> O(log N)
 *
 *   A plain BST gives all four in O(log N) only if the keys arrive in random
 *   order. They do not. Prices in a live market trend: they walk up or down, so
 *   the keys arrive close to sorted. A BST fed sorted keys becomes a linked list
 *   of height N, and every operation collapses to O(N). PlainBst in this project
 *   exists only to demonstrate that failure: 1,000,000 ascending keys give it a
 *   height of 1,000,000.
 *
 *   A Treap fixes this without the bookkeeping of a red-black or AVL tree. Each
 *   node gets a random priority at birth, and the tree is kept as a max-heap on
 *   those priorities while staying a BST on price. The crucial consequence: for
 *   a given set of keys, the tree shape is determined entirely by the random
 *   priorities, NOT by the insertion order. So inserting sorted prices produces
 *   the same expected shape as inserting shuffled prices - expected height
 *   ~3*log2(N) (about 60 at N = 1,000,000), regardless of the input pattern.
 *
 *   Compared with the alternatives:
 *     - AVL / red-black: the same O(log N) guarantee but worst-case rather than
 *       expected, at the cost of height or colour fields, several rebalancing
 *       cases and considerably more code to get right. A Treap rebalances with
 *       one comparison and one rotation per level.
 *     - Skip list: similar expected bounds, but several nodes and forward-pointer
 *       arrays per key, so far more memory for a million price levels.
 *     - Hash map of price -> queue: O(1) exact lookup, but it cannot answer
 *       "what is the best price", which is requirement 3 and the one the matcher
 *       asks on every single order. Finding the max of a hash map is O(N).
 *     - Sorted array: O(log N) search but O(N) insert and delete from shifting.
 *
 *   The honest caveat for the report: the bound is EXPECTED, not worst case. A
 *   pathological run of random priorities could still build a tall tree; the
 *   probability is vanishingly small but it is not zero, unlike AVL.
 *
 * Complexity summary (N = number of distinct price levels on this side):
 *   insert   expected O(log N)      find     expected O(log N)
 *   remove   expected O(log N)      findMin  expected O(log N)
 *   height   O(N)                   findMax  expected O(log N)
 *   size     O(1)
 */
public class Treap {

    public interface Listener {
        void onEvent(String type, int price, String detail);
    }

    private PriceNode root;
    private final Random random;
    private int size;
    private boolean lastRemoveHit;
    private Listener listener;

    public Treap() {
        this(System.nanoTime(), null);
    }

    public Treap(long seed) {
        this(seed, null);
    }

    public Treap(long seed, Listener listener) {
        this.random = new Random(seed);
        this.root = null;
        this.size = 0;
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean insert(Order order) {
        PriceNode existing = find(order.getPrice());
        if (existing != null) {
            boolean result = existing.queue.enqueue(order);
            if (result && listener != null) {
                listener.onEvent("ENQUEUE", existing.price, "trader=" + order.getTraderId() + " qty=" + order.getQuantity());
            }
            return result;
        }
        PriceNode node = new PriceNode(order.getPrice(), random.nextInt());
        if (!node.queue.enqueue(order)) {
            return false;
        }
        root = insertNode(root, node);
        size++;
        if (listener != null) {
            listener.onEvent("NODE_INSERT", node.price, "priority=" + node.priority);
        }
        return true;
    }

    private PriceNode insertNode(PriceNode current, PriceNode node) {
        if (current == null) {
            return node;
        }
        if (node.price < current.price) {
            current.left = insertNode(current.left, node);
            if (current.left.priority > current.priority) {
                current = rotateRight(current);
                if (listener != null) {
                    listener.onEvent("ROTATE_RIGHT", current.price, "rotated " + current.right.price + " up");
                }
            }
        } else {
            current.right = insertNode(current.right, node);
            if (current.right.priority > current.priority) {
                current = rotateLeft(current);
                if (listener != null) {
                    listener.onEvent("ROTATE_LEFT", current.price, "rotated " + current.left.price + " up");
                }
            }
        }
        return current;
    }

    public void remove(int price) {
        lastRemoveHit = false;
        PriceNode toRemove = find(price);
        if (toRemove != null && listener != null) {
            listener.onEvent("NODE_DELETE", price, "level emptied");
        }
        root = removeNode(root, price);
        if (lastRemoveHit) {
            size--;
        }
    }

    private PriceNode removeNode(PriceNode current, int price) {
        if (current == null) {
            return null;
        }
        if (price < current.price) {
            current.left = removeNode(current.left, price);
            return current;
        }
        if (price > current.price) {
            current.right = removeNode(current.right, price);
            return current;
        }

        if (current.left == null && current.right == null) {
            lastRemoveHit = true;
            return null;
        }
        if (current.left == null) {
            current = rotateLeft(current);
            if (listener != null) {
                listener.onEvent("ROTATE_LEFT", current.price, "removal rotate");
            }
            current.left = removeNode(current.left, price);
        } else if (current.right == null) {
            current = rotateRight(current);
            if (listener != null) {
                listener.onEvent("ROTATE_RIGHT", current.price, "removal rotate");
            }
            current.right = removeNode(current.right, price);
        } else if (current.left.priority > current.right.priority) {
            current = rotateRight(current);
            if (listener != null) {
                listener.onEvent("ROTATE_RIGHT", current.price, "removal rotate");
            }
            current.right = removeNode(current.right, price);
        } else {
            current = rotateLeft(current);
            if (listener != null) {
                listener.onEvent("ROTATE_LEFT", current.price, "removal rotate");
            }
            current.left = removeNode(current.left, price);
        }
        return current;
    }

    public PriceNode find(int price) {
        PriceNode current = root;
        while (current != null) {
            if (price == current.price) {
                return current;
            }
            current = price < current.price ? current.left : current.right;
        }
        return null;
    }

    public PriceNode findMax() {
        if (root == null) {
            return null;
        }
        PriceNode current = root;
        while (current.right != null) {
            current = current.right;
        }
        return current;
    }

    public PriceNode findMin() {
        if (root == null) {
            return null;
        }
        PriceNode current = root;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    public int height() {
        if (root == null) {
            return 0;
        }
        PriceNode[] nodeStack = new PriceNode[64];
        int[] depthStack = new int[64];
        int top = 0;
        nodeStack[top] = root;
        depthStack[top] = 1;
        top++;
        int best = 0;

        while (top > 0) {
            top--;
            PriceNode node = nodeStack[top];
            int depth = depthStack[top];
            if (depth > best) {
                best = depth;
            }
            if (top + 2 > nodeStack.length) {
                PriceNode[] grownNodes = new PriceNode[nodeStack.length * 2];
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

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return root == null;
    }

    public PriceNode getRoot() {
        return root;
    }

    private PriceNode rotateRight(PriceNode pivot) {
        PriceNode newRoot = pivot.left;
        pivot.left = newRoot.right;
        newRoot.right = pivot;
        return newRoot;
    }

    private PriceNode rotateLeft(PriceNode pivot) {
        PriceNode newRoot = pivot.right;
        pivot.right = newRoot.left;
        newRoot.left = pivot;
        return newRoot;
    }

    public String toJson(int depth) {
        StringBuilder sb = new StringBuilder();
        toJsonNode(sb, root, depth, 0);
        return sb.toString();
    }

    private void toJsonNode(StringBuilder sb, PriceNode node, int maxDepth, int currentDepth) {
        if (node == null || currentDepth >= maxDepth) return;
        sb.append("{");
        sb.append("\"price\":").append(node.price);
        sb.append(",\"priority\":").append(node.priority);
        sb.append(",\"orders\":").append(node.orderCount());
        sb.append(",\"qty\":").append(node.totalQuantity());
        if (node.left != null || node.right != null) {
            sb.append(",\"left\":");
            if (node.left != null) toJsonNode(sb, node.left, maxDepth, currentDepth + 1);
            else sb.append("null");
            sb.append(",\"right\":");
            if (node.right != null) toJsonNode(sb, node.right, maxDepth, currentDepth + 1);
            else sb.append("null");
        }
        sb.append("}");
    }
}