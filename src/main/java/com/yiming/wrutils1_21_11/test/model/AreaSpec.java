package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * 测试区域与副作用控制（JSON 里的 {@code area} 段）。
 * 非破坏性语义：只有 {@code inputs} 里的区域可写，机器本体与断言位置一律只读。
 *
 * <p>{@code schemaVersion: 3} 起 {@code inputs} 是**多个**矩形，用于"两片互不相邻的输入区、
 * 且不希望中间那片也被放开"的场合；{@code place}/{@code use} 落在**任一**矩形内即可。
 *
 * @param inputs        可写区（多个矩形）；**空列表 = 不允许任何写入**
 * @param cleanup       run 开始前如何处理可写区
 * @param forceChunks   是否强制加载测试涉及的区块（默认 false）
 * @param clearEntities 是否清除测试范围内的掉落物/经验球（默认 false）
 * @param gameRules     测试期间临时设置的 gamerule（结束还原）
 */
public record AreaSpec(
        List<Region> inputs,
        CleanupMode cleanup,
        boolean forceChunks,
        boolean clearEntities,
        Map<String, Object> gameRules) {

    public AreaSpec {
        inputs = List.copyOf(inputs);
    }

    /** 坐标是否落在**任一**可写区内。 */
    public boolean allows(BlockPos pos) {
        for (Region region : inputs) {
            if (region.contains(pos)) {
                return true;
            }
        }
        return false;
    }
}
