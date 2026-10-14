package com.yiming.wrutils1_21_11.replay;

/**
 * 一次录制的目标：以某个区块为中心、覆盖给定区块半径的静态区域。
 *
 * @param label        录制名（进命令参数，只能含 [A-Za-z0-9_-]）
 * @param dimension    维度 id（如 minecraft:overworld）
 * @param centerChunkX 中心区块 x
 * @param centerChunkZ 中心区块 z
 * @param radiusChunks 区块半径（**已含外扩**）
 */
public record RecordingTarget(String label, String dimension, int centerChunkX, int centerChunkZ, int radiusChunks) {
}
