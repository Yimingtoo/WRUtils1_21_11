package com.yiming.wrutils1_21_11.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiming.wrutils1_21_11.WrtLog;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本模组自己的配置：{@code <runDir>/config/wrutils1_21_11.json}（缺省时用默认值，不自动创建文件）。
 */
public final class RecordingConfig {
    private static final String FILE_NAME = "wrutils1_21_11.json";
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MIN_RADIUS = 8;
    private static final int DEFAULT_RADIUS_EXTRA = 1;
    private static final double DEFAULT_PADDING_SECONDS = 1.0;
    private static final String DEFAULT_CHUNK_PATH = "recordings/chunks";

    private final boolean enabled;
    private final int minRadiusChunks;
    private final int radiusExtraChunks;
    private final double paddingSeconds;
    private final Path chunkRecordingRoot;

    private RecordingConfig(boolean enabled, int minRadiusChunks, int radiusExtraChunks, double paddingSeconds,
                            Path chunkRecordingRoot) {
        this.enabled = enabled;
        this.minRadiusChunks = minRadiusChunks;
        this.radiusExtraChunks = radiusExtraChunks;
        this.paddingSeconds = paddingSeconds;
        this.chunkRecordingRoot = chunkRecordingRoot;
    }

    /**
     * 录制半径（区块）：至少 {@code minRadiusChunks}，再按 {@code radiusExtraChunks} 外扩。
     * 之所以要"最小值"：只按测试区大小算半径会录得太小（小测试只有 3×3 区块），
     * 回放里出了那点范围就是虚空，看起来像"渲染距离非常近"。外扩是必须的：
     * Minecraft 不渲染录制区域最外圈的区块。
     */
    public int radiusFor(int spanRadiusChunks) {
        return Math.max(spanRadiusChunks, minRadiusChunks) + radiusExtraChunks;
    }

    public static RecordingConfig load() {
        boolean enabled = DEFAULT_ENABLED;
        int minRadius = DEFAULT_MIN_RADIUS;
        int extra = DEFAULT_RADIUS_EXTRA;
        double padding = DEFAULT_PADDING_SECONDS;
        String chunkPath = DEFAULT_CHUNK_PATH;

        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.has("recordingEnabled")) {
                    enabled = root.get("recordingEnabled").getAsBoolean();
                }
                if (root.has("recordingMinRadiusChunks")) {
                    minRadius = root.get("recordingMinRadiusChunks").getAsInt();
                }
                if (root.has("recordingRadiusExtraChunks")) {
                    extra = root.get("recordingRadiusExtraChunks").getAsInt();
                }
                if (root.has("recordingPaddingSeconds")) {
                    padding = root.get("recordingPaddingSeconds").getAsDouble();
                }
                if (root.has("chunkRecordingPath")) {
                    chunkPath = root.get("chunkRecordingPath").getAsString();
                }
            } catch (RuntimeException | IOException e) {
                WrtLog.LOGGER.warn("[WRT] 读取配置 {} 失败，改用默认值: {}", file, e.toString());
            }
        }

        Path root = Path.of(chunkPath);
        if (!root.isAbsolute()) {
            // ServerReplay 的 ./recordings/chunks 里的 ./ 是游戏运行目录
            root = FabricLoader.getInstance().getGameDir().resolve(root);
        }
        return new RecordingConfig(enabled, minRadius, extra, padding, root);
    }

    public boolean enabled() {
        return enabled;
    }

    public int minRadiusChunks() {
        return minRadiusChunks;
    }

    /** 测试前后各预留的 tick 数（由 {@code recordingPaddingSeconds} 换算，20 tick = 1 秒）。 */
    public int paddingTicks() {
        return (int) Math.round(Math.max(0.0, paddingSeconds) * 20.0);
    }

    /** 录制半径外扩的区块数：客户端不渲染录制区域最外圈区块，所以要看 radius=2 就得按 radius=3 录。 */
    public int radiusExtraChunks() {
        return radiusExtraChunks;
    }

    public Path chunkRecordingRoot() {
        return chunkRecordingRoot;
    }
}
