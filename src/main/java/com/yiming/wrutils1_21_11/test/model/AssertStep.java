package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

public record AssertStep(long tick, BlockPos pos, Expectation expect) implements Step {
}
