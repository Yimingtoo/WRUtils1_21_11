package com.yiming.wrutils1_21_11.test.model;

import java.util.List;

/**
 * 方块实体 NBT 断言。{@code snbt} 是期望值的 SNBT 串；{@code exact=false} 时按子集匹配
 * （期望里写到的 key 必须在实际中存在并递归匹配），{@code exact=true} 时全等。
 * {@code ignoreKeys} 在两种模式下都会跳过（默认剔除 x/y/z/id 这类位置元数据）。
 */
public record NbtMatch(String snbt, boolean exact, List<String> ignoreKeys) {
}
