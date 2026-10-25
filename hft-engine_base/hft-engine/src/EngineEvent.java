/*
 * EngineEvent.java
 * Owner: Gathin
 *
 * A record of a structural change inside the engine, emitted for the UI
 * timeline and for audit.
 */
public class EngineEvent {

    private static long NEXT_ID = 0;

    public final long id;
    public final long ts;
    public final String ticker;
    public final String side;
    public final String type;
    public final int price;
    public final String traderId;
    public final String detail;

    public EngineEvent(String ticker, String side, String type, int price, String traderId, String detail) {
        this.id = NEXT_ID++;
        this.ts = System.currentTimeMillis();
        this.ticker = ticker;
        this.side = side;
        this.type = type;
        this.price = price;
        this.traderId = traderId;
        this.detail = detail;
    }

    public String toJson() {
        return "{\"id\":" + id
                + ",\"ts\":" + ts
                + ",\"ticker\":\"" + ticker + "\""
                + ",\"side\":\"" + side + "\""
                + ",\"type\":\"" + type + "\""
                + ",\"price\":" + price
                + ",\"traderId\":\"" + traderId + "\""
                + ",\"detail\":\"" + escape(detail) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
}