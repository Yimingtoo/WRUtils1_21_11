package com.yiming.wrutils1_21_11.client.editor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiming.wrutils1_21_11.test.BlockStringParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;

/** 用与 {@code /setblock} 完全相同的解析器校验方块串，供 GUI 实时标红与保存前拦截。 */
public final class BlockStringValidator {
    private BlockStringValidator() {
    }

    /**
     * @return {@code null} 表示合法；否则是可直接显示的错误信息。
     *         世界尚未加载（拿不到注册表）时一律放行，交给服务端兜底。
     */
    public static String validate(String text) {
        RegistryWrapper<Block> blocks = blocks();
        if (blocks == null) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return "方块串不能为空";
        }
        try {
            // allowNbt=true：方块串可以带 {…}（示例里的箱子 / 漏斗就带）
            // 走统一入口：它会额外拒绝尾部残留（"minecraft:stone撒地方" 不能通过）
            BlockStringParser.parse(blocks, text, true);
            return null;
        } catch (CommandSyntaxException e) {
            return e.getMessage();
        }
    }

    /** 校验编号/坐标这类正整数串。 */
    public static String validateNonNegativeInteger(String text) {
        if (text == null || text.isBlank()) {
            return "不能为空";
        }
        try {
            if (Long.parseLong(text.trim()) < 0) {
                return "不能为负数";
            }
            return null;
        } catch (NumberFormatException e) {
            return "不是整数";
        }
    }

    /** 解析 "x y z"；解析失败返回 null。 */
    public static Integer[] parsePos(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Integer[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RegistryWrapper<Block> blocks() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return null;
        }
        return client.world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
    }
}
