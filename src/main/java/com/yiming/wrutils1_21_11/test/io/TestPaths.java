package com.yiming.wrutils1_21_11.test.io;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

public final class TestPaths {
    private static final String MOD_DIR = "wrutils1_21_11";

    private TestPaths() {
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
}
