package com.yiming.wrutils1_21_11.test.runner;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.test.BlockStringParser;
import com.yiming.wrutils1_21_11.test.assertion.AssertionEngine;
import com.yiming.wrutils1_21_11.test.assertion.Failure;
import com.yiming.wrutils1_21_11.test.exec.BlockInteractor;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.Expectation;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import com.yiming.wrutils1_21_11.test.report.ReportWriter;
import com.yiming.wrutils1_21_11.test.report.TestResult;
import com.yiming.wrutils1_21_11.replay.NoopRecordingBackend;
import com.yiming.wrutils1_21_11.replay.RecordingBackend;
import com.yiming.wrutils1_21_11.replay.RecordingConfig;
import com.yiming.wrutils1_21_11.replay.RecordingSession;
import com.yiming.wrutils1_21_11.replay.RecordingTarget;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestRun {
    private final ServerWorld world;
    private final TestDefinition definition;
    private final ServerCommandSource feedback;
    private final PlayerEntity player;
    private final TestScheduler scheduler;
    private final WorldPrep prep;
    /** 全部范围的包围盒，仅用于录制（录制目标必须是单个方形区域）。 */
    private final Region chunkBounds;
    private final RecordingConfig recordingConfig;
    private final List<Failure> failures = new ArrayList<>();
    /** 窗口断言（{@code withinTicks > 0}）正在等待的条目，每 tick 采样一次。 */
    private final List<PendingAssert> pendingAsserts = new ArrayList<>();

    private RecordingSession recordingSession;
    private int leadTicksRemaining;
    private int trailTicks;
    private int postRollTicksRemaining;
    private RunState state = RunState.RUNNING;
    private long relativeTick = -1;
    private int asserts;
    private int executed;
    private TestResult result;

    public TestRun(ServerWorld world, TestDefinition definition, ServerCommandSource feedback,
                   RecordingBackend recordingBackend, RecordingConfig recordingConfig) {
        this.world = world;
        this.definition = definition;
        this.feedback = feedback;
        this.recordingConfig = recordingConfig;
        // 坐标是绝对的，在错误维度执行会静默跑错位置——宁可启动前直接拒绝。
        String actualDimension = world.getRegistryKey().getValue().toString();
        if (!actualDimension.equals(definition.dimension())) {
            throw new IllegalArgumentException("维度不匹配：定义是 " + definition.dimension()
                    + "，当前世界是 " + actualDimension + "。请到对应维度再运行");
        }
        List<Step> steps = flatten(definition);
        // 先校验再碰世界：越界的 place/use 立即失败，避免改了一半才发现
        validateWritable(definition, steps);
        this.scheduler = new TestScheduler(steps);
        AreaSpec area = definition.area();
        List<Region> chunkRegions = chunkRegions(definition, steps);
        this.chunkBounds = boundsOf(chunkRegions);
        this.prep = area == null ? null : new WorldPrep(world, area, chunkRegions);
        this.player = FakePlayer.get(world, new GameProfile(FakePlayer.DEFAULT_UUID, "[WRT] tester"));
        if (prep != null) {
            prep.prepare();
        }
        // 录制在隔离准备之后开始：不把"清空可写区"这段录进去
        this.recordingSession = startRecording(recordingBackend);
        if (recordingSession != null) {
            // 测试前后各预留一段（默认 1s）：前置让录像有开场，后置让机器跑完后的状态留在片尾
            int padding = recordingConfig.paddingTicks();
            this.leadTicksRemaining = padding;
            this.trailTicks = padding;
        }
    }

    /** place/use 会修改方块，两者都必须落在**任一**可写区内；assert 不受限。 */
    private static void validateWritable(TestDefinition definition, List<Step> steps) {
        AreaSpec area = definition.area();
        List<Region> inputs = area == null ? List.of() : area.inputs();
        for (Step step : steps) {
            BlockPos pos = writablePos(step);
            if (pos == null) {
                continue;
            }
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException(
                        describeKind(step) + " 位置 (" + coords(pos) + ") 需要写入方块，但未定义 area.input 可写区");
            }
            if (!area.allows(pos)) {
                throw new IllegalArgumentException(
                        describeKind(step) + " 位置 (" + coords(pos) + ") 在可写区 " + describeInputs(inputs)
                                + " 之外：只有输入区内的方块可以被修改");
            }
        }
    }

    /**
     * 强制区块/清实体的范围：每个可写区、每个步骤坐标（含 assert，机器要 tick 才读得准）各自成区。
     * 不能取并集包围盒——两片互不相邻的可写区之间的间隙不应被强制加载或清实体。
     */
    private static List<Region> chunkRegions(TestDefinition definition, List<Step> steps) {
        AreaSpec area = definition.area();
        List<Region> regions = new ArrayList<>();
        if (area != null) {
            regions.addAll(area.inputs());
        }
        for (Step step : steps) {
            BlockPos pos = anyPos(step);
            if (pos != null) {
                regions.add(Region.single(pos));
            }
        }
        return regions.isEmpty() ? List.of(Region.single(definition.origin())) : List.copyOf(regions);
    }

    /** 录制目标必须是一个方形区域，只能取全部范围的包围盒。 */
    private static Region boundsOf(List<Region> regions) {
        Region result = null;
        for (Region region : regions) {
            result = result == null ? region : result.union(region);
        }
        return result;
    }

    private static BlockPos writablePos(Step step) {
        if (step instanceof PlaceStep place) {
            return place.pos();
        }
        if (step instanceof UseStep use) {
            return use.pos();
        }
        return null;
    }

    private static BlockPos anyPos(Step step) {
        if (step instanceof AssertStep assertStep) {
            return assertStep.pos();
        }
        return writablePos(step);
    }

    private static String describeKind(Step step) {
        if (step instanceof PlaceStep) {
            return "place 步骤";
        }
        if (step instanceof UseStep) {
            return "use 步骤";
        }
        return step.toString();
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String describeRegion(Region region) {
        return "[" + coords(region.min()) + "]..[" + coords(region.max()) + "]";
    }

    private static String describeInputs(List<Region> inputs) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                text.append(" | ");
            }
            text.append(describeRegion(inputs.get(i)));
        }
        return text.toString();
    }

    private static List<Step> flatten(TestDefinition definition) {
        List<Step> steps = new ArrayList<>(definition.setup().size() + definition.steps().size());
        for (SetupBlock block : definition.setup()) {
            steps.add(new PlaceStep(0L, block.pos(), block.block()));
        }
        steps.addAll(definition.steps());
        return steps;
    }

    public ServerWorld world() {
        return world;
    }

    public RunState state() {
        return state;
    }

    /** 仅在 {@link #state()} 不再为 RUNNING 后非 null。 */
    public TestResult result() {
        return result;
    }

    public String dimension() {
        return world.getRegistryKey().getValue().toString();
    }

    /** 给进度 HUD 的只读快照；由服务端线程（引擎的 tick 钩子）调用。 */
    public TestProgress progress(int queued) {
        // relativeTick 从 -1 起步，第一 tick 前显示 0 比显示 -1 合理
        return new TestProgress(definition.id(), executed, scheduler.total(), asserts,
                failures.size(), Math.max(0L, relativeTick), definition.timeoutTicks(), queued);
    }

    public void tick() {
        if (state != RunState.RUNNING) {
            return;
        }
        if (!world.getTickManager().shouldTick()) {
            return;
        }
        // 前置预留：录制已开始，但测试尚未推进（relativeTick 不动）
        if (leadTicksRemaining > 0) {
            leadTicksRemaining--;
            return;
        }
        // 后置预留：测试已跑完，录制继续，跑到头才收尾
        if (postRollTicksRemaining > 0) {
            if (--postRollTicksRemaining == 0) {
                finish(RunState.DONE);
            }
            return;
        }
        relativeTick++;
        try {
            for (TestScheduler.Scheduled scheduled : scheduler.due(relativeTick)) {
                execute(scheduled, relativeTick);
            }
            // 窗口断言也属于本 tick 的执行；异常同样要按 ABORTED 收尾，不能冒泡出 END_WORLD_TICK。
            checkPendingAsserts();
        } catch (RuntimeException e) {
            WrtLog.LOGGER.error("[WRT] {} crashed at tick {}", definition.id(), relativeTick, e);
            finish(RunState.ABORTED);
            return;
        }
        // 窗口断言没结束时不算跑完：最后一步之后可能还有人在等自己的窗口
        if (scheduler.isFinished() && pendingAsserts.isEmpty()) {
            if (trailTicks > 0) {
                postRollTicksRemaining = trailTicks;
            } else {
                finish(RunState.DONE);
            }
        } else if (relativeTick > definition.timeoutTicks()) {
            // 步骤没跑完、或还在等窗口断言，都算"没结束"；后置预留（已跑完）不受 timeoutTicks 影响
            WrtLog.LOGGER.warn("[WRT] {} aborted: timeout after {} ticks", definition.id(), definition.timeoutTicks());
            finish(RunState.ABORTED);
        }
    }

    /**
     * 采样所有挂起的窗口断言：任一次通过即撤销，窗口末仍失败才并入 {@code failures}。
     * 在每 tick 的步骤循环之后调用，所以同一 tick 的 place/use 一定先执行。
     */
    private void checkPendingAsserts() {
        for (int i = 0; i < pendingAsserts.size(); i++) {
            PendingAssert pending = pendingAsserts.get(i);
            List<Failure> now = AssertionEngine.check(pending.expect(), world, pending.pos(), relativeTick,
                    pending.stepIndex());
            if (now.isEmpty()) {
                pendingAsserts.remove(i--);
                continue;
            }
            if (relativeTick >= pending.endTick()) {
                for (Failure failure : now) {
                    // 失败报在窗口末，并注明这是"窗口内始终没满足"，免得读者以为只采样了一次
                    failures.add(new Failure(failure.kind(), failure.tick(), failure.stepIndex(), failure.pos(),
                            failure.expected(), failure.actual(),
                            failure.why() + "（期望在 tick " + pending.startTick() + ".." + pending.endTick()
                                    + " 内任一 tick 成立）"));
                }
                pendingAsserts.remove(i--);
            }
        }
    }

    /** 一条正在等待窗口的断言。窗口为 {@code startTick..endTick}（含两端）。 */
    private record PendingAssert(int stepIndex, BlockPos pos, Expectation expect, long startTick, long endTick) {
    }

    private void execute(TestScheduler.Scheduled scheduled, long relativeTick) {
        Step step = scheduled.step();
        executed++;
        if (step instanceof PlaceStep place) {
            placeBlock(place);
        } else if (step instanceof UseStep use) {
            BlockInteractor.use(player, world, use.pos(), use.side(), use.hand());
        } else if (step instanceof AssertStep assertStep) {
            asserts++;
            // 报告里的 step# 只数用户可见的 steps 列表；setup 是隐式前置，不占编号。
            int stepIndex = scheduled.index() - definition.setup().size();
            if (assertStep.withinTicks() <= 0) {
                failures.addAll(AssertionEngine.check(assertStep.expect(), world, assertStep.pos(), relativeTick,
                        stepIndex));
            } else {
                // 不在这里立即采样：挂到本 tick 末尾统一处理，保证同一 tick 的 place/use 一定排在采样之前。
                pendingAsserts.add(new PendingAssert(stepIndex, assertStep.pos(), assertStep.expect(),
                        relativeTick, relativeTick + assertStep.withinTicks()));
            }
        }
    }

    private void placeBlock(PlaceStep place) {
        RegistryWrapper<Block> blocks = world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);
        BlockArgumentParser.BlockResult parsed;
        try {
            // 允许方块串携带 NBT（如箱子 {Items:[...]}），供 NBT 断言使用
            parsed = BlockStringParser.parse(blocks, place.block(), true);
        } catch (CommandSyntaxException e) {
            failures.add(new Failure("place", 0L, -1, place.pos(), place.block(), "<unparsed>",
                    "bad block string: " + e.getMessage()));
            return;
        }
        world.setBlockState(place.pos(), parsed.blockState(), Block.NOTIFY_ALL);
        NbtCompound nbt = parsed.nbt();
        // 方块串不带 {...} 时 nbt() 返回 null（不是空 compound）
        if (nbt == null || nbt.isEmpty()) {
            return;
        }
        BlockEntity blockEntity = world.getBlockEntity(place.pos());
        if (blockEntity == null) {
            failures.add(new Failure("place", 0L, -1, place.pos(), place.block(), "<no block entity>",
                    "block string carries NBT but this block has no block entity"));
            return;
        }
        // 1.21.11 的 BlockEntity.read 收 ReadView；NbtReadView 把 NbtCompound 桥成 ReadView。
        // Logging 会把 NBT 解析错误写进日志，而不是静默丢弃。
        try (ErrorReporter.Logging logging = new ErrorReporter.Logging(WrtLog.LOGGER)) {
            blockEntity.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
        }
        blockEntity.markDirty();
    }

    public void forceRestore() {
        if (prep != null) {
            prep.restore();
        }
    }

    /** 服务器停机等异常路径：保留当前录制现场（keep=true），幂等。 */
    public void abortRecording() {
        if (recordingSession != null) {
            RecordingSession session = recordingSession;
            recordingSession = null;
            session.stop(world.getServer(), true);
        }
    }

    /**
     * 用户显式停止（{@code /wrutils test stop}）：按 {@link RunState#ABORTED} 正常收尾——
     * 走 {@code finish} 即会还原 gamerule/强制区块、保留录制现场、落盘报告并播报，幂等。
     */
    public void abort() {
        if (state != RunState.RUNNING) {
            return;
        }
        finish(RunState.ABORTED);
    }

    private RecordingSession startRecording(RecordingBackend backend) {
        if (backend == null || backend == NoopRecordingBackend.INSTANCE
                || !definition.record() || !recordingConfig.enabled()) {
            return null;
        }
        AreaSpec area = definition.area();
        if (area == null || !area.forceChunks()) {
            // 录制依赖区块持续加载；不强制加载时区块可能被卸载，录制会断
            WrtLog.LOGGER.warn("[WRT] {} 开了录制但 area.forceChunks=false：区块若卸载，录制会中断",
                    definition.id());
        }
        String label = ReportWriter.sanitize(definition.id()) + "-" + System.currentTimeMillis();
        RecordingTarget target = recordingTarget(label);
        WrtLog.LOGGER.info("[WRT] {} 开始录制 label={} radius={} 区块@{} {} in {}", definition.id(),
                label, target.radiusChunks(), target.centerChunkX(), target.centerChunkZ(), target.dimension());
        return backend.start(world.getServer(), target);
    }

    /** 录制范围：覆盖全部测试范围的最小方形区块区，再按配置外扩。 */
    private RecordingTarget recordingTarget(String label) {
        int minCx = chunkBounds.min().getX() >> 4;
        int maxCx = chunkBounds.max().getX() >> 4;
        int minCz = chunkBounds.min().getZ() >> 4;
        int maxCz = chunkBounds.max().getZ() >> 4;
        int centerCx = (minCx + maxCx) / 2;
        int centerCz = (minCz + maxCz) / 2;
        int span = Math.max(Math.max(centerCx - minCx, maxCx - centerCx),
                Math.max(centerCz - minCz, maxCz - centerCz));
        return new RecordingTarget(label, dimension(), centerCx, centerCz, recordingConfig.radiusFor(span));
    }

    /** 返回要写进报告的录制位置：失败时为产出文件（或该录制的目录），通过时为 null。 */
    private String stopRecording(boolean passed) {
        if (recordingSession == null) {
            return null;
        }
        RecordingSession session = recordingSession;
        recordingSession = null;
        session.stop(world.getServer(), !passed);
        if (!passed) {
            Path dir = recordingConfig.chunkRecordingRoot().resolve(session.label());
            return session.producedFile().map(Path::toString).orElse(dir.toString());
        }
        // 通过：已请求 save=false 丢弃；再让引擎延迟兜底清理，防异步落盘竞态
        WrtTestEngine.get().scheduleRecordingCleanup(recordingConfig.chunkRecordingRoot(), session.label());
        return null;
    }

    private void finish(RunState finalState) {
        this.state = finalState;
        forceRestore();
        boolean passed = finalState == RunState.DONE && failures.isEmpty();
        String recording = stopRecording(passed);
        this.result = new TestResult(definition.id(), dimension(), definition.origin(), finalState,
                executed, asserts, relativeTick, List.copyOf(failures), recording);
        announce(writeReport());
    }

    private Path writeReport() {
        return ReportWriter.writeQuietly(world.getServer(), result);
    }

    /** 聊天 + 日志同源；报告文件里的逐条失败也用同一段文本（见 ReportWriter）。 */
    private void announce(Path report) {
        String summary = "[WRT] " + definition.id() + " " + result.outcome()
                + " -- " + executed + " steps, " + asserts + " asserts, " + failures.size() + " failed"
                + (report == null ? "" : "\n[WRT] report: " + report)
                + (result.recording() == null ? "" : "\n[WRT] replay: " + result.recording());
        WrtLog.LOGGER.info(summary);
        if (feedback == null) {
            return;
        }
        feedback.sendFeedback(() -> Text.literal(summary), false);
        for (Failure failure : failures) {
            String line = failure.describe(definition.id(), result.dimension());
            WrtLog.LOGGER.warn(line);
            feedback.sendFeedback(() -> Text.literal(line), false);
        }
    }
}
