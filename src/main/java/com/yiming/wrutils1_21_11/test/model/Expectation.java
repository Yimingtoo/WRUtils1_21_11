package com.yiming.wrutils1_21_11.test.model;

/**
 * 一条断言的期望。三个可选条件可同时存在，各自独立检查、各自产生失败。
 * {@code strict} 只影响 {@code block}：false（默认）只比方块类型与作者写了的属性，true 全等。
 */
public record Expectation(String block, boolean strict, NbtMatch nbt, RedstoneExpect redstone) {
}
