import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * WebServer.java
 * Owner: Gathin
 *
 * HTTP front end for the engine, built on the JDK's own com.sun.net.httpserver.
 *
 * Why this server and not a framework:
 *   The project allows no external libraries, and the surface needed here is
 *   seven endpoints returning small JSON documents. The JDK server covers that
 *   in a few hundred lines with no build tooling, no dependency tree and a
 *   container image that is just a JRE plus class files. Anything heavier would
 *   add far more deployment weight than functionality.
 *
 * Why JSON is assembled by hand:
 *   Same constraint. The response shapes are fixed and every value placed in a
 *   string position is either a number or an identifier the gateway has already
 *   restricted to letters, digits, dot, underscore and hyphen, so no value can
 *   escape its quotes.
 *
 * Threading:
 *   Requests are served from a fixed thread pool, so several browsers can poll
 *   at once. Every mutation of engine state goes through the controller's
 *   synchronized processOrder, and /order additionally brackets submit-plus-read
 *   in a single synchronized block so the trades reported back belong to that
 *   request and cannot be cleared by a concurrent order arriving in between.
 */
public class WebServer {

    public static final int DEFAULT_PORT = 8080;
    private static final int BOOK_DEPTH = 10;
    private static final int DEFAULT_TRADE_COUNT = 50;
    private static final int MAX_SIMULATE_ORDERS = 1_000_000;
    private static final int THREAD_POOL_SIZE = 8;

    private final MatchingController controller;
    private final OrderGateway gateway;
    private final PriceFeeder feeder;
    private final EventLog eventLog;
    private final TradeStore tradeStore;
    private final Seeder seeder;
    private final Path webRoot;
    private final int port;
    private final Object simulationLock = new Object();
    private long rateSampleMs = System.currentTimeMillis();
    private long rateSampleAccepted = 0;
    private double currentOrdersPerSecond = 0;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(int port) {
        this(port, true);
    }

    public WebServer(int port, boolean enableSeeding) {
        this.port = port;
        this.eventLog = new EventLog();
        this.tradeStore = new TradeStore();
        this.controller = new MatchingController(eventLog, tradeStore);
        this.gateway = new OrderGateway(controller);
        this.feeder = new LiveFeeder();
        this.seeder = new Seeder(gateway, feeder, 42L);
        this.webRoot = resolveWebRoot();

        if (enableSeeding) {
            seeder.seedIfEmpty();
        }
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        server.setExecutor(executor);

        server.createContext("/", this::handleStatic);
        server.createContext("/api/order", this::handleOrder);
        server.createContext("/api/book", this::handleBook);
        server.createContext("/api/trades", this::handleTrades);
        server.createContext("/api/simulate", this::handleSimulate);
        server.createContext("/api/price", this::handlePrice);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/stats", this::handleStats);
        server.createContext("/api/events", this::handleEvents);
        server.createContext("/api/treap", this::handleTreap);
        server.createContext("/api/stats/", this::handleTickerStats);
        server.createContext("/api/candles", this::handleCandles);
        server.createContext("/api/depth", this::handleDepth);
        server.createContext("/api/traders", this::handleTraders);
        server.createContext("/api/trader", this::handleTrader);

        server.start();
        System.out.println("HFT matching engine listening on http://0.0.0.0:" + port);
        System.out.println("Serving static files from " + webRoot.toAbsolutePath());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        tradeStore.close();
    }

    public int getPort() {
        return port;
    }

    public MatchingController getController() {
        return controller;
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.equals("/") || rawPath.isEmpty()) {
            rawPath = "/index.html";
        }

        Path requested = webRoot.resolve(rawPath.substring(1)).normalize();
        if (!requested.startsWith(webRoot) || !Files.isRegularFile(requested)) {
            sendText(exchange, 404, "text/plain", "404 not found");
            return;
        }

        byte[] body = Files.readAllBytes(requested);
        exchange.getResponseHeaders().set("Content-Type", contentTypeOf(requested.toString()));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        applyCors(exchange);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleOrder(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }

        String body = readBody(exchange);
        Order order;
        try {
            order = gateway.parse(body.trim().startsWith("{") ? jsonToRaw(body) : body);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"accepted\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        boolean accepted;
        List<Trade> trades;
        synchronized (controller) {
            accepted = gateway.submit(order);
            trades = controller.getLastTrades();
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"accepted\":").append(accepted).append(",\"trades\":[");
        for (int i = 0; i < trades.size(); i++) {
            if (i > 0) json.append(',');
            json.append(trades.get(i).toJson());
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleBook(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String ticker = pathTail(exchange, "/api/book");
        if (ticker.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/book/{ticker}\"}");
            return;
        }

        OrderBook.Snapshot snapshot;
        synchronized (controller) {
            snapshot = controller.getOrCreateBook(ticker).snapshot(BOOK_DEPTH);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"ticker\":\"").append(escape(ticker)).append("\",\"bids\":");
        appendLevels(json, snapshot.getBids());
        json.append(",\"asks\":");
        appendLevels(json, snapshot.getAsks());
        json.append('}');
        sendJson(exchange, 200, json.toString());
    }

    private void handleTrades(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int count = clamp(intParam(query, "n", DEFAULT_TRADE_COUNT), 1, 500);

        List<Trade> trades = controller.getTrades(count);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < trades.size(); i++) {
            if (i > 0) json.append(',');
            json.append(trades.get(i).toJson());
        }
        json.append(']');
        sendJson(exchange, 200, json.toString());
    }

    private void handleSimulate(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int n = clamp(intParam(query, "n", 100000), 1, MAX_SIMULATE_ORDERS);
        String modeText = query.getOrDefault("mode", "random");
        OrderSimulator.Mode mode = "sequential".equalsIgnoreCase(modeText)
                ? OrderSimulator.Mode.SEQUENTIAL
                : OrderSimulator.Mode.RANDOM;
        String ticker = query.getOrDefault("ticker", OrderSimulator.DEFAULT_TICKER);

        OrderSimulator.Stats stats;
        synchronized (simulationLock) {
            OrderSimulator simulator = new OrderSimulator(
                    OrderSimulator.DEFAULT_SEED, n, feeder, mode, ticker);
            try {
                simulator.run(gateway);
            } catch (IllegalArgumentException e) {
                // An invalid ?ticker= makes the first generated order fail in the
                // gateway; without this the exception escaped and killed the request.
                sendJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
                return;
            }
            stats = simulator.getStats();
        }
        sendJson(exchange, 200, stats.toJson());
    }

    private void handlePrice(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String ticker = pathTail(exchange, "/api/price");
        if (ticker.isEmpty()) {
            ticker = OrderSimulator.DEFAULT_TICKER;
        }
        int price = feeder.nextPrice(ticker);
        String source = (feeder instanceof LiveFeeder) ? ((LiveFeeder) feeder).getStatus() : "random walk";
        sendJson(exchange, 200, "{\"ticker\":\"" + escape(ticker) + "\",\"price\":" + price
                + ",\"source\":\"" + escape(source) + "\"}");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        sendText(exchange, 200, "text/plain", "ok");
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        sendJson(exchange, 200, "{\"accepted\":" + controller.getAcceptedCount()
                + ",\"rejected\":" + controller.getRejectedCount()
                + ",\"trades\":" + controller.getTotalTrades() + "}");
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long since = -1;
        if (query.containsKey("since")) {
            try {
                since = Long.parseLong(query.get("since").trim());
            } catch (NumberFormatException e) {
                // Uncaught, this escaped the handler and closed the connection.
                sendJson(exchange, 400, "{\"error\":\"since must be a whole number\"}");
                return;
            }
        }
        String ticker = query.getOrDefault("ticker", "");
        int max = clamp(intParam(query, "max", 200), 1, 500);

        List<EngineEvent> events = eventLog.since(since, ticker, max);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) json.append(',');
            json.append(events.get(i).toJson());
        }
        json.append(']');
        sendJson(exchange, 200, json.toString());
    }

    private void handleTreap(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/treap/";
        if (!path.startsWith(prefix)) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/treap/{ticker}/{bids|asks}\"}");
            return;
        }
        String rest = path.substring(prefix.length());
        String[] parts = rest.split("/");
        if (parts.length < 2 || parts[0].isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/treap/{ticker}/{bids|asks}\"}");
            return;
        }
        String ticker = parts[0].toUpperCase();
        String side = parts[1].toUpperCase();
        if (!"BIDS".equals(side) && !"ASKS".equals(side)) {
            // Previously any typo silently returned the ask ladder.
            sendJson(exchange, 400, "{\"error\":\"side must be bids or asks\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int depth = clamp(intParam(query, "depth", 5), 1, 20);

        String json;
        synchronized (controller) {
            OrderBook book = controller.getOrCreateBook(ticker);
            Treap treap = "BIDS".equals(side) ? book.getBids() : book.getAsks();
            json = treap.toJson(depth);
        }
        sendJson(exchange, 200, json);
    }

    private void handleTickerStats(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/stats/";
        if (!path.startsWith(prefix)) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/stats/{ticker}\"}");
            return;
        }
        String ticker = path.substring(prefix.length()).toUpperCase();
        if (ticker.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/stats/{ticker}\"}");
            return;
        }

        PriceNode bestBid;
        PriceNode bestAsk;
        int bidHeight;
        int askHeight;
        synchronized (controller) {
            OrderBook book = controller.getOrCreateBook(ticker);
            bestBid = book.bestBid();
            bestAsk = book.bestAsk();
            bidHeight = book.getBids().height();
            askHeight = book.getAsks().height();
        }
        int spread = (bestBid != null && bestAsk != null) ? bestAsk.getPrice() - bestBid.getPrice() : 0;

        // Lifetime figures from the store. The old code summed the last 100
        // trades only, so "volume" fell whenever older trades scrolled out of
        // the window, and "lastPrice" ended up being the OLDEST match because
        // the tape is iterated newest first.
        TradeStore.TickerTotals totals = tradeStore.getTickerTotals(ticker);

        String json = "{\"ticker\":\"" + escape(ticker) + "\""
                + ",\"bestBid\":" + (bestBid != null ? bestBid.getPrice() : "null")
                + ",\"bestAsk\":" + (bestAsk != null ? bestAsk.getPrice() : "null")
                + ",\"spread\":" + spread
                + ",\"lastPrice\":" + totals.lastPrice
                + ",\"volume\":" + totals.volume
                + ",\"trades\":" + totals.trades
                + ",\"bidTreapHeight\":" + bidHeight
                + ",\"askTreapHeight\":" + askHeight
                + ",\"accepted\":" + controller.getAcceptedCount()
                + ",\"rejected\":" + controller.getRejectedCount()
                + ",\"ordersPerSec\":" + Math.round(getOrdersPerSecond())
                + "}";
        sendJson(exchange, 200, json);
    }

    /*
     * Accepted orders per second over the last sampling window. The endpoint
     * used to return a hard-coded 1000, which was not a measurement at all.
     */
    private synchronized double getOrdersPerSecond() {
        long now = System.currentTimeMillis();
        long elapsed = now - rateSampleMs;
        if (elapsed >= 1000) {
            long accepted = controller.getAcceptedCount();
            currentOrdersPerSecond = (accepted - rateSampleAccepted) * 1000.0 / elapsed;
            rateSampleAccepted = accepted;
            rateSampleMs = now;
        }
        return currentOrdersPerSecond;
    }

    private void handleCandles(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/candles/";
        if (!path.startsWith(prefix)) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/candles/{ticker}\"}");
            return;
        }
        String ticker = path.substring(prefix.length()).toUpperCase();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int bucket = clamp(intParam(query, "bucket", 5), 1, 3600);

        List<TradeStore.TradeRecord> trades = tradeStore.getTrades();
        Map<Long, Candle> candles = new HashMap<>();

        for (TradeStore.TradeRecord t : trades) {
            if (!t.ticker.equalsIgnoreCase(ticker)) continue;
            long bucketStart = (t.ts / (bucket * 1000L)) * (bucket * 1000L);
            Candle c = candles.get(bucketStart);
            if (c == null) {
                c = new Candle(bucketStart, t.price, t.price, t.price, t.price, t.qty);
                candles.put(bucketStart, c);
            } else {
                c.high = Math.max(c.high, t.price);
                c.low = Math.min(c.low, t.price);
                c.close = t.price;
                c.volume += t.qty;
            }
        }

        List<Long> sorted = new ArrayList<>(candles.keySet());
        sorted.sort(Long::compareTo);
        int maxCandles = 120;
        if (sorted.size() > maxCandles) {
            sorted = sorted.subList(sorted.size() - maxCandles, sorted.size());
        }

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) json.append(',');
            Candle c = candles.get(sorted.get(i));
            json.append(c.toJson());
        }
        json.append(']');
        sendJson(exchange, 200, json.toString());
    }

    private void handleDepth(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/depth/";
        if (!path.startsWith(prefix)) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/depth/{ticker}\"}");
            return;
        }
        String ticker = path.substring(prefix.length()).toUpperCase();

        OrderBook.Snapshot snapshot;
        synchronized (controller) {
            snapshot = controller.getOrCreateBook(ticker).snapshot(100);
        }

        long cumBid = 0, cumAsk = 0;
        StringBuilder bidsJson = new StringBuilder("[");
        for (int i = 0; i < snapshot.getBids().size(); i++) {
            if (i > 0) bidsJson.append(',');
            OrderBook.Level l = snapshot.getBids().get(i);
            cumBid += l.getQuantity();
            bidsJson.append("{\"price\":").append(l.getPrice())
                    .append(",\"cumQty\":").append(cumBid).append('}');
        }
        bidsJson.append(']');

        StringBuilder asksJson = new StringBuilder("[");
        for (int i = 0; i < snapshot.getAsks().size(); i++) {
            if (i > 0) asksJson.append(',');
            OrderBook.Level l = snapshot.getAsks().get(i);
            cumAsk += l.getQuantity();
            asksJson.append("{\"price\":").append(l.getPrice())
                    .append(",\"cumQty\":").append(cumAsk).append('}');
        }
        asksJson.append(']');

        sendJson(exchange, 200, "{\"bids\":" + bidsJson + ",\"asks\":" + asksJson + "}");
    }

    private void handleTraders(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        Map<String, TraderStats> all = tradeStore.getAllTraderStats();
        List<TraderStats> board = TraderStats.leaderboard(all, true);

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < board.size(); i++) {
            if (i > 0) json.append(',');
            json.append(board.get(i).toJson());
        }
        json.append(']');
        sendJson(exchange, 200, json.toString());
    }

    private void handleTrader(HttpExchange exchange) throws IOException {
        if (handledPreflight(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/trader/";
        if (!path.startsWith(prefix)) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/trader/{id}\"}");
            return;
        }
        String id = path.substring(prefix.length());
        if (id.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"usage /api/trader/{id}\"}");
            return;
        }

        TraderStats stats = tradeStore.getTraderStats(id);
        if (stats == null) {
            sendJson(exchange, 404, "{\"error\":\"trader not found\"}");
            return;
        }

        List<Trade> trades = controller.getTrades(50);
        List<Trade> myTrades = new ArrayList<>();
        for (Trade t : trades) {
            if (t.getBuyerId().equals(id) || t.getSellerId().equals(id)) {
                myTrades.add(t);
            }
        }

        StringBuilder tradesJson = new StringBuilder("[");
        for (int i = 0; i < myTrades.size(); i++) {
            if (i > 0) tradesJson.append(',');
            tradesJson.append(myTrades.get(i).toJson());
        }
        tradesJson.append(']');

        String json = stats.toJson().replace("}", ",\"recentTrades\":" + tradesJson + "}");
        sendJson(exchange, 200, json);
    }

    private static class Candle {
        long ts, open, high, low, close, volume;
        Candle(long ts, long open, long high, long low, long close, long volume) {
            this.ts = ts; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
        }
        String toJson() {
            return "{\"ts\":" + ts + ",\"open\":" + open + ",\"high\":" + high + ",\"low\":" + low + ",\"close\":" + close + ",\"volume\":" + volume + "}";
        }
    }

    private static void appendLevels(StringBuilder json, List<OrderBook.Level> levels) {
        json.append('[');
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) json.append(',');
            json.append(levels.get(i).toJson());
        }
        json.append(']');
    }

    private static String pathTail(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (path.length() <= prefix.length() + 1) {
            return "";
        }
        return path.substring(prefix.length() + 1).trim().toUpperCase();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String jsonToRaw(String json) {
        String trader = jsonField(json, "traderId");
        String ticker = jsonField(json, "ticker");
        String side = jsonField(json, "side");
        String price = jsonField(json, "price");
        String qty = jsonField(json, "qty");
        if (qty == null) qty = jsonField(json, "quantity");
        if (trader == null || ticker == null || side == null || price == null || qty == null) {
            throw new IllegalArgumentException("JSON body needs traderId, ticker, side, price, qty");
        }
        return trader + "," + ticker + "," + side + "," + price + "," + qty;
    }

    private static String jsonField(String json, String name) {
        String key = "\"" + name + "\"";
        int at = json.indexOf(key);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? null : json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(
                        java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static int intParam(Map<String, String> query, String name, int fallback) {
        String value = query.get(name);
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private boolean handledPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return false;
        applyCors(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static void applyCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, "application/json", json);
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        applyCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "text/plain";
    }

    private static Path resolveWebRoot() {
        Path direct = Paths.get("web").toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) return direct;
        Path parent = Paths.get("..", "web").toAbsolutePath().normalize();
        if (Files.isDirectory(parent)) return parent;
        return direct;
    }
}
