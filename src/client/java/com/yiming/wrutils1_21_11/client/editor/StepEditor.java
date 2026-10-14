package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 步骤是不可变 record，改动一律靠重建。这里把"重建"集中一处，避免界面代码里到处写 type switch。
 */
public final class StepEditor {
    public enum Type {
        PLACE("place"), USE("use"), ASSERT("assert");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Type next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private StepEditor() {
    }

    public static Type typeOf(Step step) {
        if (step instanceof PlaceStep) {
            return Type.PLACE;
        }
        if (step instanceof UseStep) {
            return Type.USE;
        }
        return Type.ASSERT;
    }

    public static BlockPos posOf(Step step) {
        if (step instanceof PlaceStep place) {
            return place.pos();
        }
        if (step instanceof UseStep use) {
            return use.pos();
        }
        return ((AssertStep) step).pos();
    }

    /** 换类型：保留 tick 与 pos，其余字段给合理默认（use 的 side/hand 用默认值）。 */
    public static Step convert(Step step, Type type) {
        if (typeOf(step) == type) {
            return step;
        }
        long tick = step.tick();
        BlockPos pos = posOf(step);
        return switch (type) {
            case PLACE -> new PlaceStep(tick, pos, "minecraft:stone");
            case USE -> new UseStep(tick, pos, Direction.UP, Hand.MAIN_HAND);
            case ASSERT -> new AssertStep(tick, pos, new Expectation("minecraft:stone", false, null, null));
        };
    }

    public static Step withTick(Step step, long tick) {
        if (step instanceof PlaceStep place) {
            return new PlaceStep(tick, place.pos(), place.block());
        }
        if (step instanceof UseStep use) {
            return new UseStep(tick, use.pos(), use.side(), use.hand());
        }
        AssertStep assertStep = (AssertStep) step;
        return new AssertStep(tick, assertStep.pos(), assertStep.expect());
    }

    public static Step withPos(Step step, BlockPos pos) {
        if (step instanceof PlaceStep place) {
            return new PlaceStep(place.tick(), pos, place.block());
        }
        if (step instanceof UseStep use) {
            return new UseStep(use.tick(), pos, use.side(), use.hand());
        }
        AssertStep assertStep = (AssertStep) step;
        return new AssertStep(assertStep.tick(), pos, assertStep.expect());
    }

    /**
     * 该步的"主字段"——就是那一串方块描述：
     * place 的 block、assert 的 {@code expect.block}；use 没有主字段，返回空串。
     */
    public static String mainField(Step step) {
        if (step instanceof PlaceStep place) {
            return place.block();
        }
        if (step instanceof AssertStep assertStep) {
            return assertStep.expect() == null || assertStep.expect().block() == null
                    ? "" : assertStep.expect().block();
        }
        return "";
    }

    /** 写回主字段。assert 会保留原有的 strict / nbt / redstone，只换 block。 */
    public static Step withMainField(Step step, String text) {
        if (step instanceof PlaceStep place) {
            return new PlaceStep(place.tick(), place.pos(), text);
        }
        if (step instanceof AssertStep assertStep) {
            Expectation old = assertStep.expect() == null
                    ? new Expectation(null, false, null, null) : assertStep.expect();
            return new AssertStep(assertStep.tick(), assertStep.pos(),
                    new Expectation(text.isBlank() ? null : text, old.strict(), old.nbt(), old.redstone()));
        }
        return step;
    }
}
