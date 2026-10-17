package com.yiming.wrutils1_21_11.test.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.CleanupMode;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.NbtMatch;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.RedstoneExpect;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TestDefinitionLoader {
    private static final long DEFAULT_TIMEOUT_TICKS = 200L;
    /**
     * 方块实体 NBT 的顶层元数据，匹配时默认跳过（只作用于顶层）：
     * x/y/z 是坐标、id 是方块实体类型、components 是空/组件容器（1.21 起方块实体也带 components）。
     * 需要断言 components 时用 {@code nbtIgnore} 显式覆盖。
     */
    static final List<String> DEFAULT_NBT_IGNORE_KEYS = List.of("x", "y", "z", "id", "components");

    private TestDefinitionLoader() {
    }

    public static TestDefinition load(Path file) throws IOException {
        return parse(Files.readString(file), file.toString());
    }

    /**
     * 坐标一律是**相对 origin 的偏移**，由顶层 {@code schemaVersion} 标记。
     * 旧格式（无 schemaVersion、坐标为绝对值）已废弃，直接拒绝——不做任何启发式猜测，
     * 猜错就是静默跑错，宁可报错。
     */
    public static TestDefinition parse(String json, String sourceName) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        if (!root.has("schemaVersion")) {
            throw new IllegalArgumentException(sourceName + ": 缺少 schemaVersion（旧的绝对坐标格式已废弃，"
                    + "坐标现为相对 origin 的偏移，迁移后请补上 \"schemaVersion\": "
                    + TestDefinitionWriter.SCHEMA_VERSION + "）");
        }
        int schemaVersion = root.get("schemaVersion").getAsInt();
        if (schemaVersion != TestDefinitionWriter.SCHEMA_VERSION) {
            throw new IllegalArgumentException(sourceName + ": 不支持的 schemaVersion=" + schemaVersion
                    + "（当前为 " + TestDefinitionWriter.SCHEMA_VERSION + "）");
        }

        String id = root.get("id").getAsString();
        String description = optString(root, "description", "");
        String dimension = optString(root, "dimension", "minecraft:overworld");
        BlockPos origin = readPos(root.get("origin"));
        long timeoutTicks = root.has("timeoutTicks") ? root.get("timeoutTicks").getAsLong() : DEFAULT_TIMEOUT_TICKS;
        if (root.has("isolate")) {
            throw new IllegalArgumentException("字段 isolate 已改名为 area，请重命名");
        }
        AreaSpec area = readArea(root.getAsJsonObject("area"), origin);

        List<SetupBlock> setup = new ArrayList<>();
        if (root.has("setup")) {
            for (JsonElement element : root.getAsJsonArray("setup")) {
                JsonObject entry = element.getAsJsonObject();
                setup.add(new SetupBlock(readStored(entry.get("pos"), origin), entry.get("block").getAsString()));
            }
        }
        List<Step> steps = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("steps")) {
            steps.add(readStep(element.getAsJsonObject(), origin));
        }
        return new TestDefinition(id, description, dimension, origin, timeoutTicks, area,
                List.copyOf(setup), List.copyOf(steps), !root.has("record") || root.get("record").getAsBoolean());
    }

    private static AreaSpec readArea(JsonObject area, BlockPos origin) {
        if (area == null) {
            return null;
        }
        // Phase 2 的 radius/clearArea 是全区域清空的破坏性语义，已废弃
        if (area.has("radius") || area.has("clearArea")) {
            throw new IllegalArgumentException(
                    "area.radius/clearArea 已废弃：改用 area.input（可写区数组）+ area.cleanup");
        }
        List<Region> inputs = readInputs(area, origin);
        CleanupMode cleanup = switch (optString(area, "cleanup", "clear_input").toLowerCase(Locale.ROOT)) {
            case "clear_input", "clear" -> CleanupMode.CLEAR_INPUT;
            case "none", "keep" -> CleanupMode.NONE;
            case "snapshot", "restore" -> CleanupMode.SNAPSHOT;
            default -> throw new IllegalArgumentException("unknown cleanup mode: " + area.get("cleanup").getAsString());
        };
        Map<String, Object> gameRules = new LinkedHashMap<>();
        if (area.has("gameRules")) {
            for (Map.Entry<String, JsonElement> entry : area.getAsJsonObject("gameRules").entrySet()) {
                JsonPrimitive value = entry.getValue().getAsJsonPrimitive();
                gameRules.put(entry.getKey(), value.isBoolean() ? value.getAsBoolean() : value.getAsInt());
            }
        }
        return new AreaSpec(
                inputs,
                cleanup,
                area.has("forceChunks") && area.get("forceChunks").getAsBoolean(),
                area.has("clearEntities") && area.get("clearEntities").getAsBoolean(),
                Map.copyOf(gameRules));
    }

    /**
     * 可写区是**数组**（schemaVersion 3 起支持多个）；键缺失或为 null 表示不允许任何写入。
     * 单个矩形写成对象（v2 的旧形状）会**直接报错**，不做形状猜测。
     */
    private static List<Region> readInputs(JsonObject area, BlockPos origin) {
        JsonElement element = area.get("input");
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(
                    "area.input 必须是数组（schemaVersion 3 起可写区可以有多个），"
                            + "例如 \"input\": [{ \"min\": [0,0,0], \"max\": [1,0,0] }]");
        }
        List<Region> inputs = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            inputs.add(readRegion(entry.getAsJsonObject(), origin));
        }
        return inputs;
    }

    private static Region readRegion(JsonObject region, BlockPos origin) {
        return Region.of(readStored(region.get("min"), origin), readStored(region.get("max"), origin));
    }

    private static Step readStep(JsonObject step, BlockPos origin) {
        String action = step.get("action").getAsString();
        long tick = step.get("tick").getAsLong();
        BlockPos pos = readStored(step.get("pos"), origin);
        return switch (action) {
            case "place" -> new PlaceStep(tick, pos, step.get("block").getAsString());
            case "use" -> new UseStep(tick, pos, readDirection(step, "side", Direction.UP), readHand(step, "hand"));
            case "assert" -> new AssertStep(tick, pos, readExpectation(step.getAsJsonObject("expect")),
                    readWithinTicks(step));
            default -> throw new IllegalArgumentException("unknown step action: " + action);
        };
    }

    /** 缺省 0 = 精确断言；负数没有意义，直接拒绝而不是悄悄当成 0。 */
    private static long readWithinTicks(JsonObject step) {
        if (!step.has("withinTicks")) {
            return 0L;
        }
        long value = step.get("withinTicks").getAsLong();
        if (value < 0) {
            throw new IllegalArgumentException("withinTicks 不能为负: " + value);
        }
        return value;
    }

    private static Expectation readExpectation(JsonObject expect) {
        if (expect == null) {
            return new Expectation(null, false, null, null);
        }
        String block = optString(expect, "block", null);
        boolean strict = expect.has("strict") && expect.get("strict").getAsBoolean();
        return new Expectation(block, strict, readNbtMatch(expect), readRedstone(expect));
    }

    private static NbtMatch readNbtMatch(JsonObject expect) {
        if (!expect.has("nbt")) {
            return null;
        }
        boolean exact = "exact".equalsIgnoreCase(optString(expect, "nbtMatch", "subset"));
        List<String> ignoreKeys = new ArrayList<>(DEFAULT_NBT_IGNORE_KEYS);
        if (expect.has("nbtIgnore")) {
            ignoreKeys.clear();
            for (JsonElement element : expect.getAsJsonArray("nbtIgnore")) {
                ignoreKeys.add(element.getAsString());
            }
        }
        return new NbtMatch(expect.get("nbt").getAsString(), exact, List.copyOf(ignoreKeys));
    }

    private static RedstoneExpect readRedstone(JsonObject expect) {
        if (!expect.has("redstone")) {
            return null;
        }
        JsonObject redstone = expect.getAsJsonObject("redstone");
        RedstoneExpect.Mode mode = switch (optString(redstone, "mode", "received").toLowerCase(Locale.ROOT)) {
            case "received" -> RedstoneExpect.Mode.RECEIVED;
            case "strong", "received_strong" -> RedstoneExpect.Mode.STRONG;
            case "emitted", "emit" -> RedstoneExpect.Mode.EMITTED;
            default -> throw new IllegalArgumentException("unknown redstone mode: " + redstone.get("mode").getAsString());
        };
        return new RedstoneExpect(mode, readDirection(redstone, "side", Direction.UP),
                redstone.has("min") ? redstone.get("min").getAsInt() : 0,
                redstone.has("max") ? redstone.get("max").getAsInt() : 15);
    }

    private static Direction readDirection(JsonObject obj, String key, Direction fallback) {
        return obj.has(key) ? Direction.valueOf(obj.get(key).getAsString().toUpperCase(Locale.ROOT)) : fallback;
    }

    private static Hand readHand(JsonObject obj, String key) {
        if (!obj.has(key)) {
            return Hand.MAIN_HAND;
        }
        return switch (obj.get(key).getAsString().toLowerCase(Locale.ROOT)) {
            case "off", "off_hand" -> Hand.OFF_HAND;
            default -> Hand.MAIN_HAND;
        };
    }

    /** JSON 里的 pos 存的是相对 origin 的偏移，读成绝对坐标。 */
    private static BlockPos readStored(JsonElement element, BlockPos origin) {
        return origin.add(readPos(element));
    }

    private static BlockPos readPos(JsonElement element) {
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException("pos must be [x, y, z], got " + array);
        }
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }
}
