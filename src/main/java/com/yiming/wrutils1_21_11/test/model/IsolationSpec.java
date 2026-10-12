package com.yiming.wrutils1_21_11.test.model;

import java.util.Map;

public record IsolationSpec(
        int radius,
        boolean clearArea,
        boolean clearEntities,
        boolean forceChunks,
        Map<String, Object> gameRules) {
}
