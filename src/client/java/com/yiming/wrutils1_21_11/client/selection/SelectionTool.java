package com.yiming.wrutils1_21_11.client.selection;

import com.yiming.wrutils1_21_11.command.SelectionManager;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 燧石选区工具：手持燧石左键 = pos1，右键 = pos2，写进与 {@code /wrutils sel pos1|pos2}
 * 完全同一份 {@link SelectionManager} 状态（不引入新语义）。
 *
 * <p>另有一个<b>总开关</b>（默认开，键位切换，见 {@link #toggle()}），HUD 由
 * {@link SelectionHud} 显示它的状态；开关关掉时燧石就是普通燧石，一切照原版。
 *
 * <p><b>为什么是燧石</b>：它原版既没有左键行为也没有右键用途，当"魔杖"代价最小——
 * 手持时只剩"左键挖不动方块"这一条（裸手速度，本就不实用）。
 * 2026-10-15 之前用的是木斧，因木斧左键能挖、右键能给原木去皮，干扰明显而换掉。
 *
 * <p>这两个回调在客户端与服务端<b>都会</b>触发（fabric-events-interaction-v0 里
 * {@code MultiPlayerGameModeMixin} 与 {@code ServerPlayerGameModeMixin} 各挂一次），
 * 而监听器是进程级的——单人下客户端与服务端同 JVM，不收窄就会被执行两遍。
 * 故用 {@code world.isClient()} 限死在客户端。
 *
 * <p>返回 {@link ActionResult#FAIL} 而不是 SUCCESS：FAIL 才"取消且<b>不</b>发包给服务端"，
 * 也就不会在服务端再把这次交互放行一遍（右键给原木去皮就是这么漏出去的）。
 *
 * <p>直调 {@link SelectionManager} 依赖"单人集成服务器下客户端与服务端同 JVM"这一既有假设
 * （与客户端直读 {@link WrtTestEngine#isRunning()} 同源）。要支持多人时，只需把
 * {@link #setPos} 换成发 payload 或执行 {@code /wrutils sel ...}，其余一行不动。
 */
public final class SelectionTool {
    /** 选区功能总开关，由键位切换；HUD 显示它的状态。默认开——不开的话验过的行为就变了。 */
    private static boolean enabled = true;

    private SelectionTool() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 开关键位调用，返回切换后的状态，便于调用方给一次即时反馈。 */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!active(world, player, hand)) {
                return ActionResult.PASS;
            }
            setPos(player, true, pos);
            return ActionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!active(world, player, hand)) {
                return ActionResult.PASS;
            }
            setPos(player, false, hitResult.getBlockPos());
            return ActionResult.FAIL;
        });
    }

    /** 只有"客户端侧 + 开关已开 + 手持燧石 + 没在跑测试"时才接管，其余一律放行给原版。 */
    private static boolean active(World world, PlayerEntity player, Hand hand) {
        return world.isClient()
                && enabled
                && player.getStackInHand(hand).isOf(Items.FLINT)
                && !WrtTestEngine.get().isRunning();
    }

    private static void setPos(PlayerEntity player, boolean first, BlockPos pos) {
        if (first) {
            SelectionManager.get().setPos1(player.getUuid(), pos);
        } else {
            SelectionManager.get().setPos2(player.getUuid(), pos);
        }
        // 选点会连点很多次，用动作栏而非聊天，免得刷屏
        player.sendMessage(Text.literal("[WRT] " + (first ? "pos1" : "pos2")
                + " = (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), true);
    }
}
