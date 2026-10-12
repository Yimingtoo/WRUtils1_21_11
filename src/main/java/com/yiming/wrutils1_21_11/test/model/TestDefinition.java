package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

import java.util.List;

public record TestDefinition(
        String id,
        String description,
        String dimension,
        BlockPos origin,
        long timeoutTicks,
        IsolationSpec isolate,
        List<SetupBlock> setup,
        List<Step> steps) {
}
