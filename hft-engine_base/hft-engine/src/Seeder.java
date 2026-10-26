import java.util.Random;

/*
 * Seeder.java
 * Owner: Mithun Prasath K
 *
 * If the store is empty at startup, generate ~500 demo orders through the
 * normal gateway so charts and tables are never blank on first open.
 */
public class Seeder {

    private static final String[] DEMO_TRADERS = {"alice", "bob", "carol", "dave", "erin"};
    private static final String[] DEMO_TICKERS = {"AAPL", "TSLA", "BTCUSDT"};
    private static final int TARGET_ORDERS = 500;

    private final OrderGateway gateway;
    private final PriceFeeder feeder;
    private final Random random;

    public Seeder(OrderGateway gateway, PriceFeeder feeder, long seed) {
        this.gateway = gateway;
        this.feeder = feeder;
        this.random = new Random(seed);
    }

    public void seedIfEmpty() {
        if (!gateway.getController().getTickers().isEmpty()) {
            return; // already has data
        }
        System.out.println("Seeder: generating demo history...");
        for (int i = 0; i < TARGET_ORDERS; i++) {
            String trader = DEMO_TRADERS[random.nextInt(DEMO_TRADERS.length)];
            String ticker = DEMO_TICKERS[random.nextInt(DEMO_TICKERS.length)];
            boolean isBuy = random.nextBoolean();
            int price = feeder.nextPrice(ticker) + random.nextInt(11) - 5;
            if (price < 1) price = 1;
            int qty = random.nextInt(100) + 1;
            String raw = trader + "," + ticker + "," + (isBuy ? "BUY" : "SELL") + "," + price + "," + qty;
            try {
                gateway.submitRaw(raw);
            } catch (IllegalArgumentException ignored) {}
        }
        System.out.println("Seeder: done (" + gateway.getController().getTotalTrades() + " trades generated)");
    }
}
