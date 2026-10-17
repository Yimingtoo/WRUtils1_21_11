package com.yiming.wrutils1_21_11.test.runner;

/**
 * 正在跑的测试的进度快照，供客户端的进度 HUD 显示。
 *
 * <p>之所以做成不可变快照、而不是让 HUD 直接去读 {@link TestRun} 的各个字段：
 * {@code TestRun} 的进度字段由**服务端线程**改写、HUD 在**渲染线程**读，
 * 逐字段读既没有 happen-before 边、也拿不到一致的一组数（可能读到跨 tick 的混合值）。
 * 服务端每 tick 构造一次整份快照，客户端只读这一个 {@code volatile} 引用——
 * 与 {@code WrtTestEngine.currentRun} 的既有做法同一个套路（单人下客户端与服务端同 JVM）。
 */
public record TestProgress(
        String id,
        int executed,
        int totalSteps,
        int asserts,
        int failures,
        long tick,
        long timeoutTicks,
        int queued) {
}
