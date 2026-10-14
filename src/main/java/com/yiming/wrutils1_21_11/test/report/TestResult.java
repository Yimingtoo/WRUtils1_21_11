package com.yiming.wrutils1_21_11.test.report;

import com.yiming.wrutils1_21_11.test.assertion.Failure;
import com.yiming.wrutils1_21_11.test.runner.RunState;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** 一次 run 结束后的结果快照，供聊天/日志/报告文件同源消费。 */
public record TestResult(
        String id,
        String dimension,
        BlockPos origin,
        RunState state,
        int executed,
        int asserts,
        long ticks,
        List<Failure> failures,
        String recording) {

    /** ABORTED 优先于 FAILED/PASSED；没有失败就是 PASSED。 */
    public String outcome() {
        if (state == RunState.ABORTED) {
            return "ABORTED";
        }
        return failures.isEmpty() ? "PASSED" : "FAILED";
    }

    public boolean passed() {
        return state == RunState.DONE && failures.isEmpty();
    }
}
