package com.yiming.wrutils1_21_11.test.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.IsolationSpec;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
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
    private static final int DEFAULT_ISOLATE_RADIUS = 3;

    private TestDefinitionLoader() {
    }

    public static TestDefinition load(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        String id = root.get("id").getAsString();
        String description = optString(root, "description", "");
        String dimension = optString(root, "dimension", "minecraft:overworld");
        BlockPos origin = readPos(root.get("origin"));
        long timeoutTicks = root.has("timeoutTicks") ? root.get("timeoutTicks").getAsLong() : DEFAULT_TIMEOUT_TICKS;
        IsolationSpec isolate = readIsolate(root.getAsJsonObject("isolate"));

        List<SetupBlock> setup = new ArrayList<>();
        if (root.has("setup")) {
            for (JsonElement element : root.getAsJsonArray("setup")) {
                JsonObject entry = element.getAsJsonObject();
                setup.add(new SetupBlock(readPos(entry.get("pos")), entry.get("block").getAsString()));
            }
        }
        List<Step> steps = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("steps")) {
            steps.add(readStep(element.getAsJsonObject()));
        }
        return new TestDefinition(id, description, dimension, origin, timeoutTicks, isolate,
                List.copyOf(setup), List.copyOf(steps));
    }

    private static IsolationSpec readIsolate(JsonObject isolate) {
        if (isolate == null) {
            return null;
        }
        Map<String, Object> gameRules = new LinkedHashMap<>();
        if (isolate.has("gameRules")) {
            for (Map.Entry<String, JsonElement> entry : isolate.getAsJsonObject("gameRules").entrySet()) {
                JsonPrimitive value = entry.getValue().getAsJsonPrimitive();
                gameRules.put(entry.getKey(), value.isBoolean() ? value.getAsBoolean() : value.getAsInt());
            }
        }
        return new IsolationSpec(
                isolate.has("radius") ? isolate.get("radius").getAsInt() : DEFAULT_ISOLATE_RADIUS,
                !isolate.has("clearArea") || isolate.get("clearArea").getAsBoolean(),
                !isolate.has("clearEntities") || isolate.get("clearEntities").getAsBoolean(),
                !isolate.has("forceChunks") || isolate.get("forceChunks").getAsBoolean(),
                Map.copyOf(gameRules));
    }

    private static Step readStep(JsonObject step) {
        String action = step.get("action").getAsString();
        long tick = step.get("tick").getAsLong();
        BlockPos pos = readPos(step.get("pos"));
        return switch (action) {
            case "place" -> new PlaceStep(tick, pos, step.get("block").getAsString());
            case "use" -> new UseStep(tick, pos, readDirection(step, "side", Direction.UP), readHand(step, "hand"));
            case "assert" -> new AssertStep(tick, pos, readExpectation(step.getAsJsonObject("expect")));
            default -> throw new IllegalArgumentException("unknown step action: " + action);
        };
    }

    private static Expectation readExpectation(JsonObject expect) {
        String block = expect != null && expect.has("block") ? expect.get("block").getAsString() : null;
        return new Expectation(block);
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
