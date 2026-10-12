package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

public record PlaceStep(long tick, BlockPos pos, String block) implements Step {
}
