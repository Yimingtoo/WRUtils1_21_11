package com.yiming.wrutils1_21_11.test.assertion;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.state.property.Property;

import java.util.Map;
import java.util.Optional;

public final class BlockStateMatcher {
    private BlockStateMatcher() {
    }

    /**
     * {@code strict=false} 只比作者写了的属性（{@code parsed.properties()}）；
     * {@code strict=true} 比对期望的完整属性集（{@code parsed.blockState().getEntries()}）。
     */
    public static Optional<String> mismatch(BlockArgumentParser.BlockResult expected, BlockState actual, boolean strict) {
        BlockState expectedState = expected.blockState();
        if (!actual.isOf(expectedState.getBlock())) {
            return Optional.of("block: expected " + BlockArgumentParser.stringifyBlockState(expectedState)
                    + ", got " + BlockArgumentParser.stringifyBlockState(actual));
        }
        Map<Property<?>, Comparable<?>> actualProperties = actual.getEntries();
        Map<Property<?>, Comparable<?>> expectedProperties = strict
                ? expectedState.getEntries()
                : expected.properties();
        for (Map.Entry<Property<?>, Comparable<?>> entry : expectedProperties.entrySet()) {
            Property<?> property = entry.getKey();
            Comparable<?> expectedValue = entry.getValue();
            Comparable<?> actualValue = actualProperties.get(property);
            if (actualValue == null) {
                return Optional.of("property " + property.getName() + ": not present on actual block");
            }
            if (!expectedValue.equals(actualValue)) {
                return Optional.of("property " + property.getName()
                        + ": expected " + expectedValue + ", got " + actualValue);
            }
        }
        return Optional.empty();
    }
}
