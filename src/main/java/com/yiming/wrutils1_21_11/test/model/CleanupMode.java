package com.yiming.wrutils1_21_11.test.model;

/**
 * run 开始前如何处理可写区（机器本体无论如何都不动）。
 */
public enum CleanupMode {
    /** 跑前把可写区清成空气，保证重复跑一致；跑后不还原。 */
    CLEAR_INPUT,
    /** 跑前跑后都不碰方块，可重复性由玩家自己维护。 */
    NONE,
    /** 跑前快照可写区内的非空气方块与方块实体 NBT，跑完（含中止）原样写回。 */
    SNAPSHOT
}
