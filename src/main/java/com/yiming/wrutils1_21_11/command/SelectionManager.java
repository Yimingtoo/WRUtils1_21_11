package com.yiming.wrutils1_21_11.command;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SelectionManager {
    public record Selection(BlockPos pos1, BlockPos pos2) {
        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }
    }

    private static final SelectionManager INSTANCE = new SelectionManager();

    private final Map<UUID, BlockPos> first = new HashMap<>();
    private final Map<UUID, BlockPos> second = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionManager get() {
        return INSTANCE;
    }

    public void setPos1(UUID player, BlockPos pos) {
        first.put(player, pos);
    }

    public void setPos2(UUID player, BlockPos pos) {
        second.put(player, pos);
    }

    public Selection get(UUID player) {
        return new Selection(first.get(player), second.get(player));
    }

    public void clear(UUID player) {
        first.remove(player);
        second.remove(player);
    }
}
