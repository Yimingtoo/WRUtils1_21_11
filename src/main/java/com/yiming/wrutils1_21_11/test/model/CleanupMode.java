package com.yiming.wrutils1_21_11.test.model;

/**
 * run 开始前如何处理可写区（机器本体无论如何都不动）。
 */
public enum CleanupMode {
    /** 跑前把可写区清成空气，保证重复跑一致；跑后不还原。（当前唯一已实现） */
    CLEAR_INPUT,
    /** 完全不碰方块，可重复性由玩家自己维护。（尚未实现） */
    NONE,
    /** 跑前快照可写区方块与方块实体 NBT，结束时原样写回。（尚未实现） */
    SNAPSHOT
}
