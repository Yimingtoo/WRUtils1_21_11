package com.yiming.wrutils1_21_11.replay;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Optional;

public interface RecordingSession {
    String label();

    /** {@code keep=false} 表示这次录制通过、应被丢弃。 */
    void stop(MinecraftServer server, boolean keep);

    /** 产出文件；可能因异步落盘而暂时拿不到。 */
    Optional<Path> producedFile();
}
