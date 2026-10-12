package com.yiming.wrutils1_21_11.test.runner;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.test.assertion.AssertionEngine;
import com.yiming.wrutils1_21_11.test.assertion.Failure;
import com.yiming.wrutils1_21_11.test.exec.BlockInteractor;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class TestRun {
    private final ServerWorld world;
    private final TestDefinition definition;
    private final ServerCommandSource feedback;
    private final PlayerEntity player;
    private final TestScheduler scheduler;
    private final WorldPrep prep;
    private final List<Failure> failures = new ArrayList<>();

    private RunState state = RunState.RUNNING;
    private long relativeTick = -1;
    private int asserts;
    private int executed;

    public TestRun(ServerWorld world, TestDefinition definition, ServerCommandSource feedback) {
        this.world = world;
        this.definition = definition;
        this.feedback = feedback;
        this.player = FakePlayer.get(world, new GameProfile(FakePlayer.DEFAULT_UUID, "[WRT] tester"));
        this.scheduler = new TestScheduler(flatten(definition));
        this.prep = definition.isolate() == null
                ? null
                : new WorldPrep(world, definition.origin(), definition.isolate());
        if (prep != null) {
            prep.prepare();
        }
    }

    private static List<Step> flatten(TestDefinition definition) {
        List<Step> steps = new ArrayList<>(definition.setup().size() + definition.steps().size());
        for (SetupBlock block : definition.setup()) {
            steps.add(new PlaceStep(0L, block.pos(), block.block()));
        }
        steps.addAll(definition.steps());
        return steps;
    }

    public ServerWorld world() {
        return world;
    }

    public RunState state() {
        return state;
    }

    public void tick() {
        if (state != RunState.RUNNING) {
            return;
        }
        if (!world.getTickManager().shouldTick()) {
            return;
        }
        relativeTick++;
        try {
            for (TestScheduler.Scheduled scheduled : scheduler.due(relativeTick)) {
                execute(scheduled, relativeTick);
            }
        } catch (RuntimeException e) {
            WrtLog.LOGGER.error("[WRT] {} crashed at tick {}", definition.id(), relativeTick, e);
            finish(RunState.ABORTED);
            return;
        }
        if (relativeTick > definition.timeoutTicks()) {
            WrtLog.LOGGER.warn("[WRT] {} aborted: timeout after {} ticks", definition.id(), definition.timeoutTicks());
            finish(RunState.ABORTED);
        } else if (scheduler.isFinished()) {
            finish(RunState.DONE);
        }
    }

    private void execute(TestScheduler.Scheduled scheduled, long relativeTick) {
        Step step = scheduled.step();
        executed++;
        if (step instanceof PlaceStep place) {
            placeBlock(place);
        } else if (step instanceof UseStep use) {
            BlockInteractor.use(player, world, use.pos(), use.side(), use.hand());
        } else if (step instanceof AssertStep assertStep) {
            asserts++;
            failures.addAll(AssertionEngine.check(assertStep.expect(), world, assertStep.pos(), relativeTick, scheduled.index()));
        }
    }

    private void placeBlock(PlaceStep place) {
        RegistryWrapper<Block> blocks = world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
        try {
            BlockState state = BlockArgumentParser.block(blocks, place.block(), false).blockState();
            world.setBlockState(place.pos(), state, Block.NOTIFY_ALL);
        } catch (CommandSyntaxException e) {
            failures.add(new Failure(0L, -1, place.pos(), place.block(), "<unparsed>", "bad block string: " + e.getMessage()));
        }
    }

    public void forceRestore() {
        if (prep != null) {
            prep.restore();
        }
    }

    private void finish(RunState finalState) {
        this.state = finalState;
        forceRestore();
        report();
    }

    private void report() {
        String header = state == RunState.ABORTED ? "ABORTED" : failures.isEmpty() ? "PASSED" : "FAILED";
        String summary = "[WRT] " + definition.id() + " " + header
                + " -- " + executed + " steps, " + asserts + " asserts, " + failures.size() + " failed";
        WrtLog.LOGGER.info(summary);
        if (feedback == null) {
            return;
        }
        feedback.sendFeedback(() -> Text.literal(summary), false);
        for (Failure failure : failures) {
            String line = "[FAIL] step#" + failure.stepIndex() + " tick=" + failure.tick()
                    + "\n  at  " + failure.pos().getX() + " " + failure.pos().getY() + " " + failure.pos().getZ()
                    + "\n  exp " + failure.expected()
                    + "\n  act " + failure.actual()
                    + "\n  why " + failure.why();
            WrtLog.LOGGER.warn(line);
            feedback.sendFeedback(() -> Text.literal(line), false);
        }
    }
}
