package com.yiming.wrutils1_21_11.test.assertion;

import com.yiming.wrutils1_21_11.test.model.RedstoneExpect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Optional;

public final class RedstoneMatcher {
    private RedstoneMatcher() {
    }

    /** 按期望的 mode 读出实际红石强度。 */
    public static int read(RedstoneExpect spec, ServerWorld world, BlockPos pos) {
        return switch (spec.mode()) {
            case RECEIVED -> world.getReceivedRedstonePower(pos);
            case STRONG -> world.getReceivedStrongRedstonePower(pos);
            case EMITTED -> world.getEmittedRedstonePower(pos, spec.side());
        };
    }

    public static Optional<String> mismatch(RedstoneExpect spec, int actual) {
        if (actual < spec.min() || actual > spec.max()) {
            return Optional.of(spec.mode().name().toLowerCase(Locale.ROOT)
                    + ": expected " + describeRange(spec) + ", got " + actual);
        }
        return Optional.empty();
    }

    /** 人类可读的期望串，用于失败信息的 "exp" 行。 */
    public static String describeRange(RedstoneExpect spec) {
        String prefix = spec.mode().name().toLowerCase(Locale.ROOT);
        if (spec.mode() == RedstoneExpect.Mode.EMITTED) {
            prefix = prefix + "@" + spec.side().name();
        }
        if (spec.min() == spec.max()) {
            return prefix + "=" + spec.min();
        }
        return prefix + " in [" + spec.min() + ", " + spec.max() + "]";
    }
}
