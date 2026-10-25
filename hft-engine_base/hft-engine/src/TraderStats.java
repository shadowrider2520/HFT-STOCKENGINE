import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * TraderStats.java
 * Owner: Gathin
 *
 * Per-trader positions computed from trades.
 */
public class TraderStats {

    private final String traderId;
    private int trades;
    private long boughtQty;
    private long soldQty;
    private long buyValue;
    private long sellValue;
    private long lastActive;

    public TraderStats(String traderId) {
        this.traderId = traderId;
    }

    public void addTrade(boolean isBuy, int price, int qty, long ts) {
        trades++;
        if (isBuy) {
            boughtQty += qty;
            buyValue += (long) price * qty;
        } else {
            soldQty += qty;
            sellValue += (long) price * qty;
        }
        lastActive = ts;
    }

    public String getTraderId() { return traderId; }
    public int getTrades() { return trades; }
    public long getBoughtQty() { return boughtQty; }
    public long getSoldQty() { return soldQty; }
    public long getNetPosition() { return boughtQty - soldQty; }
    public double getAvgBuy() { return boughtQty == 0 ? 0 : (double) buyValue / boughtQty; }
    public double getAvgSell() { return soldQty == 0 ? 0 : (double) sellValue / soldQty; }
    public long getCashFlow() { return sellValue - buyValue; }
    public long getLastActive() { return lastActive; }

    public String toJson() {
        return "{\"traderId\":\"" + traderId + "\""
                + ",\"trades\":" + trades
                + ",\"boughtQty\":" + boughtQty
                + ",\"soldQty\":" + soldQty
                + ",\"netPosition\":" + getNetPosition()
                + ",\"avgBuy\":" + String.format("%.2f", getAvgBuy())
                + ",\"avgSell\":" + String.format("%.2f", getAvgSell())
                + ",\"cashFlow\":" + getCashFlow()
                + ",\"lastActive\":" + lastActive + "}";
    }

    public static List<TraderStats> leaderboard(Map<String, TraderStats> all, boolean byCashFlow) {
        List<TraderStats> list = new ArrayList<>(all.values());
        list.sort((a, b) -> byCashFlow ? Long.compare(b.getCashFlow(), a.getCashFlow())
                : Long.compare(b.getNetPosition(), a.getNetPosition()));
        return list;
    }
}