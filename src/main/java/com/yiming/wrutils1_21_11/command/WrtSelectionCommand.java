package com.yiming.wrutils1_21_11.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

public final class WrtSelectionCommand {
    private static final double REACH = 200.0D;

    private WrtSelectionCommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> node() {
        return CommandManager.literal("sel")
                .then(posNode("pos1", true))
                .then(posNode("pos2", false))
                .then(CommandManager.literal("clear").executes(WrtSelectionCommand::clear))
                .then(CommandManager.literal("info").executes(WrtSelectionCommand::info));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> posNode(String name, boolean first) {
        return CommandManager.literal(name)
                .executes(ctx -> setPos(ctx, first, null))
                .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> setPos(ctx, first, BlockPosArgumentType.getBlockPos(ctx, "pos"))));
    }

    private static int setPos(CommandContext<ServerCommandSource> context, boolean first, BlockPos explicit) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[WRT] 需要以玩家身份执行"));
            return 0;
        }
        BlockPos pos = explicit != null ? explicit : raycastBlock(player);
        if (pos == null) {
            source.sendError(Text.literal("[WRT] 视线没有命中方块（也可显式传坐标）"));
            return 0;
        }
        if (first) {
            SelectionManager.get().setPos1(player.getUuid(), pos);
        } else {
            SelectionManager.get().setPos2(player.getUuid(), pos);
        }
        source.sendFeedback(() -> Text.literal("[WRT] " + (first ? "pos1" : "pos2") + " = " + format(pos)), false);
        return 1;
    }

    private static int clear(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[WRT] 需要以玩家身份执行"));
            return 0;
        }
        SelectionManager.get().clear(player.getUuid());
        source.sendFeedback(() -> Text.literal("[WRT] 选区已清除"), false);
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[WRT] 需要以玩家身份执行"));
            return 0;
        }
        SelectionManager.Selection selection = SelectionManager.get().get(player.getUuid());
        if (!selection.isComplete()) {
            source.sendFeedback(() -> Text.literal("[WRT] 选区不完整: pos1=" + describe(selection.pos1())
                    + " pos2=" + describe(selection.pos2())), false);
            return 0;
        }
        BlockPos min = BlockPos.min(selection.pos1(), selection.pos2());
        BlockPos max = BlockPos.max(selection.pos1(), selection.pos2());
        String size = (max.getX() - min.getX() + 1) + "x" + (max.getY() - min.getY() + 1) + "x"
                + (max.getZ() - min.getZ() + 1);
        source.sendFeedback(() -> Text.literal("[WRT] pos1=" + format(selection.pos1())
                + " pos2=" + format(selection.pos2())
                + " min=" + format(min) + " max=" + format(max) + " 体积=" + size), false);
        return 1;
    }

    private static BlockPos raycastBlock(ServerPlayerEntity player) {
        HitResult hit = player.raycast(REACH, 0.0F, false);
        return hit instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null;
    }

    private static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String describe(BlockPos pos) {
        return pos == null ? "<未设置>" : format(pos);
    }
}
