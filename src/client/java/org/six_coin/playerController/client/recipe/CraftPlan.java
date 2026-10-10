package org.six_coin.playerController.client.recipe;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.util.List;

/**
 * 一次合成 / 切石的完整计划：放什么、放哪、分几轮、每轮合几次。
 *
 * <p>工作台：{@code cells} 是最多 9 个格子（{@code gridSlot} 是 3×3 里的序号，0 是左上角）；
 * 切石机：只有一个输入格（{@code gridSlot} = 0）。
 *
 * <p>原版一次合成每格消耗 1 个，所以「每格放 N 个」就能连着合 N 次。
 * 要合的次数超过一格能放的上限时（比如要合 100 次、每格最多叠 64），就分几轮摆：
 * 每轮摆 {@code roundCrafts} 个/格、合完再摆下一轮（{@code rounds} 轮）。
 */
public final class CraftPlan {

    /** 在哪个方块上做。 */
    public enum Station {
        CRAFTING_TABLE,
        STONECUTTER
    }

    /** 一个格子要放的东西（数量由「这一轮合几次」决定，所以这里不记数量）。 */
    public record Cell(int gridSlot, Item item) {
    }

    private final Station station;
    private final Item target;
    private final int count;
    private final int perCraft;
    private final int crafts;
    private final int roundCrafts;
    private final List<Cell> cells;

    public CraftPlan(Station station, Item target, int count, int perCraft, int crafts,
                     int roundCrafts, List<Cell> cells) {
        this.station = station;
        this.target = target;
        this.count = count;
        this.perCraft = perCraft;
        this.crafts = crafts;
        this.roundCrafts = Math.max(1, roundCrafts);
        this.cells = List.copyOf(cells);
    }

    public Station station() {
        return station;
    }

    public Item target() {
        return target;
    }

    /** 一共要做出多少个。 */
    public int count() {
        return count;
    }

    /** 一共要合几次。 */
    public int crafts() {
        return crafts;
    }

    /** 单次合成的产量。 */
    public int perCraft() {
        return perCraft;
    }

    /** 一轮最多合几次（受「一格能叠多少」限制）。 */
    public int roundCrafts() {
        return roundCrafts;
    }

    /** 要摆几轮材料。 */
    public int rounds() {
        return (crafts + roundCrafts - 1) / roundCrafts;
    }

    /** 第 round 轮（从 0 开始）每格要放几个。 */
    public int amountForRound(int round) {
        return Math.max(0, Math.min(roundCrafts, crafts - round * roundCrafts));
    }

    public List<Cell> cells() {
        return cells;
    }

    /** 一句话描述，给聊天栏 / 日志用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(station == Station.CRAFTING_TABLE ? "工作台" : "切石机").append("：");
        sb.append(id(target)).append(" x").append(count);
        sb.append("（每次 ").append(perCraft).append(" 个，合 ").append(crafts).append(" 次");
        if (rounds() > 1) {
            sb.append("，分 ").append(rounds()).append(" 轮，每轮最多 ").append(roundCrafts).append(" 次");
        }
        sb.append("；材料 ");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(id(cells.get(i).item()));
        }
        sb.append("）");
        return sb.toString();
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
