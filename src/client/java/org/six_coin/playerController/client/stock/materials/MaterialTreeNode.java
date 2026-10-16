package org.six_coin.playerController.client.stock.materials;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 材料清单里的一棵树的一个节点。
 *
 * <p>{@code count} 记的是**父级要多少**（需求），不是「实际会做出多少」：
 * 合成要凑整次，多出来的部分由「一次出几个」体现，所以下一层的数量是
 * {@code ceil(需求 ÷ 单次产量) × 这一格用几个}（见 {@link MaterialTreeBuilder}）。
 *
 * @param recipe 定下来的配方；null 表示这一步到此为止（没配方，或者被 {@code terminal_items} 拦住）
 */
public record MaterialTreeNode(Item item, int count, @Nullable MaterialsRecipes.Recipe recipe,
                               List<MaterialTreeNode> subMaterials) {

    /** {@code base} 或者工作站类型（{@code crafting_table} / {@code stonecutter}）。 */
    public String type() {
        return recipe == null ? "base" : recipe.kind().jsonType();
    }

    public boolean isBase() {
        return recipe == null;
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("item", MaterialsConfig.itemId(item));
        object.addProperty("count", count);
        object.addProperty("type", type());

        JsonArray subs = new JsonArray();
        for (MaterialTreeNode sub : subMaterials) subs.add(sub.toJson());
        object.add("sub_materials", subs);
        return object;
    }

    /** 这棵树一共几个节点（含自己）。 */
    public int size() {
        int size = 1;
        for (MaterialTreeNode sub : subMaterials) size += sub.size();
        return size;
    }

    /** 这棵树最深几层（含自己）。 */
    public int depth() {
        int deepest = 0;
        for (MaterialTreeNode sub : subMaterials) deepest = Math.max(deepest, sub.depth());
        return deepest + 1;
    }
}
