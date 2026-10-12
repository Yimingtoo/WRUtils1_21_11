package com.yiming.wrutils1_21_11.test.assertion;

import net.minecraft.util.math.BlockPos;

public record Failure(long tick, int stepIndex, BlockPos pos, String expected, String actual, String why) {
}
