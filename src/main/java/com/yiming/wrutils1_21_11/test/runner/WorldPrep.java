package com.yiming.wrutils1_21_11.test.runner;

import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.CleanupMode;
import com.yiming.wrutils1_21_11.test.model.Region;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试开始前的一次性准备与结束时的还原。
 *
 * <p>**非破坏性**：方块层面只动 {@code spec.inputs}（可写区），机器本体与断言位置一律不碰。
 * 强制区块/清实体按"测试涉及范围"（{@code chunkRegion} = 可写区 ∪ 所有步骤坐标）计算。
 */
public final class WorldPrep {
    private final ServerWorld world;
    private final AreaSpec spec;
    private final Region chunkRegion;
    private final List<ChunkPos> forcedChunks = new ArrayList<>();
    private final Map<GameRule<?>, Object> savedRules = new LinkedHashMap<>();
    /** {@code snapshot} 模式：可写区内全部**非空气**方块；其余模式为 null。 */
    private Map<BlockPos, BlockState> snapshotBlocks;
    /** 同上，有方块实体的位置及其 NBT（与 snapshotBlocks 同时建立）。 */
    private Map<BlockPos, NbtCompound> snapshotBlockEntities;

    /**
     * 快照体积上限。快照本身只存非空气方块，但还原时要遍历整个区域；
     * 超大区域会让服务端内存和 tick 时间不可控——宁可明确报错，也不要 OOM。
     */
    private static final long MAX_SNAPSHOT_VOLUME = 1_000_000L;

    public WorldPrep(ServerWorld world, AreaSpec spec, Region chunkRegion) {
        this.world = world;
        this.spec = spec;
        this.chunkRegion = chunkRegion;
    }

    public void prepare() {
        try {
            // 先做体积检查：超限时不应先强制加载一大堆区块
            if (spec.cleanup() == CleanupMode.SNAPSHOT) {
                checkSnapshotVolume();
            }
            // 再强制加载：快照要读方块状态，未加载的区块读出来是空气，
            // 不先加载就会把整片区域记成"本来什么都没有"。
            if (spec.forceChunks()) {
                forceChunks();
            }
            applyCleanup();
            if (spec.clearEntities()) {
                clearEntities();
            }
            applyGameRules();
        } catch (RuntimeException e) {
            WrtLog.LOGGER.error("[WRT] world prep failed, rolling back", e);
            restore();
            throw e;
        }
    }

    /**
     * 还原本次 prepare / 测试造成的改动，并保持幂等。
     * {@code snapshot} 模式下先还原可写区方块（含方块实体 NBT），再还原 gamerule 与强制区块——
     * 先写方块再卸载区块，避免写到半加载的区块。
     */
    public void restore() {
        restoreBlocks();
        MinecraftServer server = world.getServer();
        for (Map.Entry<GameRule<?>, Object> entry : savedRules.entrySet()) {
            setRule(world.getGameRules(), entry.getKey(), entry.getValue(), server);
        }
        savedRules.clear();
        for (ChunkPos chunk : forcedChunks) {
            world.setChunkForced(chunk.x, chunk.z, false);
        }
        forcedChunks.clear();
    }

    private void applyCleanup() {
        switch (spec.cleanup()) {
            case CLEAR_INPUT -> clearInput();
            case NONE -> {
                // 完全不碰方块：可重复性由测试作者自己保证
            }
            case SNAPSHOT -> captureSnapshot();
        }
    }

    private void checkSnapshotVolume() {
        double volume = 0;
        for (Region input : spec.inputs()) {
            volume += (double) (input.max().getX() - input.min().getX() + 1)
                    * (input.max().getY() - input.min().getY() + 1)
                    * (input.max().getZ() - input.min().getZ() + 1);
        }
        if (volume > MAX_SNAPSHOT_VOLUME) {
            throw new IllegalArgumentException("cleanup=snapshot 的可写区过大（" + (long) volume
                    + " 格，上限 " + MAX_SNAPSHOT_VOLUME + "）：请缩小 area.input");
        }
    }

    /**
     * 记录可写区内全部非空气方块与方块实体 NBT。快照在 {@code forceChunks()} 之后、测试改动之前取。
     */
    private void captureSnapshot() {
        // 先写局部变量，全部成功后再挂到字段上：中途抛异常时不能被当成"完整快照"去还原，
        // 否则没拍到的位置会被当成空气清掉。
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, NbtCompound> blockEntities = new HashMap<>();
        RegistryWrapper.WrapperLookup registries = world.getRegistryManager();
        for (Region input : spec.inputs()) {
            for (BlockPos pos : BlockPos.iterate(input.min(), input.max())) {
                // 这里的 BlockPos 是复用的可变实例，存进 map 前必须固定下来
                BlockPos immutable = pos.toImmutable();
                BlockState state = world.getBlockState(immutable);
                if (!state.isAir()) {
                    blocks.put(immutable, state);
                }
                BlockEntity blockEntity = world.getBlockEntity(immutable);
                if (blockEntity != null) {
                    blockEntities.put(immutable, blockEntity.createNbtWithIdentifyingData(registries));
                }
            }
        }
        this.snapshotBlocks = blocks;
        this.snapshotBlockEntities = blockEntities;
    }

    /**
     * 把可写区写回快照：快照里有的恢复原方块，快照时为空气的重新清成空气。
     * 分两轮——先写方块状态（这会创建对应类型的方块实体），再写回方块实体 NBT；
     * 顺序反了会写到旧的 / 错误的方块实体上。
     */
    private void restoreBlocks() {
        if (snapshotBlocks == null) {
            return;
        }
        for (Region input : spec.inputs()) {
            for (BlockPos pos : BlockPos.iterate(input.min(), input.max())) {
                BlockPos immutable = pos.toImmutable();
                BlockState target = snapshotBlocks.getOrDefault(immutable, Blocks.AIR.getDefaultState());
                if (world.getBlockState(immutable) != target) {
                    // 与 clearInput 一致用 NOTIFY_ALL：保留红石/邻居更新语义，让世界回到自洽状态
                    world.setBlockState(immutable, target, Block.NOTIFY_ALL);
                }
            }
        }
        RegistryWrapper.WrapperLookup registries = world.getRegistryManager();
        for (Map.Entry<BlockPos, NbtCompound> entry : snapshotBlockEntities.entrySet()) {
            BlockEntity blockEntity = world.getBlockEntity(entry.getKey());
            if (blockEntity == null) {
                // 理论上不会发生：方块状态已经先恢复，对应类型的方块实体应当存在
                WrtLog.LOGGER.warn("[WRT] snapshot 还原时找不到方块实体: {}", entry.getKey());
                continue;
            }
            try (ErrorReporter.Logging logging = new ErrorReporter.Logging(WrtLog.LOGGER)) {
                blockEntity.read(NbtReadView.create(logging, registries, entry.getValue()));
            }
            blockEntity.markDirty();
        }
        // 幂等：再调用一次 restore() 时不再重复写方块
        snapshotBlocks = null;
        snapshotBlockEntities = null;
    }

    private void clearInput() {
        for (Region input : spec.inputs()) {
            for (BlockPos pos : BlockPos.iterate(input.min(), input.max())) {
                if (!world.getBlockState(pos).isAir()) {
                    // NOTIFY_ALL 而非 FORCE_STATE：保留完整的红石/邻居更新语义
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private void forceChunks() {
        int minCx = chunkRegion.min().getX() >> 4;
        int maxCx = chunkRegion.max().getX() >> 4;
        int minCz = chunkRegion.min().getZ() >> 4;
        int maxCz = chunkRegion.max().getZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (world.setChunkForced(cx, cz, true)) {
                    forcedChunks.add(new ChunkPos(cx, cz));
                }
            }
        }
    }

    private void clearEntities() {
        Box box = new Box(
                chunkRegion.min().getX(), chunkRegion.min().getY(), chunkRegion.min().getZ(),
                chunkRegion.max().getX() + 1, chunkRegion.max().getY() + 1, chunkRegion.max().getZ() + 1);
        discardEntitiesOfType(ItemEntity.class, box);
        discardEntitiesOfType(ExperienceOrbEntity.class, box);
    }

    private <T extends Entity> void discardEntitiesOfType(Class<T> type, Box box) {
        TypeFilter<Entity, T> filter = TypeFilter.instanceOf(type);
        for (T entity : world.getEntitiesByType(filter, box, e -> true)) {
            entity.discard();
        }
    }

    private void applyGameRules() {
        if (spec.gameRules().isEmpty()) {
            return;
        }
        GameRules rules = world.getGameRules();
        MinecraftServer server = world.getServer();
        for (Map.Entry<String, Object> entry : spec.gameRules().entrySet()) {
            GameRule<?> rule = findRule(rules, entry.getKey());
            savedRules.put(rule, rules.getValue(rule));
            setRule(rules, rule, entry.getValue(), server);
        }
    }

    private static GameRule<?> findRule(GameRules rules, String name) {
        // 1.21.11 的规则名与旧版差异很大（如 doMobSpawning → spawn_mobs），
        // 匹配失败时把可用名字全部列出，省得去翻文档。
        List<String> names = rules.streamRules()
                .map(rule -> rule.getId().getPath())
                .sorted()
                .toList();
        return rules.streamRules()
                .filter(rule -> rule.getId().getPath().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown gamerule: " + name + "（可用: " + String.join(", ", names) + "）"));
    }

    @SuppressWarnings("unchecked")
    private static void setRule(GameRules rules, GameRule<?> rule, Object value, MinecraftServer server) {
        // getValueClass() 决定 JSON 值该转成 Boolean 还是 Integer；GameRule 的泛型在运行时被擦除
        rules.setValue((GameRule<Object>) rule, convert(rule.getValueClass(), value), server);
    }

    private static Object convert(Class<?> type, Object value) {
        if (type == Integer.class) {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        }
        if (type == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
        }
        return value;
    }
}
