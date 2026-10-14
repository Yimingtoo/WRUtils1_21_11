package com.yiming.wrutils1_21_11.test.model;

import java.util.Map;

/**
 * 测试区域与副作用控制（JSON 里的 {@code area} 段）。
 * 非破坏性语义：只有 {@code input} 区域可写，机器本体与断言位置一律只读。
 *
 * @param input         可写区（place/use 只允许落在这里）；为 null 表示不允许任何写入
 * @param cleanup       run 开始前如何处理可写区
 * @param forceChunks   是否强制加载测试涉及的区块（默认 false）
 * @param clearEntities 是否清除测试范围内的掉落物/经验球（默认 false）
 * @param gameRules     测试期间临时设置的 gamerule（结束还原）
 */
public record AreaSpec(
        Region input,
        CleanupMode cleanup,
        boolean forceChunks,
        boolean clearEntities,
        Map<String, Object> gameRules) {
}
