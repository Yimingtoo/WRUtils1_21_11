package com.yiming.wrutils1_21_11.test.runner;

import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.UseStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TestScheduler {
    public record Scheduled(int index, Step step) {
    }

    private final List<Scheduled> ordered;
    private int cursor;

    public TestScheduler(List<Step> steps) {
        List<Scheduled> entries = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            entries.add(new Scheduled(i, steps.get(i)));
        }
        entries.sort(Comparator.comparingLong((Scheduled s) -> s.step().tick())
                .thenComparingInt(s -> phaseOf(s.step())));
        this.ordered = List.copyOf(entries);
    }

    public List<Scheduled> due(long relativeTick) {
        List<Scheduled> result = new ArrayList<>();
        while (cursor < ordered.size() && ordered.get(cursor).step().tick() <= relativeTick) {
            result.add(ordered.get(cursor));
            cursor++;
        }
        return result;
    }

    public boolean isFinished() {
        return cursor >= ordered.size();
    }

    private static int phaseOf(Step step) {
        if (step instanceof PlaceStep) {
            return 0;
        }
        if (step instanceof UseStep) {
            return 1;
        }
        if (step instanceof AssertStep) {
            return 2;
        }
        return 3;
    }
}
