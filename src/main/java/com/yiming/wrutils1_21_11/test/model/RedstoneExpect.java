package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.Direction;

/**
 * 红石强度断言：读取的强度值须落在 {@code [min, max]} 内。
 * {@code mode} 决定读法；{@code side} 仅在 {@code EMITTED} 下使用（发射方向）。
 */
public record RedstoneExpect(Mode mode, Direction side, int min, int max) {
    public enum Mode {
        /** world.getReceivedRedstonePower(pos) */
        RECEIVED,
        /** world.getReceivedStrongRedstonePower(pos) */
        STRONG,
        /** world.getEmittedRedstonePower(pos, side) */
        EMITTED
    }
}
