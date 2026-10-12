package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public record UseStep(long tick, BlockPos pos, Direction side, Hand hand) implements Step {
}
