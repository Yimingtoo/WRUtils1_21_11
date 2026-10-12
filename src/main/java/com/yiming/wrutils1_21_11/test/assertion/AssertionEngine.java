package com.yiming.wrutils1_21_11.test.assertion;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AssertionEngine {
    private AssertionEngine() {
    }

    public static List<Failure> check(Expectation expect, ServerWorld world, BlockPos pos, long tick, int stepIndex) {
        List<Failure> failures = new ArrayList<>();
        BlockState actual = world.getBlockState(pos);
        if (expect.block() != null) {
            RegistryWrapper<Block> blocks = world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
            BlockArgumentParser.BlockResult parsed;
            try {
                parsed = BlockArgumentParser.block(blocks, expect.block(), false);
            } catch (CommandSyntaxException e) {
                failures.add(new Failure(tick, stepIndex, pos, expect.block(),
                        BlockArgumentParser.stringifyBlockState(actual), "bad expected block string: " + e.getMessage()));
                return failures;
            }
            Optional<String> mismatch = BlockStateMatcher.mismatch(parsed, actual);
            mismatch.ifPresent(why -> failures.add(new Failure(tick, stepIndex, pos,
                    BlockArgumentParser.stringifyBlockState(parsed.blockState()),
                    BlockArgumentParser.stringifyBlockState(actual),
                    why)));
        }
        return failures;
    }
}
