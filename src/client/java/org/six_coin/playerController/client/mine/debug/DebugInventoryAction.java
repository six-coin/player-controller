package org.six_coin.playerController.client.mine.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.List;

/**
 * 一条一条把 {@link DebugInventory} 生成的那串管理员指令发出去（每个 tick 一条）。
 *
 * <p>为什么不一口气全发：{@code /item replace} 是**改服务端**的，一堆指令挤在同一个 tick 里
 * 既容易被服务端当成刷屏，出问题也不好定位。一条一条来，最后再看一眼结果。
 *
 * <p>{@code verify} = true 的时候，全部发完会等一会儿让物品栏同步回来，然后跑一遍
 * {@code debug_chunk_miner} 的物品栏检查，把还不对的地方打到聊天栏（生成「完整物品栏」时用）。
 */
public class DebugInventoryAction extends Action {

    /** 全部发完之后等多久再检查（让服务端把物品栏同步回来）。 */
    private static final int VERIFY_WAIT_TICKS = 20;

    private final String label;
    private final List<String> commands;
    private final boolean verify;

    private int index;
    private int waitTicks;

    public DebugInventoryAction(String label, List<String> commands, boolean verify) {
        this.label = label;
        this.commands = List.copyOf(commands);
        this.verify = verify;
    }

    @Override
    public String name() {
        return "生成物品栏（" + label + "，" + commands.size() + " 条指令）";
    }

    @Override
    protected void onStart() {
        if (commands.isEmpty()) {
            fail("没有要发的指令");
            return;
        }
        ChatUtils.info("开始生成物品栏：" + label + "（" + commands.size() + " 条管理员指令，"
            + "每条一 tick）");
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            fail("玩家或连接不存在");
            return;
        }

        if (index < commands.size()) {
            String command = commands.get(index++);
            try {
                player.connection.sendCommand(command);
            } catch (Exception e) {
                fail("指令发不出去：" + command + "（" + e + "）");
                return;
            }
            return;
        }

        // 指令发完了：等一下让物品栏同步回来
        if (++waitTicks < VERIFY_WAIT_TICKS) return;
        if (verify) {
            List<String> problems = DebugInventoryCheck.problems(player);
            if (problems.isEmpty()) {
                ChatUtils.info("物品栏检查通过：完全符合 debug_chunk_miner 的要求");
            } else {
                ChatUtils.error("物品栏还有 " + problems.size() + " 处不符合 debug_chunk_miner：");
                for (String problem : problems) ChatUtils.error("  · " + problem);
            }
        } else if (!DebugInventoryCheck.applied(player)) {
            ChatUtils.error("指令好像没生效（快捷栏 1 / 背包 1 还是空的）—— 你有管理员权限吗？");
        }
        finish();
    }

    @Override
    protected void onEnd() {
        if (failureReason() == null) {
            ChatUtils.info("物品栏生成完毕：" + label);
        }
    }
}
