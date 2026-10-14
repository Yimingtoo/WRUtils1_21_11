package com.yiming.wrutils1_21_11.client;

import com.yiming.wrutils1_21_11.client.editor.EditorSession;
import com.yiming.wrutils1_21_11.client.editor.TestAreaScreen;
import com.yiming.wrutils1_21_11.client.editor.TestEditScreen;
import com.yiming.wrutils1_21_11.client.editor.TestEditorScreen;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public class Wrutils1_21_11Client implements ClientModInitializer {

    /** 是否在掉落物头顶显示实体数字 ID，由 I 键切换。 */
    public static boolean showItemEntityId = true;

    @Override
    public void onInitializeClient() {
        KeyBinding toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wrutils1_21_11.toggle_item_entity_id",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KeyBinding.Category.MISC));
        KeyBinding editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wrutils1_21_11.open_editor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KeyBinding.Category.MISC));
        KeyBinding pickKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wrutils1_21_11.pick_block",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyBinding.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                showItemEntityId = !showItemEntityId;
            }
            while (editorKey.wasPressed()) {
                openEditor(client);
            }
            while (pickKey.wasPressed()) {
                pickBlock(client);
            }
        });
    }

    /**
     * 拾取准星指向的方块，交给 {@link EditorSession} 兑现（写进选中条目 / 当作新原点整体平移）。
     * 只在"有待处理的拾取、且没开着任何界面"时生效，所以不会误触。
     */
    private static void pickBlock(MinecraftClient client) {
        EditorSession session = EditorSession.get();
        if (!session.isPicking() || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
            client.player.sendMessage(Text.literal("[WRT] 准星没有指向方块"), false);
            return;
        }
        EditorSession.PickKind kind = session.pickKind();
        String message = session.applyPick(hit.getBlockPos());
        if (message != null) {
            client.player.sendMessage(Text.literal("[WRT] " + message), false);
        }
        // 拾取可写区的角要回可写区屏，其余回编辑屏
        boolean backToArea = kind == EditorSession.PickKind.AREA_MIN || kind == EditorSession.PickKind.AREA_MAX;
        client.setScreen(backToArea ? new TestAreaScreen() : new TestEditScreen());
    }

    /**
     * 打开测试编辑器。单人集成服务器下客户端与服务端同 JVM，{@link WrtTestEngine#isRunning()}
     * 读到的就是服务端正在用的那个单例（故无需任何网络包）。
     */
    private static void openEditor(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        if (!client.isInSingleplayer() || client.getServer() == null) {
            client.player.sendMessage(Text.literal("[WRT] 测试编辑器仅支持单人存档"), false);
            return;
        }
        if (WrtTestEngine.get().isRunning()) {
            // 附一个可点击的「停止测试」：点击把命令**填入聊天框**，回车才执行。
            // 不用 RunCommand 直接执行——/wrutils 带权限要求，客户端解析会被判为
            // PERMISSIONS_REQUIRED，从而弹出原版"此命令需要权限"确认框（那个框我们改不了）。
            client.player.sendMessage(Text.literal("[WRT] 测试运行中，无法打开编辑器  ")
                    .append(Text.literal("[停止测试]").styled(style -> style
                            .withColor(Formatting.YELLOW)
                            .withClickEvent(new ClickEvent.SuggestCommand("/wrutils test stop"))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Text.literal("点击填入 /wrutils test stop，回车执行"))))), false);
            return;
        }
        // 打开列表即视为放弃未完成的拾取，免得之后按 P 误把坐标填进去
        EditorSession.get().cancelPick();
        client.setScreen(new TestEditorScreen());
    }
}
