package org.six_coin.playerController.client.recipe;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.util.List;

/**
 * 一次合成 / 切石的完整计划：放什么、放哪、取几次成品。
 *
 * <p>工作台：{@code cells} 是最多 9 个格子（{@code gridSlot} 是 3×3 里的序号，0 是左上角），
 * 每格放 {@code crafts} 个（原版一次合成每格消耗 1 个，所以放 {@code crafts} 个就能连着取 {@code crafts} 次）。
 *
 * <p>切石机：只有一个输入格（{@code gridSlot} = 0 就是输入格），同样放 {@code crafts} 个。
 */
public final class CraftPlan {

    /** 在哪个方块上做。 */
    public enum Station {
        CRAFTING_TABLE,
        STONECUTTER
    }

    /** 一个格子要放的东西。 */
    public record Cell(int gridSlot, Item item, int amount) {
    }

    private final Station station;
    private final Item target;
    private final int count;
    private final int crafts;
    private final int perCraft;
    private final List<Cell> cells;

    public CraftPlan(Station station, Item target, int count, int crafts, int perCraft, List<Cell> cells) {
        this.station = station;
        this.target = target;
        this.count = count;
        this.crafts = crafts;
        this.perCraft = perCraft;
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

    /** 要点几次成品（每次合成一次）。 */
    public int crafts() {
        return crafts;
    }

    /** 单次合成的产量。 */
    public int perCraft() {
        return perCraft;
    }

    public List<Cell> cells() {
        return cells;
    }

    /** 一句话描述，给聊天栏 / 日志用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(station == Station.CRAFTING_TABLE ? "工作台" : "切石机").append("：");
        sb.append(id(target)).append(" x").append(count);
        sb.append("（每次 ").append(perCraft).append(" 个，合 ").append(crafts).append(" 次；材料 ");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append("、");
            Cell cell = cells.get(i);
            sb.append(id(cell.item())).append(" x").append(cell.amount());
        }
        sb.append("）");
        return sb.toString();
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
