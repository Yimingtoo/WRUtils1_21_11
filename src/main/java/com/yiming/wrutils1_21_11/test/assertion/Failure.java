package com.yiming.wrutils1_21_11.test.assertion;

import net.minecraft.util.math.BlockPos;

/**
 * 一条断言失败。{@code kind} 是失败类别（blockstate / nbt / redstone / place / startup），
 * 与 exp/act 一起构成"聊天 + 日志 + 报告文件"三处同源的展示内容。
 */
public record Failure(String kind, long tick, int stepIndex, BlockPos pos, String expected, String actual, String why) {

    /**
     * 多行人类可读描述，三处消费方共用，保证格式一致。
     * 非步骤类失败（如启动失败，stepIndex &lt; 0）不显示 step#；exp/act 都为空时省略这两行。
     */
    public String describe(String testId, String dimension) {
        StringBuilder sb = new StringBuilder("[FAIL] ").append(testId);
        if (stepIndex >= 0) {
            sb.append(" step#").append(stepIndex);
        }
        sb.append(" tick=").append(tick)
                .append("\n  at   ").append(dimension).append(' ')
                .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                .append("\n  kind ").append(kind);
        if (!expected.isEmpty() || !actual.isEmpty()) {
            sb.append("\n  exp  ").append(expected)
                    .append("\n  act  ").append(actual);
        }
        return sb.append("\n  why  ").append(why).toString();
    }
}
