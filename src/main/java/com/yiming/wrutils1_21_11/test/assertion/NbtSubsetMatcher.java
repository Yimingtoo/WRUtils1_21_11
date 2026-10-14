package com.yiming.wrutils1_21_11.test.assertion;

import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 期望 NBT 与实际 NBT 的比较，语义对齐原版 {@code /execute if data}（{@code NbtHelper.matches}）。
 *
 * <p>子集模式（默认）：期望里的每个 key 必须在实际中存在并递归匹配；实际多出的 key 不报错。
 * 全等模式：额外要求实际不含期望之外的 key。
 *
 * <p>数值按 {@code doubleValue()} 比较，避免 {@code 1b} 与 {@code 1} 误报。
 * 列表要求**长度相等且逐元素匹配**——与 {@code /execute if data} 一致，不会因多出元素而假 PASS。
 *
 * <p>{@code ignoreKeys} **只作用于顶层**（同样对齐原版）：这样默认剔除方块实体的
 * {@code x/y/z/id/components} 元数据时，不会顺带把 {@code Items[...]} 里物品自身的
 * {@code id} 或 {@code components} 也忽略掉。
 */
public final class NbtSubsetMatcher {
    private NbtSubsetMatcher() {
    }

    public static Optional<String> mismatch(NbtCompound expected, NbtCompound actual, boolean exact, Set<String> ignoreKeys) {
        return compareCompound(expected, actual, exact, ignoreKeys, "");
    }

    private static Optional<String> compareCompound(NbtCompound expected, NbtCompound actual, boolean exact,
                                                    Set<String> ignoreKeys, String path) {
        for (Map.Entry<String, NbtElement> entry : expected.entrySet()) {
            if (ignoreKeys.contains(entry.getKey())) {
                continue;
            }
            String childPath = path + "/" + entry.getKey();
            NbtElement actualValue = actual.get(entry.getKey());
            if (actualValue == null) {
                return Optional.of(childPath + ": missing, expected " + entry.getValue());
            }
            Optional<String> mismatch = compare(entry.getValue(), actualValue, exact, childPath);
            if (mismatch.isPresent()) {
                return mismatch;
            }
        }
        if (exact) {
            for (String key : actual.getKeys()) {
                if (!ignoreKeys.contains(key) && !expected.contains(key)) {
                    return Optional.of(path + "/" + key + ": unexpected key (exact match)");
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> compare(NbtElement expected, NbtElement actual, boolean exact, String path) {
        if (expected instanceof NbtCompound expectedCompound) {
            if (!(actual instanceof NbtCompound actualCompound)) {
                return Optional.of(path + ": expected compound, got " + typeName(actual));
            }
            // 顶层以外的 compound 不再应用 ignoreKeys
            return compareCompound(expectedCompound, actualCompound, exact, Set.of(), path);
        }

        if (expected instanceof AbstractNbtNumber expectedNumber) {
            if (!(actual instanceof AbstractNbtNumber actualNumber)) {
                return Optional.of(path + ": expected number, got " + typeName(actual));
            }
            if (Double.compare(expectedNumber.doubleValue(), actualNumber.doubleValue()) != 0) {
                return Optional.of(path + ": expected " + expected + ", got " + actual);
            }
            return Optional.empty();
        }

        if (expected instanceof NbtList expectedList) {
            if (!(actual instanceof NbtList actualList)) {
                return Optional.of(path + ": expected list, got " + typeName(actual));
            }
            if (actualList.size() != expectedList.size()) {
                return Optional.of(path + ": list size expected " + expectedList.size() + ", got " + actualList.size());
            }
            for (int i = 0; i < expectedList.size(); i++) {
                Optional<String> mismatch = compare(expectedList.get(i), actualList.get(i), exact, path + "[" + i + "]");
                if (mismatch.isPresent()) {
                    return mismatch;
                }
            }
            return Optional.empty();
        }

        if (!expected.equals(actual)) {
            return Optional.of(path + ": expected " + expected + ", got " + actual);
        }
        return Optional.empty();
    }

    private static String typeName(NbtElement element) {
        return element.getClass().getSimpleName();
    }
}
