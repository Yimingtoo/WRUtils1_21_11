package com.yiming.wrutils1_21_11.test.assertion;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.state.property.Property;

import java.util.Map;
import java.util.Optional;

public final class BlockStateMatcher {
    private BlockStateMatcher() {
    }

    public static Optional<String> mismatch(BlockArgumentParser.BlockResult expected, BlockState actual) {
        BlockState expectedState = expected.blockState();
        if (!actual.isOf(expectedState.getBlock())) {
            return Optional.of("block: expected " + BlockArgumentParser.stringifyBlockState(expectedState)
                    + ", got " + BlockArgumentParser.stringifyBlockState(actual));
        }
        Map<Property<?>, Comparable<?>> actualProperties = actual.getEntries();
        for (Map.Entry<Property<?>, Comparable<?>> entry : expected.properties().entrySet()) {
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
