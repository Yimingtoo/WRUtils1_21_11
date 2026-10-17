package com.yiming.wrutils1_21_11.test.runner;

/**
 * 正在跑的测试的进度快照，供客户端的进度 HUD 显示。
 *
 * <p>之所以做成不可变快照、而不是让 HUD 直接去读 {@link TestRun} 的各个字段：
 * {@code TestRun} 的进度字段由**服务端线程**改写、HUD 在**渲染线程**读，
 * 逐字段读既没有 happen-before 边、也拿不到一致的一组数（可能读到跨 tick 的混合值）。
 * 服务端每 tick 构造一次整份快照，由 {@code TestStatusServer} 经 {@code TestStatusUpdate}
 * 广播；客户端读 {@code ClientTestState} 里的副本，单人/多人同一条码路。
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
