package org.six_coin.playerController.client.waypoint;

/**
 * 一条有向边（目前只有末地传送门用）。
 *
 * <p>末地传送门只进不出，所以不能当成无向边处理。
 * 这类边很少，单独存一份。
 */
public final class DirectedEdge {

    private int from;
    private int to;

    /** 给 Gson 用。 */
    private DirectedEdge() {
    }

    DirectedEdge(int from, int to) {
        this.from = from;
        this.to = to;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    @Override
    public String toString() {
        return from + " -> " + to;
    }
}
