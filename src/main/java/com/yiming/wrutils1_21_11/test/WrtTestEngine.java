package com.yiming.wrutils1_21_11.test;

import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.command.WrtTestCommand;
import com.yiming.wrutils1_21_11.replay.NoopRecordingBackend;
import com.yiming.wrutils1_21_11.replay.RecordingBackend;
import com.yiming.wrutils1_21_11.replay.RecordingBackends;
import com.yiming.wrutils1_21_11.replay.RecordingConfig;
import com.yiming.wrutils1_21_11.replay.RecordingFiles;
import com.yiming.wrutils1_21_11.replay.RecordingSession;
import com.yiming.wrutils1_21_11.replay.RecordingTarget;
import com.yiming.wrutils1_21_11.test.assertion.Failure;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.report.ReportWriter;
import com.yiming.wrutils1_21_11.test.report.TestResult;
import com.yiming.wrutils1_21_11.test.runner.RunState;
import com.yiming.wrutils1_21_11.test.runner.TestRun;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class WrtTestEngine {
    private static final WrtTestEngine INSTANCE = new WrtTestEngine();
    /** /wrutils recording test 的录制时长（tick）。 */
    private static final int PROBE_TICKS = 40;
    /** "通过即删"的兜底重试次数：文件可能异步落盘，隔几 tick 再 glob。 */
    private static final int CLEANUP_RETRIES = 5;

    private MinecraftServer server;
    private TestRun currentRun;

    private RecordingConfig recordingConfig = RecordingConfig.load();
    private RecordingBackend recordingBackend = NoopRecordingBackend.INSTANCE;

    // runall 状态：待跑队列 + 已完成结果 + 该批次共用的反馈源与世界
    private final Deque<TestDefinition> pending = new ArrayDeque<>();
    private final List<TestResult> batch = new ArrayList<>();
    private ServerCommandSource batchFeedback;
    private ServerWorld batchWorld;

    // "通过即删"的延迟清理队列
    private final List<Cleanup> cleanups = new ArrayList<>();
    // /wrutils recording test 的短录制
    private RecordingSession probeSession;
    private ServerWorld probeWorld;
    private ServerCommandSource probeFeedback;
    private int probeTicksLeft;

    private WrtTestEngine() {
    }

    public static WrtTestEngine get() {
        return INSTANCE;
    }

    public static void bootstrap() {
        ServerLifecycleEvents.SERVER_STARTED.register(INSTANCE::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCE::onServerStopped);
        ServerLifecycleEvents.SERVER_STOPPING.register(INSTANCE::onServerStopping);
        ServerTickEvents.END_WORLD_TICK.register(INSTANCE::onEndWorldTick);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> WrtTestCommand.register(dispatcher));
    }

    public MinecraftServer server() {
        return server;
    }

    public RecordingBackend recordingBackend() {
        return recordingBackend;
    }

    public RecordingConfig recordingConfig() {
        return recordingConfig;
    }

    /** 单跑一次。构造失败（如 gamerule 名字写错）直接抛给命令层显示可读错误。 */
    public boolean startRun(TestDefinition definition, ServerCommandSource feedback, ServerWorld world) {
        if (currentRun != null) {
            return false;
        }
        clearBatch();
        currentRun = new TestRun(world, definition, feedback, recordingBackend, recordingConfig);
        WrtLog.LOGGER.info("[WRT] started {} in {}", definition.id(), world.getRegistryKey().getValue());
        return true;
    }

    /** 串行跑一批；队列在每次 run 结束后自动推进，全部完成后打印总表。 */
    public boolean startBatch(List<TestDefinition> definitions, ServerCommandSource feedback, ServerWorld world) {
        if (currentRun != null) {
            return false;
        }
        clearBatch();
        pending.addAll(definitions);
        batchFeedback = feedback;
        batchWorld = world;
        startNext();
        return true;
    }

    private void startNext() {
        // 循环而非递归：启动失败的测试已在 startDefinition 里登记为 ABORTED，
        // 必须继续取下一个——否则 currentRun 为空、onEndWorldTick 不再被驱动，队列会卡死。
        while (true) {
            TestDefinition definition = pending.poll();
            if (definition == null) {
                finishBatch();
                return;
            }
            currentRun = startDefinition(definition, batchFeedback, batchWorld);
            if (currentRun != null) {
                return;
            }
        }
    }

    /**
     * 构造 TestRun 会在构造器里做世界隔离，可能抛异常（如 gamerule 名字写错、place/use 越界）。
     * 批跑时不能让它打断整批——登记一条 ABORTED 结果（并落盘）继续下一个。
     */
    private TestRun startDefinition(TestDefinition definition, ServerCommandSource feedback, ServerWorld world) {
        try {
            TestRun run = new TestRun(world, definition, feedback, recordingBackend, recordingConfig);
            WrtLog.LOGGER.info("[WRT] started {} in {}", definition.id(), world.getRegistryKey().getValue());
            return run;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            WrtLog.LOGGER.error("[WRT] {} 启动失败: {}", definition.id(), message);
            Failure failure = new Failure("startup", 0L, -1, definition.origin(), "", "", message);
            TestResult result = new TestResult(definition.id(), world.getRegistryKey().getValue().toString(),
                    definition.origin(), RunState.ABORTED, 0, 0, 0, List.of(failure), null);
            // 启动失败也要落盘，否则批跑报告里会凭空少一条（失败记录不完整）
            ReportWriter.writeQuietly(world.getServer(), result);
            batch.add(result);
            if (batchFeedback != null) {
                batchFeedback.sendError(Text.literal("[WRT] " + definition.id() + " 启动失败: " + message));
            }
            return null;
        }
    }

    private void clearBatch() {
        pending.clear();
        batch.clear();
        batchFeedback = null;
        batchWorld = null;
    }

    private void finishBatch() {
        if (batchFeedback == null) {
            clearBatch();
            return;
        }
        int passed = 0;
        StringBuilder table = new StringBuilder("[WRT] runall -- " + batch.size() + " tests");
        for (TestResult result : batch) {
            if (result.passed()) {
                passed++;
            }
            table.append("\n  ").append(result.outcome()).append("  ").append(result.id())
                    .append("  (").append(result.failures().size()).append(" failed)");
        }
        table.append("\n[WRT] runall: ").append(passed).append("/").append(batch.size()).append(" passed");
        ServerCommandSource feedback = batchFeedback;
        String text = table.toString();
        WrtLog.LOGGER.info(text);
        feedback.sendFeedback(() -> Text.literal(text), false);
        clearBatch();
    }

    // ---- 录制 ----

    /** 把"通过即删"的兜底清理排进队列：隔几 tick 再 glob 删除，防异步落盘竞态。 */
    public void scheduleRecordingCleanup(Path root, String label) {
        cleanups.add(new Cleanup(root, label, CLEANUP_RETRIES));
    }

    /** /wrutils recording test：在原地起一段短录制，PROBE_TICKS 后自动停止并保留。 */
    public boolean startRecordingProbe(ServerWorld world, BlockPos pos, int radiusChunks,
                                       ServerCommandSource feedback) {
        if (currentRun != null || probeSession != null || recordingBackend == NoopRecordingBackend.INSTANCE) {
            return false;
        }
        String label = "probe-" + System.currentTimeMillis();
        RecordingTarget target = new RecordingTarget(label, world.getRegistryKey().getValue().toString(),
                pos.getX() >> 4, pos.getZ() >> 4, radiusChunks);
        probeSession = recordingBackend.start(server, target);
        probeWorld = world;
        probeFeedback = feedback;
        probeTicksLeft = PROBE_TICKS;
        return true;
    }

    private void tickProbe(ServerWorld world) {
        if (probeSession == null || probeWorld != world || --probeTicksLeft > 0) {
            return;
        }
        RecordingSession session = probeSession;
        ServerCommandSource feedback = probeFeedback;
        probeSession = null;
        probeWorld = null;
        probeFeedback = null;
        session.stop(server, true);
        String location = session.producedFile().map(Path::toString)
                .orElse(recordingConfig.chunkRecordingRoot().resolve(session.label()).toString());
        String message = "[WRT] 录制探测完成: " + location;
        WrtLog.LOGGER.info(message);
        if (feedback != null) {
            feedback.sendFeedback(() -> Text.literal(message), false);
        }
    }

    private void tickCleanups() {
        for (int i = 0; i < cleanups.size(); i++) {
            Cleanup cleanup = cleanups.get(i);
            if (RecordingFiles.deleteAll(cleanup.root(), cleanup.label()) || cleanup.ticksLeft() <= 0) {
                cleanups.remove(i--);
            } else {
                cleanups.set(i, new Cleanup(cleanup.root(), cleanup.label(), cleanup.ticksLeft() - 1));
            }
        }
    }

    // ---- 生命周期 ----

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        this.recordingBackend = RecordingBackends.select(server, recordingConfig);
        WrtLog.LOGGER.info("WrtTestEngine attached");
    }

    private void onServerStopping(MinecraftServer server) {
        // 停机前必须还原 gamerule / 强制区块，否则会写进存档
        if (currentRun != null) {
            currentRun.forceRestore();
            currentRun.abortRecording();
        }
        if (probeSession != null) {
            probeSession.stop(server, true);
            probeSession = null;
        }
        clearBatch();
    }

    private void onServerStopped(MinecraftServer server) {
        this.server = null;
        this.currentRun = null;
        this.recordingBackend = NoopRecordingBackend.INSTANCE;
        clearBatch();
    }

    private void onEndWorldTick(ServerWorld world) {
        tickCleanups();
        tickProbe(world);
        if (currentRun == null || currentRun.world() != world) {
            return;
        }
        currentRun.tick();
        if (currentRun.state() == RunState.RUNNING) {
            return;
        }
        TestResult result = currentRun.result();
        currentRun = null;
        if (batchFeedback != null) {
            batch.add(result);
            startNext();
        }
    }

    private record Cleanup(Path root, String label, int ticksLeft) {
    }
}
