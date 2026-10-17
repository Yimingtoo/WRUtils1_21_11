package com.yiming.wrutils1_21_11.replay;

import com.yiming.wrutils1_21_11.WrtLog;
import net.minecraft.server.MinecraftServer;

/** 探测可用后端并选一个；都没有就降级 {@link NoopRecordingBackend}（只 warn 一次）。 */
public final class RecordingBackends {
    private RecordingBackends() {
    }

    public static RecordingBackend select(MinecraftServer server, RecordingConfig config) {
        if (!config.enabled()) {
            WrtLog.LOGGER.info("[WRT] 配置里关闭了录制，使用 Noop 后端");
            return NoopRecordingBackend.INSTANCE;
        }
        RecordingBackend candidate = new ServerReplayBackend(config.chunkRecordingRoot());
        if (candidate.isAvailable(server)) {
            WrtLog.LOGGER.info("[WRT] 录制后端: {}", candidate.id());
            return candidate;
        }
        WrtLog.LOGGER.warn("[WRT] 未检测到可用的录制后端（需安装 server-replay），"
                + "测试照常运行、报告照常输出，只是不录制回放");
        return NoopRecordingBackend.INSTANCE;
    }
}
