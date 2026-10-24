package org.six_coin.playerController.client.mine.complex.task;

import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.mine.complex.MineTaskRegion;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 【补货一趟】—— <b>这一轮只是占位</b>，流程下一轮再写。
 *
 * <p>为什么先留个接口：{@code d_start_mining} 每次挖完一个子区块都要问一句
 * 「要不要补货」，需要的时候要先回区块起始点、再回挖掘区起始点、补一趟、再回去接着挖。
 * 这条链路已经接好了，只差这个动作本身。
 *
 * <p>下一轮要在这里做的事（接口约定）：
 * <ul>
 *   <li>输入：任务名、{@link MineTaskRegion}、{@link MineTaskStationData}
 *       （里面的数量和空格数在每次拿 / 放之后都已经更新过）；</li>
 *   <li>把收集到的产物从物品栏的收集盒里倒进「卸货潜影盒摆放处」上的盒子，
 *       倒完记 {@link MineTaskStationData#recordPlacementBox}；</li>
 *   <li>把用空的收集盒换成新的（「补充潜影盒摆放处」上的盒子 / 空潜影盒回收处）；</li>
 *   <li>按 {@link MineTaskKit} 补齐工具 / 食物 / 图腾 / 圆石盒 / 黏液块盒；</li>
 *   <li>盔甲耐久低了去 mending 站立点修；</li>
 *   <li>结束时人站在安全点（调用方负责再走回挖掘区）。</li>
 * </ul>
 */
public class MineTaskResupplyAction extends Action {

    private final String task;
    private final MineTaskRegion region;
    private final MineTaskStationData stationData;

    public MineTaskResupplyAction(String task, MineTaskRegion region, MineTaskStationData stationData) {
        this.task = task;
        this.region = region;
        this.stationData = stationData;
    }

    @Override
    public String name() {
        return "补货一趟（占位）";
    }

    @Override
    protected void onStart() {
        ChatUtils.error("【补货】任务 " + task + " 需要补货，但补货流程还没实现（下一轮加）—— "
            + "先按现在的物品栏继续挖，工具 / 材料可能不够");
        ChatUtils.debug("补货占位：region=%s，station_data=%s", region.describe(),
            stationData == null ? "null" : stationData.describe());
        finish();
    }

    @Override
    protected void tick() {
        finish();
    }
}
