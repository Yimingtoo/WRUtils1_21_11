package com.yiming.wrutils1_21_11.test.runner;

import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.CleanupMode;
import com.yiming.wrutils1_21_11.test.model.Region;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试开始前的一次性准备与结束时的还原。
 *
 * <p>**非破坏性**：方块层面只动 {@code spec.input}（可写区），机器本体与断言位置一律不碰。
 * 强制区块/清实体按"测试涉及范围"（{@code chunkRegion} = 可写区 ∪ 所有步骤坐标）计算。
 */
public final class WorldPrep {
    private final ServerWorld world;
    private final AreaSpec spec;
    private final Region chunkRegion;
    private final List<ChunkPos> forcedChunks = new ArrayList<>();
    private final Map<GameRule<?>, Object> savedRules = new LinkedHashMap<>();

    public WorldPrep(ServerWorld world, AreaSpec spec, Region chunkRegion) {
        this.world = world;
        this.spec = spec;
        this.chunkRegion = chunkRegion;
    }

    public void prepare() {
        try {
            applyCleanup();
            if (spec.forceChunks()) {
                forceChunks();
            }
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
     * 只还原本次 prepare 改动的全局量（gamerule、强制区块），并保持幂等。
     * 可写区里的方块**不还原**（当前仅实现 CLEAR_INPUT 模式）。
     */
    public void restore() {
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
            case NONE, SNAPSHOT -> throw new UnsupportedOperationException(
                    "cleanup 模式 " + spec.cleanup() + " 尚未实现（目前仅支持 clear_input）");
        }
    }

    private void clearInput() {
        Region input = spec.input();
        if (input == null) {
            return;
        }
        for (BlockPos pos : BlockPos.iterate(input.min(), input.max())) {
            if (!world.getBlockState(pos).isAir()) {
                // NOTIFY_ALL 而非 FORCE_STATE：保留完整的红石/邻居更新语义
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
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
