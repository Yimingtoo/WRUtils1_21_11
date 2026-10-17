package com.yiming.wrutils1_21_11.client;

import com.yiming.wrutils1_21_11.client.editor.EditorSession;
import com.yiming.wrutils1_21_11.client.editor.TestAreaScreen;
import com.yiming.wrutils1_21_11.client.editor.TestEditScreen;
import com.yiming.wrutils1_21_11.client.editor.TestEditorScreen;
import com.yiming.wrutils1_21_11.client.net.ClientTestEditor;
import com.yiming.wrutils1_21_11.client.net.ClientTestState;
import com.yiming.wrutils1_21_11.client.progress.ProgressHud;
import com.yiming.wrutils1_21_11.client.selection.SelectionHud;
import com.yiming.wrutils1_21_11.client.selection.SelectionRenderer;
import com.yiming.wrutils1_21_11.client.selection.SelectionTool;
import com.yiming.wrutils1_21_11.command.SelectionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
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
        // 燧石选区：左键 pos1 / 右键 pos2，并把选区画成线框 + 左下角状态提示
        SelectionTool.register();
        SelectionRenderer.register();
        SelectionHud.register();
        // 测试进度：左上角显示正在跑的测试跑到哪了
        ProgressHud.register();
        // 编辑器与运行状态走网络（payload 类型已在 common 侧注册，这里只挂客户端这半边的接收器）
        ClientTestEditor.register();
        ClientTestState.register();

        // 客户端的这几个静态字段跨存档、跨服务器都活着，换连接时必须复位，否则会"串台"：
        //   1) ClientTestState 卡在"正在跑测试"（服务端广播是边沿触发，错过那条 idle 就永远等不到）
        //   2) 上一个存档的选区线框画在新存档里，而 /wrutils test new 会拿它去建一个坐标离谱的测试
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSession(handler));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession(handler));

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
        KeyBinding selectionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wrutils1_21_11.toggle_selection",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
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
            while (selectionKey.wasPressed()) {
                // 开关一按就有反馈（HUD 也会跟着出现/消失，但动作栏能给一次明确回应）
                boolean on = SelectionTool.toggle();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[WRT] 选区工具：" + (on ? "开启" : "关闭")), true);
                }
            }
        });
    }

    /**
     * 换连接时清掉客户端的会话状态（见 {@link #onInitializeClient()} 里的说明）。
     *
     * <p>选区**只清自己那份**（按 UUID），不清全局：单人开 LAN 时客户端与服务端共用同一个
     * {@link SelectionManager}，清全局会把同房间其他玩家的选区一并抹掉。
     */
    private static void resetSession(ClientPlayNetworkHandler handler) {
        ClientTestState.reset();
        SelectionManager.get().clear(handler.getProfile().id());
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
     * 打开测试编辑器。单人多人走同一条路：编辑器的文件操作与运行状态全部经 payload 与服务端交互，
     * 所以这里只判断"对面有没有装 WRT"，不再判断是不是单人存档。
     */
    private static void openEditor(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        if (!ClientTestEditor.isAvailable()) {
            client.player.sendMessage(Text.literal("[WRT] 该服务器没有安装 WRT，测试编辑器不可用"), false);
            return;
        }
        if (ClientTestState.isRunning()) {
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
