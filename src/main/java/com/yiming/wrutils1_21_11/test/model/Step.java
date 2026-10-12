package com.yiming.wrutils1_21_11.test.model;

public sealed interface Step permits PlaceStep, UseStep, AssertStep {
    long tick();
}
