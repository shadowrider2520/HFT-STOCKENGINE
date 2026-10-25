import java.util.List;

/*
 * OrderGateway.java
 * Owner: Mithun Prasath K
 *
 * The door into the exchange. Everything from outside - the web form, the
 * simulator, the console demo - arrives here as a flat text line and leaves as
 * either a validated Order or an exception.
 *
 * Why a separate class instead of parsing inside the controller:
 *   Validation and matching are different jobs with different failure modes. A
 *   malformed line is a client error (reject the message, HTTP 400); a full
 *   price level is an exchange-state error (the order was well formed but the
 *   book could not take it). Keeping them apart means the matching engine only
 *   ever sees structurally valid orders and never has to defend against a null
 *   ticker or a negative quantity on the hot path.
 *
 * Wire format: traderId,ticker,BUY|SELL,price,qty
 *   Deliberately plain text. No library is allowed, and a comma-separated line
 *   parses with one split and four conversions, which keeps the gateway cheap
 *   enough to sit in front of a million-order benchmark.
 */
public class OrderGateway {

    private static final int MAX_QUANTITY = 1000000;
    private static final int MAX_PRICE = 100000000;
    private static final int MAX_TRADER_LENGTH = 20;

    private final MatchingController controller;
    private int acceptedCount;
    private int rejectedCount;

    public OrderGateway(MatchingController controller) {
        this.controller = controller;
    }

    public Order parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty order");
        }
        String line = raw.trim();
        if (line.isEmpty()) {
            throw new IllegalArgumentException("empty order");
        }

        String[] parts = line.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "expected 5 fields traderId,ticker,BUY|SELL,price,qty but got " + parts.length);
        }

        String traderId = parts[0].trim();
        String ticker = parts[1].trim().toUpperCase();
        String side = parts[2].trim().toUpperCase();

        if (traderId.isEmpty()) {
            throw new IllegalArgumentException("traderId is empty");
        }
        if (ticker.isEmpty()) {
            throw new IllegalArgumentException("ticker is empty");
        }
        if (!isSafeIdentifier(traderId) || !isSafeIdentifier(ticker)) {
            throw new IllegalArgumentException("traderId and ticker allow only letters, digits, . _ -");
        }
        if (traderId.length() > MAX_TRADER_LENGTH || ticker.length() > MAX_TRADER_LENGTH) {
            throw new IllegalArgumentException("traderId and ticker max length is " + MAX_TRADER_LENGTH);
        }

        boolean isBuy;
        if (side.equals("BUY")) {
            isBuy = true;
        } else if (side.equals("SELL")) {
            isBuy = false;
        } else {
            throw new IllegalArgumentException("side must be BUY or SELL, got '" + parts[2].trim() + "'");
        }

        int price = parseInt(parts[3].trim(), "price");
        int quantity = parseInt(parts[4].trim(), "qty");

        if (price <= 0 || price > MAX_PRICE) {
            throw new IllegalArgumentException("price must be between 1 and " + MAX_PRICE);
        }
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("qty must be between 1 and " + MAX_QUANTITY);
        }

        return new Order(traderId, ticker, isBuy, price, quantity, System.nanoTime());
    }

    public boolean submit(Order order) {
        boolean accepted;
        synchronized (controller) {
            long rejectedBefore = controller.getRejectedCount();
            controller.processOrder(order);
            accepted = controller.getRejectedCount() == rejectedBefore;
        }
        if (accepted) {
            acceptedCount++;
        } else {
            rejectedCount++;
        }
        return accepted;
    }

    public boolean submitRaw(String raw) {
        return submit(parse(raw));
    }

    public List<Trade> lastTrades() {
        return controller.getLastTrades();
    }

    public MatchingController getController() {
        return controller;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    private static int parseInt(String text, String field) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number, got '" + text + "'");
        }
    }

    private static boolean isSafeIdentifier(String text) {
        if (text.length() > 32) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}