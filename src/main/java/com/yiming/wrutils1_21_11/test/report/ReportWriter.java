package com.yiming.wrutils1_21_11.test.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.test.assertion.Failure;
import com.yiming.wrutils1_21_11.test.io.TestPaths;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把 {@link TestResult} 落盘成同名的一对文件：
 * {@code reports/<时间戳>-<id>.json}（结构化）与 {@code .txt}（人读）。
 */
public final class ReportWriter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ReportWriter() {
    }

    /** 落盘失败只告警、不抛出：报告写不出来不应影响 run 的收尾。 */
    public static Path writeQuietly(MinecraftServer server, TestResult result) {
        try {
            return write(server, result);
        } catch (IOException | RuntimeException e) {
            WrtLog.LOGGER.warn("[WRT] {} 报告写入失败: {}", result.id(), e.toString());
            return null;
        }
    }

    public static Path write(MinecraftServer server, TestResult result) throws IOException {
        Path dir = TestPaths.reportsDir(server);
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(STAMP);
        String base = stamp + "-" + sanitize(result.id());
        Path json = dir.resolve(base + ".json");
        Files.writeString(json, json(result, stamp));
        Files.writeString(dir.resolve(base + ".txt"), text(result, stamp));
        return json;
    }

    /** 测试 id 来自 JSON 文件，可能含路径分隔符等，落盘前消毒。 */
    public static String sanitize(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (char c : id.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? "test" : sb.toString();
    }

    private static String json(TestResult result, String stamp) {
        JsonObject root = new JsonObject();
        root.addProperty("id", result.id());
        root.addProperty("timestamp", stamp);
        root.addProperty("state", result.outcome());
        root.addProperty("dimension", result.dimension());
        root.add("origin", posArray(result.origin()));
        root.addProperty("ticks", result.ticks());
        root.addProperty("executed", result.executed());
        root.addProperty("asserts", result.asserts());
        root.addProperty("failed", result.failures().size());
        if (result.recording() != null) {
            root.addProperty("recording", result.recording());
        }

        JsonArray failures = new JsonArray();
        for (Failure failure : result.failures()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", failure.kind());
            entry.addProperty("step", failure.stepIndex());
            entry.addProperty("tick", failure.tick());
            entry.add("pos", posArray(failure.pos()));
            entry.addProperty("expected", failure.expected());
            entry.addProperty("actual", failure.actual());
            entry.addProperty("why", failure.why());
            failures.add(entry);
        }
        root.add("failures", failures);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    private static String text(TestResult result, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== WRT report ===\n")
                .append("test      ").append(result.id()).append('\n')
                .append("time      ").append(stamp).append('\n')
                .append("state     ").append(result.outcome()).append('\n')
                .append("dimension ").append(result.dimension()).append('\n')
                .append("origin    ").append(format(result.origin())).append('\n')
                .append("ticks     ").append(result.ticks()).append('\n')
                .append("counts    ").append(result.executed()).append(" steps, ")
                .append(result.asserts()).append(" asserts, ")
                .append(result.failures().size()).append(" failed\n");
        if (result.recording() != null) {
            sb.append("recording ").append(result.recording()).append('\n');
        }
        for (Failure failure : result.failures()) {
            sb.append('\n').append(failure.describe(result.id(), result.dimension())).append('\n');
        }
        return sb.toString();
    }

    private static JsonArray posArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
