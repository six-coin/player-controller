package org.six_coin.playerController.client.waypoint;

/**
 * 一条边：连接两个路径点。
 *
 * <p>{@code bi = true} 是双向边（两个方向都能走），{@code bi = false} 是单向边（只能 from -&gt; to）。
 * 边的编号不存在对象里，而是它在外层 {@code edges} 表里的键（-1, -2, -3 ...）。
 */
public final class Edge {

    private int from;
    private int to;
    private boolean bi;

    /** 给 Gson 用。 */
    private Edge() {
    }

    Edge(int from, int to, boolean bi) {
        this.from = from;
        this.to = to;
        this.bi = bi;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    public boolean bi() {
        return bi;
    }

    /** 这条边有没有碰到这个路径点（不分方向）。 */
    public boolean touches(int id) {
        return from == id || to == id;
    }

    /** 能不能从 a 走到 b。 */
    public boolean canTraverse(int a, int b) {
        if (from == a && to == b) return true;
        return bi && from == b && to == a;
    }

    /** 双端点正好是这两个点（不分方向、也不管是不是单向）。 */
    public boolean between(int a, int b) {
        return (from == a && to == b) || (from == b && to == a);
    }

    /** 从 id 这一端出发时，另一端的 id。 */
    public int other(int id) {
        return from == id ? to : from;
    }

    void remap(int newFrom, int newTo) {
        this.from = newFrom;
        this.to = newTo;
    }

    @Override
    public String toString() {
        return from + (bi ? " <-> " : " -> ") + to;
    }
}
