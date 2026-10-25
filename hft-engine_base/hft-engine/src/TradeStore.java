import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * TradeStore.java
 * Owner: Gathin
 *
 * Append-only CSV persistence for trades and orders. On startup loads both
 * into memory so history, candles and trader stats survive restarts.
 * Open-book state is NOT persisted (history only).
 * Writes buffered, flushed every 1 s and on shutdown hook.
 */
public class TradeStore {

    private static final Path DATA_DIR = Paths.get("data");
    private static final Path TRADES_CSV = DATA_DIR.resolve("trades.csv");
    private static final Path ORDERS_CSV = DATA_DIR.resolve("orders.csv");

    private final List<TradeRecord> allTrades = new ArrayList<>();
    private final List<TradeRecord> tradeBuffer = new ArrayList<>();
    private final List<OrderRecord> allOrders = new ArrayList<>();
    private final List<OrderRecord> orderBuffer = new ArrayList<>();
    private final Map<String, TraderStats> traderStats = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed = false;

    public TradeStore() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            System.err.println("TradeStore: failed to create data dir: " + e);
        }
        load();
        flusher.scheduleAtFixedRate(this::flush, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    private void load() {
        loadTrades();
        loadOrders();
        rebuildStats();
    }

    private void loadTrades() {
        if (!Files.exists(TRADES_CSV)) return;
        try {
            List<String> lines = Files.readAllLines(TRADES_CSV);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                if (p.length >= 6) {
                    try {
                        long ts = Long.parseLong(p[0]);
                        String ticker = p[1];
                        String buyer = p[2];
                        String seller = p[3];
                        int price = Integer.parseInt(p[4]);
                        int qty = Integer.parseInt(p[5]);
                        TradeRecord record = new TradeRecord(ts, ticker, buyer, seller, price, qty);
                        allTrades.add(record);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("TradeStore: failed to load trades: " + e);
        }
    }

    private void loadOrders() {
        if (!Files.exists(ORDERS_CSV)) return;
        try {
            List<String> lines = Files.readAllLines(ORDERS_CSV);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                if (p.length >= 6) {
                    try {
                        long ts = Long.parseLong(p[0]);
                        String trader = p[1];
                        String ticker = p[2];
                        String side = p[3];
                        int price = Integer.parseInt(p[4]);
                        int qty = Integer.parseInt(p[5]);
                        OrderRecord record = new OrderRecord(ts, trader, ticker, side, price, qty);
                        allOrders.add(record);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("TradeStore: failed to load orders: " + e);
        }
    }

    private void rebuildStats() {
        for (TradeRecord t : allTrades) {
            updateStats(t.buyer, t.ticker, true, t.price, t.qty, t.ts);
            updateStats(t.seller, t.ticker, false, t.price, t.qty, t.ts);
        }
    }

    private void updateStats(String traderId, String ticker, boolean isBuy, int price, int qty, long ts) {
        TraderStats stats = traderStats.computeIfAbsent(traderId, TraderStats::new);
        stats.addTrade(isBuy, price, qty, ts);
    }

    public synchronized void recordTrade(Trade trade) {
        TradeRecord record = new TradeRecord(trade.getTimestamp(), trade.getTicker(),
                trade.getBuyerId(), trade.getSellerId(), trade.getPrice(), trade.getQuantity());
        allTrades.add(record);
        tradeBuffer.add(record);
        updateStats(trade.getBuyerId(), trade.getTicker(), true, trade.getPrice(), trade.getQuantity(), trade.getTimestamp());
        updateStats(trade.getSellerId(), trade.getTicker(), false, trade.getPrice(), trade.getQuantity(), trade.getTimestamp());
    }

    public synchronized void recordOrder(Order order) {
        OrderRecord record = new OrderRecord(order.getTimestamp(), order.getTraderId(), order.getTicker(),
                order.isBuy() ? "BUY" : "SELL", order.getPrice(), order.getQuantity());
        allOrders.add(record);
        orderBuffer.add(record);
    }

    public List<TradeRecord> getTrades() {
        return new ArrayList<>(allTrades);
    }

    public List<OrderRecord> getOrders() {
        return new ArrayList<>(allOrders);
    }

    public TraderStats getTraderStats(String traderId) {
        return traderStats.get(traderId);
    }

    public Map<String, TraderStats> getAllTraderStats() {
        return new ConcurrentHashMap<>(traderStats);
    }

    public synchronized void flush() {
        if (closed) return;
        try (BufferedWriter tw = new BufferedWriter(new FileWriter(TRADES_CSV.toFile(), true));
             BufferedWriter ow = new BufferedWriter(new FileWriter(ORDERS_CSV.toFile(), true))) {
            for (TradeRecord t : tradeBuffer) {
                tw.write(t.ts + "," + t.ticker + "," + t.buyer + "," + t.seller + "," + t.price + "," + t.qty);
                tw.newLine();
            }
            for (OrderRecord o : orderBuffer) {
                ow.write(o.ts + "," + o.trader + "," + o.ticker + "," + o.side + "," + o.price + "," + o.qty);
                ow.newLine();
            }
            tradeBuffer.clear();
            orderBuffer.clear();
        } catch (IOException e) {
            System.err.println("TradeStore: flush failed: " + e);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        flusher.shutdownNow();
        flush();
    }

    public static class TradeRecord {
        public final long ts;
        public final String ticker;
        public final String buyer;
        public final String seller;
        public final int price;
        public final int qty;

        public TradeRecord(long ts, String ticker, String buyer, String seller, int price, int qty) {
            this.ts = ts; this.ticker = ticker; this.buyer = buyer; this.seller = seller; this.price = price; this.qty = qty;
        }
    }

    public static class OrderRecord {
        public final long ts;
        public final String trader;
        public final String ticker;
        public final String side;
        public final int price;
        public final int qty;

        public OrderRecord(long ts, String trader, String ticker, String side, int price, int qty) {
            this.ts = ts; this.trader = trader; this.ticker = ticker; this.side = side; this.price = price; this.qty = qty;
        }
    }
}