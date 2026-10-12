package com.yiming.wrutils1_21_11.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import com.yiming.wrutils1_21_11.test.io.TestDefinitionLoader;
import com.yiming.wrutils1_21_11.test.io.TestPaths;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class WrtTestCommand {
    private static final int DEFAULT_TIMEOUT_TICKS = 200;

    private WrtTestCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wrutils")
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .then(CommandManager.literal("test")
                        .then(CommandManager.literal("list")
                                .executes(context -> list(context, null))
                                .then(CommandManager.argument("filter", StringArgumentType.word())
                                        .executes(context -> list(context, StringArgumentType.getString(context, "filter")))))
                        .then(CommandManager.literal("show")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .executes(WrtTestCommand::show)))
                        .then(CommandManager.literal("new")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .executes(context -> createNew(context, null))
                                        .then(CommandManager.argument("description", StringArgumentType.greedyString())
                                                .executes(context -> createNew(context,
                                                        StringArgumentType.getString(context, "description"))))))
                        .then(CommandManager.literal("run")
                                .then(CommandManager.argument("id", StringArgumentType.word())
                                        .executes(WrtTestCommand::run)))
                        .then(CommandManager.literal("reload").executes(WrtTestCommand::reload)))
                .then(WrtSelectionCommand.node()));
    }

    private static int list(CommandContext<ServerCommandSource> context, String filter) {
        ServerCommandSource source = context.getSource();
        Path dir = TestPaths.testsDir(source.getServer());
        if (!Files.isDirectory(dir)) {
            source.sendFeedback(() -> Text.literal("[WRT] 没有测试目录: " + dir), false);
            return 0;
        }
        List<String> ids;
        try (Stream<Path> files = Files.list(dir)) {
            ids = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .filter(id -> filter == null || id.contains(filter))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            source.sendError(Text.literal("[WRT] 读取目录失败: " + e));
            return 0;
        }
        if (ids.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[WRT] 没有匹配的测试"), false);
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] " + ids.size() + " 个测试: " + String.join(", ", ids)), false);
        return ids.size();
    }

    private static int show(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        TestDefinition definition;
        try {
            definition = load(source.getServer(), id);
        } catch (Exception e) {
            source.sendError(Text.literal("[WRT] " + e.getMessage()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] " + definition.id() + ": " + definition.description()
                + "\n  dimension=" + definition.dimension() + " origin=" + format(definition.origin())
                + " timeout=" + definition.timeoutTicks()
                + "\n  isolate=" + (definition.isolate() == null ? "<无>" : definition.isolate())
                + "\n  setup=" + definition.setup().size() + " 项, steps=" + definition.steps().size() + " 项"), false);
        for (int i = 0; i < definition.steps().size(); i++) {
            int index = i;
            Step step = definition.steps().get(i);
            source.sendFeedback(() -> Text.literal("    #" + index + " t" + step.tick() + " " + describe(step)), false);
        }
        return 1;
    }

    private static int createNew(CommandContext<ServerCommandSource> context, String description) {
        ServerCommandSource source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[WRT] 需要以玩家身份执行（选区属于玩家）"));
            return 0;
        }
        SelectionManager.Selection selection = SelectionManager.get().get(player.getUuid());
        if (!selection.isComplete()) {
            source.sendError(Text.literal("[WRT] 请先用 /wrutils sel pos1|pos2 设置选区"));
            return 0;
        }
        Path file = TestPaths.testFile(source.getServer(), id);
        if (Files.exists(file)) {
            source.sendError(Text.literal("[WRT] 已存在: " + file));
            return 0;
        }
        BlockPos origin = selection.pos1();
        int radius = radiusFor(origin, selection.pos2());
        String dimension = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, skeleton(id, description == null ? "" : description, dimension, origin, radius));
        } catch (IOException e) {
            source.sendError(Text.literal("[WRT] 写入失败: " + e));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] 已生成 " + file
                + " (origin=" + format(origin) + " radius=" + radius + ")"), false);
        return 1;
    }

    private static int run(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        TestDefinition definition;
        try {
            definition = load(source.getServer(), id);
        } catch (Exception e) {
            source.sendError(Text.literal("[WRT] " + e.getMessage()));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayer();
        ServerWorld world = player != null ? (ServerWorld) player.getEntityWorld() : source.getServer().getOverworld();
        try {
            if (!WrtTestEngine.get().startRun(definition, source, world)) {
                source.sendError(Text.literal("[WRT] 已有测试在运行"));
                return 0;
            }
        } catch (RuntimeException e) {
            // 世界隔离可以在这一步失败（如 gamerule 名字写错），要给出可读信息而不是
            // 让它冒泡成原版的 "An unexpected error occurred"
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            source.sendError(Text.literal("[WRT] 启动失败: " + message));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] running " + id + " in " + world.getRegistryKey().getValue()), false);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Path dir = TestPaths.testsDir(source.getServer());
        long count = 0L;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                count = files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
            } catch (IOException e) {
                source.sendError(Text.literal("[WRT] 读取目录失败: " + e));
                return 0;
            }
        }
        long total = count;
        source.sendFeedback(() -> Text.literal("[WRT] 重新扫描完成，共 " + total
                + " 个测试（引擎每次运行都从磁盘读取，无缓存）"), false);
        return 1;
    }

    private static TestDefinition load(MinecraftServer server, String id) throws IOException {
        Path file = TestPaths.testFile(server, id);
        if (!Files.isRegularFile(file)) {
            throw new IOException("找不到测试定义: " + file);
        }
        return TestDefinitionLoader.load(file);
    }

    private static String skeleton(String id, String description, String dimension, BlockPos origin, int radius) {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("description", description);
        root.addProperty("dimension", dimension);
        root.add("origin", posArray(origin));
        root.addProperty("timeoutTicks", DEFAULT_TIMEOUT_TICKS);

        JsonObject isolate = new JsonObject();
        isolate.addProperty("radius", radius);
        isolate.addProperty("clearArea", true);
        isolate.addProperty("clearEntities", true);
        isolate.addProperty("forceChunks", true);
        isolate.add("gameRules", new JsonObject());
        root.add("isolate", isolate);

        root.add("setup", new JsonArray());
        root.add("steps", new JsonArray());
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    private static JsonArray posArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static int radiusFor(BlockPos origin, BlockPos other) {
        return Math.max(
                Math.max(Math.abs(other.getX() - origin.getX()), Math.abs(other.getY() - origin.getY())),
                Math.abs(other.getZ() - origin.getZ()));
    }

    private static String describe(Step step) {
        if (step instanceof PlaceStep place) {
            return "place " + format(place.pos()) + " " + place.block();
        }
        if (step instanceof UseStep use) {
            return "use " + format(use.pos()) + " side=" + use.side().name() + " hand=" + use.hand().name();
        }
        if (step instanceof AssertStep assertStep) {
            return "assert " + format(assertStep.pos()) + " " + assertStep.expect();
        }
        return step.toString();
    }

    private static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
