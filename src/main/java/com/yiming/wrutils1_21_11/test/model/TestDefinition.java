package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

import java.util.List;

public record TestDefinition(
        String id,
        String description,
        String dimension,
        BlockPos origin,
        long timeoutTicks,
        AreaSpec area,
        List<SetupBlock> setup,
        List<Step> steps,
        boolean record) {

    /** 供命令行的 --record/--no-record 覆盖本文件的 record 设置。 */
    public TestDefinition withRecord(boolean value) {
        return new TestDefinition(id, description, dimension, origin, timeoutTicks, area, setup, steps, value);
    }
}
