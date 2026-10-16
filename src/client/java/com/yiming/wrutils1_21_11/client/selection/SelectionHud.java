package com.yiming.wrutils1_21_11.client.selection;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 左下角显示选区功能的开关状态：<b>手持燧石且开关开启时才画</b>，其余时候整块不画。
 * 也就是"看不到"同时意味着"没拿燧石"或"开关关着"——反正都用不了，没必要占地方。
 *
 * <p>挂在原版聊天层之后（{@link VanillaHudElements#CHAT}），这样能继承"按 F1 隐藏 HUD"的渲染条件；
 * 用 {@code addFirst}/{@code addLast} 不继承该条件，F1 藏不掉。
 */
public final class SelectionHud {
    private static final int MARGIN = 4;
    private static final int COLOR = 0xFF55FF55;

    private SelectionHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("wrutils1_21_11", "selection_status"),
                SelectionHud::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        // 两个条件与 SelectionTool.active() 里的前两条对齐：开关开着、且手里确实是燧石。
        // 手持别的物品（或空手）时什么都不画——那时燧石选区根本无从谈起。
        if (!SelectionTool.isEnabled() || !holdingFlint(client)) {
            return;
        }
        TextRenderer textRenderer = client.textRenderer;
        int lineHeight = textRenderer.fontHeight + 1;
        int y = context.getScaledWindowHeight() - MARGIN - 2 * lineHeight;
        context.drawText(textRenderer, Text.literal("[WRT] 选区工具：开启"), MARGIN, y, COLOR, true);
        context.drawText(textRenderer, Text.literal("左键 pos1 / 右键 pos2"), MARGIN, y + lineHeight, COLOR, true);
    }

    /** 主手或副手拿着燧石。 */
    private static boolean holdingFlint(MinecraftClient client) {
        return client.player != null
                && (client.player.getMainHandStack().isOf(Items.FLINT)
                || client.player.getOffHandStack().isOf(Items.FLINT));
    }
}
