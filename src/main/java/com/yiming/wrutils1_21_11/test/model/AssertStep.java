package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

/**
 * 一条断言。
 *
 * <p>{@code withinTicks} 为 0 时只在 {@code tick} 那一刻采样（旧的精确语义）。
 * 大于 0 时，在 {@code tick .. tick + withinTicks}（含两端）每个 tick 采样一次，
 * **任一 tick 满足即通过**——专为侦测器这类 1 tick 脉冲设计。
 */
public record AssertStep(long tick, BlockPos pos, Expectation expect, long withinTicks) implements Step {
}
