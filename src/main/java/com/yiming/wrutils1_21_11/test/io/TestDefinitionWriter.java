package com.yiming.wrutils1_21_11.test.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/**
 * {@link TestDefinitionLoader} 的逆操作：模型 → JSON。
 * 与 loader 往返保真（load → save → load 对象相等）是本类的硬要求，见 {@code /wrutils test roundtrip}。
 *
 * <p>坐标语义：{@code origin} 恒为绝对值，其余坐标一律写成**相对 origin 的偏移**（{@link #SCHEMA_VERSION} = 3）。
 * 省略字段只在"省略后 loader 会还原出一模一样的值"时才做——否则必须显式写出。
 */
public final class TestDefinitionWriter {
    /** 坐标语义版本：pos / area.input 为相对 origin 的偏移；3 起可写区可以有多个。 */
    public static final int SCHEMA_VERSION = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TestDefinitionWriter() {
    }

    public static String toJson(TestDefinition definition) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("id", definition.id());
        root.addProperty("description", definition.description());
        root.addProperty("dimension", definition.dimension());
        root.add("origin", posArray(definition.origin()));
        root.addProperty("timeoutTicks", definition.timeoutTicks());
        root.addProperty("record", definition.record());
        // area 为 null 时必须整段省略：写 {} 会被 loader 读成非 null 的 AreaSpec，破坏往返相等
        if (definition.area() != null) {
            root.add("area", area(definition.area(), definition.origin()));
        }
        JsonArray setup = new JsonArray();
        for (SetupBlock block : definition.setup()) {
            JsonObject entry = new JsonObject();
            entry.add("pos", relArray(block.pos(), definition.origin()));
            entry.addProperty("block", block.block());
            setup.add(entry);
        }
        root.add("setup", setup);
        JsonArray steps = new JsonArray();
        for (Step step : definition.steps()) {
            steps.add(step(step, definition.origin()));
        }
        // steps 缺失会让 loader NPE，必须永远写出（哪怕是空数组）
        root.add("steps", steps);
        return GSON.toJson(root) + "\n";
    }

    /** 原子写：先写 .tmp 再 rename，避免引擎在服务端线程读盘时读到半截 JSON。 */
    public static void write(Path file, TestDefinition definition) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(definition), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject area(AreaSpec area, BlockPos origin) {
        JsonObject object = new JsonObject();
        // input 永远是数组：空数组与"键缺失"在 loader 里等价（都不允许写入），所以总能安全写出
        JsonArray inputs = new JsonArray();
        for (Region region : area.inputs()) {
            JsonObject entry = new JsonObject();
            entry.add("min", relArray(region.min(), origin));
            entry.add("max", relArray(region.max(), origin));
            inputs.add(entry);
        }
        object.add("input", inputs);
        object.addProperty("cleanup", area.cleanup().name().toLowerCase(Locale.ROOT));
        object.addProperty("forceChunks", area.forceChunks());
        object.addProperty("clearEntities", area.clearEntities());
        JsonObject gameRules = new JsonObject();
        area.gameRules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue() instanceof Boolean value) {
                        gameRules.addProperty(entry.getKey(), value);
                    } else if (entry.getValue() instanceof Integer value) {
                        gameRules.addProperty(entry.getKey(), value);
                    } else {
                        throw new IllegalArgumentException("gameRules 只支持布尔或整数: " + entry.getKey());
                    }
                });
        object.add("gameRules", gameRules);
        return object;
    }

    private static JsonObject step(Step step, BlockPos origin) {
        JsonObject object = new JsonObject();
        if (step instanceof PlaceStep place) {
            object.addProperty("action", "place");
            object.addProperty("tick", place.tick());
            object.add("pos", relArray(place.pos(), origin));
            object.addProperty("block", place.block());
        } else if (step instanceof UseStep use) {
            object.addProperty("action", "use");
            object.addProperty("tick", use.tick());
            object.add("pos", relArray(use.pos(), origin));
            object.addProperty("side", use.side().name().toLowerCase(Locale.ROOT));
            object.addProperty("hand", use.hand() == Hand.OFF_HAND ? "off_hand" : "main");
        } else if (step instanceof AssertStep assertStep) {
            object.addProperty("action", "assert");
            object.addProperty("tick", assertStep.tick());
            object.add("pos", relArray(assertStep.pos(), origin));
            object.add("expect", assertStep.expect() == null ? new JsonObject() : expectation(assertStep.expect()));
            // 只在非默认时写，现有定义的 JSON 形状保持不变
            if (assertStep.withinTicks() > 0) {
                object.addProperty("withinTicks", assertStep.withinTicks());
            }
        } else {
            throw new IllegalArgumentException("未知步骤类型: " + step.getClass());
        }
        return object;
    }

    private static JsonObject expectation(Expectation expect) {
        JsonObject object = new JsonObject();
        if (expect.block() != null) {
            object.addProperty("block", expect.block());
        }
        if (expect.strict()) {
            object.addProperty("strict", true);
        }
        if (expect.nbt() != null) {
            object.addProperty("nbt", expect.nbt().snbt());
            if (expect.nbt().exact()) {
                object.addProperty("nbtMatch", "exact");
            }
            // nbtIgnore 是"整体替换"语义：只有恰好等于缺省表时才能省略，否则必须写全量
            if (!expect.nbt().ignoreKeys().equals(TestDefinitionLoader.DEFAULT_NBT_IGNORE_KEYS)) {
                JsonArray ignore = new JsonArray();
                expect.nbt().ignoreKeys().forEach(ignore::add);
                object.add("nbtIgnore", ignore);
            }
        }
        if (expect.redstone() != null) {
            JsonObject redstone = new JsonObject();
            redstone.addProperty("mode", expect.redstone().mode().name().toLowerCase(Locale.ROOT));
            redstone.addProperty("side", expect.redstone().side().name().toLowerCase(Locale.ROOT));
            redstone.addProperty("min", expect.redstone().min());
            redstone.addProperty("max", expect.redstone().max());
            object.add("redstone", redstone);
        }
        return object;
    }

    /** 绝对坐标 → 相对 origin 的偏移（可为负）。 */
    private static JsonArray relArray(BlockPos pos, BlockPos origin) {
        return posArray(pos.subtract(origin));
    }

    private static JsonArray posArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }
}
