package com.yiming.wrutils1_21_11.replay;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Optional;

/** 没有可用录制后端时的降级实现：测试照常跑，什么都不录。 */
public final class NoopRecordingBackend implements RecordingBackend {
    public static final NoopRecordingBackend INSTANCE = new NoopRecordingBackend();

    private NoopRecordingBackend() {
    }

    @Override
    public String id() {
        return "none";
    }

    @Override
    public boolean isAvailable(MinecraftServer server) {
        return true;
    }

    @Override
    public RecordingSession start(MinecraftServer server, RecordingTarget target) {
        return new Session(target.label());
    }

    private record Session(String label) implements RecordingSession {
        @Override
        public void stop(MinecraftServer server, boolean keep) {
        }

        @Override
        public Optional<Path> producedFile() {
            return Optional.empty();
        }
    }
}
