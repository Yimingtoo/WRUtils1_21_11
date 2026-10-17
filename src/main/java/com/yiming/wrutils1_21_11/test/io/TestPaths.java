package com.yiming.wrutils1_21_11.test.io;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TestPaths {
    private static final String MOD_DIR = "wrutils1_21_11";
    /** id 会被直接拼进文件名，只允许字母、数字、下划线、连字符。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+");

    private TestPaths() {
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public static Path testsDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(MOD_DIR).resolve("tests");
    }

    public static Path reportsDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(MOD_DIR).resolve("reports");
    }

    public static Path testFile(MinecraftServer server, String id) {
        return testsDir(server).resolve(id + ".json");
    }

    /** tests 目录里的全部 id（排序后）。目录不存在时返回空列表。 */
    public static List<String> listTestIds(MinecraftServer server) throws IOException {
        Path dir = testsDir(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }
    }
}
