package com.yiming.wrutils1_21_11.client.progress;

import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import com.yiming.wrutils1_21_11.test.runner.TestProgress;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 屏幕**左上角**显示正在跑的测试的进度；没在跑就整块不画。
 *
 * <p>放左上角而不是像 {@link com.yiming.wrutils1_21_11.client.selection.SelectionHud 选区 HUD}
 * 那样放左下角：那里是原版聊天区，而测试结果本来就打在聊天里，会撞上。
 *
 * <p>挂在原版聊天层之后（{@link VanillaHudElements#CHAT}）以继承"按 F1 隐藏 HUD"的渲染条件——
 * 与选区 HUD 同一个套路；用 {@code addFirst}/{@code addLast} 不继承该条件。
 */
public final class ProgressHud {
    private static final int MARGIN = 4;
    private static final int COLOR = 0xFF66CCFF;
    private static final int COLOR_FAIL = 0xFFFF5555;

    private ProgressHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("wrutils1_21_11", "test_progress"),
                ProgressHud::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        TestProgress progress = WrtTestEngine.get().progress();
        if (progress == null) {
            return;
        }
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        int lineHeight = textRenderer.fontHeight + 1;
        context.drawText(textRenderer, Text.literal("[WRT] 运行中 " + progress.id()),
                MARGIN, MARGIN, COLOR, true);

        StringBuilder detail = new StringBuilder()
                .append("步骤 ").append(progress.executed()).append('/').append(progress.totalSteps())
                .append("   断言 ").append(progress.asserts())
                .append("   失败 ").append(progress.failures())
                .append("   tick ").append(progress.tick()).append('/').append(progress.timeoutTicks());
        if (progress.queued() > 0) {
            detail.append("   队列剩 ").append(progress.queued());
        }
        // 有失败就整行标红：跑批时最需要一眼看到的就这个
        context.drawText(textRenderer, Text.literal(detail.toString()),
                MARGIN, MARGIN + lineHeight, progress.failures() > 0 ? COLOR_FAIL : COLOR, true);
    }
}
