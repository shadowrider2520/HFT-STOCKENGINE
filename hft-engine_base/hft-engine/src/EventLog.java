import java.util.ArrayList;
import java.util.List;

/*
 * EventLog.java
 * Owner: Gathin
 *
 * Ring buffer of the last 5,000 EngineEvents.
 */
public class EventLog {

    private static final int CAPACITY = 5000;

    private final EngineEvent[] buffer = new EngineEvent[CAPACITY];
    private int head = 0;
    private int count = 0;

    public synchronized void append(EngineEvent event) {
        buffer[head] = event;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    public synchronized List<EngineEvent> since(long id, String ticker, int max) {
        List<EngineEvent> out = new ArrayList<>();
        if (count == 0) return out;

        int start = (head - count + CAPACITY) % CAPACITY;
        for (int i = 0; i < count && out.size() < max; i++) {
            int idx = (start + i) % CAPACITY;
            EngineEvent e = buffer[idx];
            if (e != null && e.id > id && (ticker == null || ticker.isEmpty() || ticker.equalsIgnoreCase(e.ticker))) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized int size() {
        return count;
    }
} 
