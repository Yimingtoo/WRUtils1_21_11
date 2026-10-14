package com.yiming.wrutils1_21_11.test.assertion;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.NbtMatch;
import com.yiming.wrutils1_21_11.test.model.RedstoneExpect;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AssertionEngine {
    private AssertionEngine() {
    }

    public static List<Failure> check(Expectation expect, ServerWorld world, BlockPos pos, long tick, int stepIndex) {
        List<Failure> failures = new ArrayList<>();
        if (expect.block() != null) {
            checkBlock(expect, world, pos, tick, stepIndex, failures);
        }
        if (expect.nbt() != null) {
            checkNbt(expect.nbt(), world, pos, tick, stepIndex, failures);
        }
        if (expect.redstone() != null) {
            checkRedstone(expect.redstone(), world, pos, tick, stepIndex, failures);
        }
        return failures;
    }

    private static void checkBlock(Expectation expect, ServerWorld world, BlockPos pos, long tick, int stepIndex,
                                   List<Failure> failures) {
        BlockState actual = world.getBlockState(pos);
        RegistryWrapper<Block> blocks = world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
        BlockArgumentParser.BlockResult parsed;
        try {
            parsed = BlockArgumentParser.block(blocks, expect.block(), false);
        } catch (CommandSyntaxException e) {
            failures.add(new Failure("blockstate", tick, stepIndex, pos, expect.block(),
                    BlockArgumentParser.stringifyBlockState(actual), "bad expected block string: " + e.getMessage()));
            return;
        }
        BlockStateMatcher.mismatch(parsed, actual, expect.strict()).ifPresent(why -> failures.add(new Failure(
                "blockstate", tick, stepIndex, pos,
                // 显示作者写的那串，而不是补全默认属性后的状态：非严格模式只比写了的属性，
                // 补全后会把未写的属性（如 lever 的 face=wall）也显示出来，误导读者。
                expect.block(),
                BlockArgumentParser.stringifyBlockState(actual),
                why)));
    }

    private static void checkNbt(NbtMatch match, ServerWorld world, BlockPos pos, long tick, int stepIndex,
                                 List<Failure> failures) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            failures.add(new Failure("nbt", tick, stepIndex, pos, match.snbt(), "<no block entity>",
                    "no block entity at pos"));
            return;
        }
        NbtCompound expected;
        try {
            expected = StringNbtReader.readCompound(match.snbt());
        } catch (CommandSyntaxException e) {
            failures.add(new Failure("nbt", tick, stepIndex, pos, match.snbt(), "<unparsed>",
                    "bad expected SNBT: " + e.getMessage()));
            return;
        }
        // createNbt 而非 createComponentlessNbt：后者会丢 id
        NbtCompound actual = blockEntity.createNbt(world.getRegistryManager());
        Set<String> ignoreKeys = new HashSet<>(match.ignoreKeys());
        Optional<String> mismatch = NbtSubsetMatcher.mismatch(expected, actual, match.exact(), ignoreKeys);
        mismatch.ifPresent(why -> failures.add(new Failure("nbt", tick, stepIndex, pos,
                expected.toString(), actual.toString(), why)));
    }

    private static void checkRedstone(RedstoneExpect spec, ServerWorld world, BlockPos pos, long tick, int stepIndex,
                                      List<Failure> failures) {
        int actual = RedstoneMatcher.read(spec, world, pos);
        RedstoneMatcher.mismatch(spec, actual).ifPresent(why -> failures.add(new Failure("redstone", tick, stepIndex, pos,
                RedstoneMatcher.describeRange(spec), String.valueOf(actual), why)));
    }
}
