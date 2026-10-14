package com.yiming.wrutils1_21_11.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yiming.wrutils1_21_11.replay.RecordingBackend;
import com.yiming.wrutils1_21_11.replay.RecordingConfig;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import com.yiming.wrutils1_21_11.test.io.TestDefinitionLoader;
import com.yiming.wrutils1_21_11.test.io.TestPaths;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Region;
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
import java.util.ArrayList;
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
                                        .executes(context -> run(context, null))
                                        .then(CommandManager.literal("--record")
                                                .executes(context -> run(context, true)))
                                        .then(CommandManager.literal("--no-record")
                                                .executes(context -> run(context, false)))))
                        .then(CommandManager.literal("runall")
                                .executes(context -> runAll(context, null))
                                .then(CommandManager.argument("filter", StringArgumentType.word())
                                        .executes(context -> runAll(context,
                                                StringArgumentType.getString(context, "filter")))))
                        .then(CommandManager.literal("reload").executes(WrtTestCommand::reload)))
                .then(CommandManager.literal("recording")
                        .then(CommandManager.literal("status").executes(WrtTestCommand::recordingStatus))
                        .then(CommandManager.literal("test").executes(WrtTestCommand::recordingTest)))
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
                + "\n  area=" + (definition.area() == null ? "<无>" : definition.area())
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
        Region input = Region.of(selection.pos1(), selection.pos2());
        BlockPos origin = input.min();
        String dimension = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, skeleton(id, description == null ? "" : description, dimension, origin, input));
        } catch (IOException e) {
            source.sendError(Text.literal("[WRT] 写入失败: " + e));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] 已生成 " + file
                + " (origin=" + format(origin) + " input=[" + format(input.min()) + "]..[" + format(input.max()) + "])"), false);
        return 1;
    }

    private static int run(CommandContext<ServerCommandSource> context, Boolean recordOverride) {
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
        if (recordOverride != null) {
            definition = definition.withRecord(recordOverride);
        }
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

    private static int runAll(CommandContext<ServerCommandSource> context, String filter) {
        ServerCommandSource source = context.getSource();
        Path dir = TestPaths.testsDir(source.getServer());
        if (!Files.isDirectory(dir)) {
            source.sendError(Text.literal("[WRT] 没有测试目录: " + dir));
            return 0;
        }
        List<TestDefinition> definitions = new ArrayList<>();
        int skipped = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> filter == null || idOf(path).contains(filter))
                    .sorted()
                    .toList();
            for (Path file : candidates) {
                try {
                    definitions.add(TestDefinitionLoader.load(file));
                } catch (Exception e) {
                    skipped++;
                    Path failed = file;
                    source.sendError(Text.literal("[WRT] 跳过 " + failed.getFileName() + ": " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            source.sendError(Text.literal("[WRT] 读取目录失败: " + e));
            return 0;
        }
        if (definitions.isEmpty()) {
            source.sendError(Text.literal("[WRT] 没有可运行的测试" + (skipped > 0 ? "（" + skipped + " 个加载失败）" : "")));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayer();
        ServerWorld world = player != null ? (ServerWorld) player.getEntityWorld() : source.getServer().getOverworld();
        if (!WrtTestEngine.get().startBatch(definitions, source, world)) {
            source.sendError(Text.literal("[WRT] 已有测试在运行"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] runall: " + definitions.size() + " 个测试排队运行"), false);
        return definitions.size();
    }

    private static String idOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    private static int recordingStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        WrtTestEngine engine = WrtTestEngine.get();
        RecordingBackend backend = engine.recordingBackend();
        RecordingConfig config = engine.recordingConfig();
        source.sendFeedback(() -> Text.literal("[WRT] recording"
                + "\n  enabled=" + config.enabled()
                + "\n  backend=" + backend.id()
                + "\n  minRadiusChunks=" + config.minRadiusChunks()
                + "\n  radiusExtraChunks=" + config.radiusExtraChunks()
                + "\n  paddingTicks=" + config.paddingTicks() + " (每侧)"
                + "\n  chunkRecordingRoot=" + config.chunkRecordingRoot()), false);
        return 1;
    }

    private static int recordingTest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[WRT] 需要以玩家身份执行（录制以你所在区块为中心）"));
            return 0;
        }
        WrtTestEngine engine = WrtTestEngine.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        int radius = engine.recordingConfig().radiusFor(0);
        if (!engine.startRecordingProbe(world, player.getBlockPos(), radius, source)) {
            source.sendError(Text.literal("[WRT] 无法开始录制探测：已有测试/录制在进行，或后端不可用（"
                    + engine.recordingBackend().id() + "）"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[WRT] 录制探测已开始，约 40 tick 后自动停止并保留"), false);
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

    private static String skeleton(String id, String description, String dimension, BlockPos origin, Region input) {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("description", description);
        root.addProperty("dimension", dimension);
        root.add("origin", posArray(origin));
        root.addProperty("timeoutTicks", DEFAULT_TIMEOUT_TICKS);

        JsonObject region = new JsonObject();
        region.add("min", posArray(input.min()));
        region.add("max", posArray(input.max()));

        JsonObject area = new JsonObject();
        area.add("input", region);
        area.addProperty("cleanup", "clear_input");
        area.addProperty("forceChunks", false);
        area.addProperty("clearEntities", false);
        area.add("gameRules", new JsonObject());
        root.add("area", area);

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
