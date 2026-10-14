package com.yiming.wrutils1_21_11.test.model;

import net.minecraft.util.math.BlockPos;

/** 闭区间矩形区域（各轴均含两端）。min/max 由 {@link #of} 归一化，保证 min ≤ max。 */
public record Region(BlockPos min, BlockPos max) {

    public static Region of(BlockPos a, BlockPos b) {
        return new Region(
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    public static Region single(BlockPos pos) {
        return new Region(pos, pos);
    }

    public Region union(Region other) {
        return new Region(
                new BlockPos(Math.min(min.getX(), other.min.getX()), Math.min(min.getY(), other.min.getY()),
                        Math.min(min.getZ(), other.min.getZ())),
                new BlockPos(Math.max(max.getX(), other.max.getX()), Math.max(max.getY(), other.max.getY()),
                        Math.max(max.getZ(), other.max.getZ())));
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
