package com.yiming.wrutils1_21_11.replay;

import net.minecraft.server.MinecraftServer;

public interface RecordingBackend {
    String id();

    boolean isAvailable(MinecraftServer server);

    RecordingSession start(MinecraftServer server, RecordingTarget target);
}
