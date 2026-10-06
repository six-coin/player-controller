package org.six_coin.playerController.client.waypoint;

/**
 * 一条边：连接两个路径点（存的是路径点 id，不是坐标）。
 *
 * <p>每条边只能在单一轴向上移动，也就是两个路径点必须有两个坐标相同。
 */
public final class Edge {

    private int a;
    private int b;

    /** 给 Gson 用。 */
    private Edge() {
    }

    Edge(int a, int b) {
        this.a = a;
        this.b = b;
    }

    public int a() {
        return a;
    }

    public int b() {
        return b;
    }

    public int other(int id) {
        return id == a ? b : a;
    }

    public boolean connects(int id1, int id2) {
        return (a == id1 && b == id2) || (a == id2 && b == id1);
    }

    @Override
    public String toString() {
        return a + " <-> " + b;
    }
}
