package com.yiming.wrutils1_21_11.test.exec;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class BlockInteractor {
    private BlockInteractor() {
    }

    public static ActionResult use(PlayerEntity player, ServerWorld world, BlockPos pos, Direction side, Hand hand) {
        BlockState state = world.getBlockState(pos);
        player.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 0.0F, 0.0F);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);

        ItemStack stack = player.getStackInHand(hand);
        ActionResult result = state.onUseWithItem(stack, world, player, hand, hit);
        if (result.isAccepted()) {
            return result;
        }
        if (result instanceof ActionResult.PassToDefaultBlockAction) {
            if (state.onUse(world, player, hit).isAccepted()) {
                return result;
            }
        }
        stack.useOnBlock(new ItemUsageContext(player, hand, hit));
        return result;
    }
}
