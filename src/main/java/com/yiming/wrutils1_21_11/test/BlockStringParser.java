package com.yiming.wrutils1_21_11.test;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.block.Block;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;

/**
 * 方块串解析的唯一入口。
 *
 * <p><b>为什么要包一层</b>：{@link BlockArgumentParser#block(RegistryWrapper, String, boolean)}
 * 的字符串重载只解析**前缀**，不检查尾部残留——{@code "minecraft:stone撒地方"} 会被当成
 * {@code minecraft:stone} 静默接受。编辑器校验、place 步骤、assert 的 block 条件都必须走这里，
 * 否则坏串能落盘，运行时还会悄悄按前缀生效。
 */
public final class BlockStringParser {
    private static final SimpleCommandExceptionType TRAILING =
            new SimpleCommandExceptionType(Text.literal("方块串尾部有多余字符"));

    private BlockStringParser() {
    }

    public static BlockArgumentParser.BlockResult parse(RegistryWrapper<Block> blocks, String text, boolean allowNbt)
            throws CommandSyntaxException {
        StringReader reader = new StringReader(text);
        BlockArgumentParser.BlockResult result = BlockArgumentParser.block(blocks, reader, allowNbt);
        // 尾部空白无害（编辑器里打空格很常见），真正要挡的是残留的实义字符
        reader.skipWhitespace();
        if (reader.canRead()) {
            throw TRAILING.createWithContext(reader);
        }
        return result;
    }
}
