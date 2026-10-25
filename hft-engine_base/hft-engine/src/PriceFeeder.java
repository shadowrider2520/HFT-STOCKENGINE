/*
 * PriceFeeder.java
 * Owner: Gathin
 *
 * Source of a reference price for a symbol.
 *
 * Why an interface:
 *   The simulator needs a price to centre its generated orders on, but it must
 *   not care where that number comes from. Behind this one method sit two very
 *   different implementations - a deterministic random walk and a live exchange
 *   WebSocket - and the engine, the tests and the benchmark all work unchanged
 *   against either. It also makes the fallback trivial: LiveFeeder simply holds
 *   a RandomWalkFeeder and delegates to it when the socket is down.
 *
 * Important scope note: this price only CENTRES simulated order flow. The
 * engine is itself the exchange; it never routes anything to a real market.
 */
public interface PriceFeeder {

    /*
     * Returns the current reference price for a symbol, in whole ticks.
     * Implementations must never throw and must never return a value below 1,
     * because a non-positive price would be rejected by the gateway and would
     * stall the simulator.
     * Complexity: implementation defined; both implementations here are O(1).
     */
    int nextPrice(String ticker);
}
