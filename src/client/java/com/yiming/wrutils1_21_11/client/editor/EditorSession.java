package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.test.io.TestDefinitionWriter;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.CleanupMode;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编辑器的客户端工作副本。**内存里一律用绝对坐标**（与模型、引擎一致），
 * 只在 {@link #json()} 时交给 {@link TestDefinitionWriter} 换算成相对 {@code origin} 的偏移。
 *
 * <p>做成进程级单例：选点这类操作必须"关屏 → 看世界 → 回屏"，只有跨界面的状态才接得住。
 *
 * <p><b>这里不持有文件路径</b>：多人下客户端没有服务端存档的路径，存盘由服务端做
 * （见 {@code TestEditorServer}）。客户端只负责把定义序列化成 JSON 发出去。
 */
public final class EditorSession {
    private static final EditorSession INSTANCE = new EditorSession();

    private String id = "untitled";
    private String description = "";
    private String dimension = "minecraft:overworld";
    private BlockPos origin = BlockPos.ORIGIN;
    private long timeoutTicks = 200L;
    private boolean record = true;
    private AreaSpec area;
    private final List<SetupBlock> setup = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();

    private EditorSession() {
    }

    public static EditorSession get() {
        return INSTANCE;
    }

    public void load(TestDefinition definition) {
        cancelPick();
        this.id = definition.id();
        this.description = definition.description();
        this.dimension = definition.dimension();
        this.origin = definition.origin();
        this.timeoutTicks = definition.timeoutTicks();
        this.record = definition.record();
        this.area = definition.area();
        this.setup.clear();
        this.setup.addAll(definition.setup());
        this.steps.clear();
        this.steps.addAll(definition.steps());
    }

    /** 全新的空测试：可写区先设成 origin 那一格，随加的方块由 {@link #widenInputToFit()} 撑开。 */
    public void reset(String id, String dimension, BlockPos origin) {
        reset(id, dimension, origin, List.of(Region.single(origin)));
    }

    /**
     * 同上，但可写区由调用方给定——手持燧石选过范围时用它，免得"圈好了范围、新建却建在脚底下"。
     * {@code origin} 应与 {@code inputs} 的 min 角一致（{@code /wrutils test new} 就是这么定的）。
     */
    public void reset(String id, String dimension, BlockPos origin, List<Region> inputs) {
        load(new TestDefinition(id, "", dimension, origin, 200L,
                new AreaSpec(inputs, CleanupMode.CLEAR_INPUT, false, false, Map.of()),
                List.of(), List.of(), true));
    }

    /**
     * 把可写区撑到能覆盖全部 setup / steps 坐标（"你放哪儿、可写区就到哪儿"）。
     * **只在恰好一个可写区时生效**——一旦定义出第二个区域，就说明你想自己掌控，编辑器不再替你改。
     */
    public void widenInputToFit() {
        if (area == null || area.inputs().size() != 1) {
            return;
        }
        Region region = area.inputs().get(0);
        for (SetupBlock block : setup) {
            region = region.union(Region.single(block.pos()));
        }
        for (Step step : steps) {
            region = region.union(Region.single(StepEditor.posOf(step)));
        }
        setInputs(List.of(region));
    }

    // ---- 可写区 ----

    public List<Region> inputs() {
        return area == null ? List.of() : area.inputs();
    }

    /** 换掉可写区列表，其余区域设置（cleanup / forceChunks / gameRules…）保持不变。 */
    public void setInputs(List<Region> inputs) {
        if (area == null) {
            area = new AreaSpec(inputs, CleanupMode.CLEAR_INPUT, false, false, Map.of());
            return;
        }
        area = new AreaSpec(inputs, area.cleanup(), area.forceChunks(), area.clearEntities(), area.gameRules());
    }

    /** 可写区的清理策略；没有 area 时按默认的 {@code clear_input}。 */
    public CleanupMode cleanup() {
        return area == null ? CleanupMode.CLEAR_INPUT : area.cleanup();
    }

    /** 只换 cleanup，区域列表与其余开关保持不变。 */
    public void setCleanup(CleanupMode mode) {
        if (area == null) {
            area = new AreaSpec(List.of(Region.single(origin)), mode, false, false, Map.of());
            return;
        }
        area = new AreaSpec(area.inputs(), mode, area.forceChunks(), area.clearEntities(), area.gameRules());
    }

    /**
     * 把超时抬到"最晚步骤（窗口断言算到窗口末端）+ 100 tick"，**从不降低**——
     * 用户显式填了更长超时（比如等机器稳定）时不该被改回去。
     */
    public void raiseTimeoutToFit() {
        long latest = 0L;
        for (Step step : steps) {
            long end = step.tick();
            if (step instanceof AssertStep assertStep) {
                end += Math.max(0L, assertStep.withinTicks());
            }
            latest = Math.max(latest, end);
        }
        long minimum = latest + 100L;
        if (timeoutTicks < minimum) {
            timeoutTicks = minimum;
        }
    }

    public void addRegion() {
        List<Region> inputs = new ArrayList<>(inputs());
        inputs.add(Region.single(origin));
        setInputs(inputs);
    }

    public void removeRegion(int index) {
        List<Region> inputs = new ArrayList<>(inputs());
        if (index < 0 || index >= inputs.size()) {
            return;
        }
        inputs.remove(index);
        setInputs(inputs);
    }

    public void setRegionMin(int index, BlockPos pos) {
        List<Region> inputs = new ArrayList<>(inputs());
        if (index < 0 || index >= inputs.size()) {
            return;
        }
        inputs.set(index, Region.of(pos, inputs.get(index).max()));
        setInputs(inputs);
    }

    public void setRegionMax(int index, BlockPos pos) {
        List<Region> inputs = new ArrayList<>(inputs());
        if (index < 0 || index >= inputs.size()) {
            return;
        }
        inputs.set(index, Region.of(inputs.get(index).min(), pos));
        setInputs(inputs);
    }

    /**
     * 整体平移：{@code origin} 换成新值，**所有绝对坐标同加同一个 delta**——
     * 相对偏移不变，等于把整台机器搬到新位置（存盘后 JSON 只差 origin 一个字段）。
     */
    public void translateOriginTo(BlockPos newOrigin) {
        if (newOrigin.equals(origin)) {
            return;
        }
        BlockPos delta = newOrigin.subtract(origin);
        for (int i = 0; i < setup.size(); i++) {
            setup.set(i, new SetupBlock(setup.get(i).pos().add(delta), setup.get(i).block()));
        }
        for (int i = 0; i < steps.size(); i++) {
            steps.set(i, StepEditor.withPos(steps.get(i), StepEditor.posOf(steps.get(i)).add(delta)));
        }
        if (area != null) {
            List<Region> shifted = new ArrayList<>();
            for (Region region : area.inputs()) {
                shifted.add(new Region(region.min().add(delta), region.max().add(delta)));
            }
            setInputs(shifted);
        }
        this.origin = newOrigin;
    }

    // ---- 坐标拾取（跨 Screen，所以状态必须放在这里） ----

    public enum PickKind {
        /** 把拾到的方块坐标写进指定条目。 */
        ENTRY_POS,
        /** 把拾到的方块当成新的 origin，整体平移。 */
        ORIGIN,
        /** 把拾到的方块设为可写区 {@code pickIndex} 的 min 角（{@code Region.of} 会归一化，先拾哪个角都行）。 */
        AREA_MIN,
        /** 同上，设为 max 角。 */
        AREA_MAX
    }

    private PickKind pickKind;
    private int pickIndex = -1;

    public void beginPick(PickKind kind, int index) {
        this.pickKind = kind;
        this.pickIndex = index;
    }

    public boolean isPicking() {
        return pickKind != null;
    }

    public PickKind pickKind() {
        return pickKind;
    }

    public void cancelPick() {
        this.pickKind = null;
        this.pickIndex = -1;
    }

    /** @return 这次拾取的人类可读说明；没有待处理的拾取则返回 {@code null}。 */
    public String applyPick(BlockPos pos) {
        PickKind kind = pickKind;
        int index = pickIndex;
        cancelPick();
        if (kind == null) {
            return null;
        }
        if (kind == PickKind.ORIGIN) {
            BlockPos delta = pos.subtract(origin);
            translateOriginTo(pos);
            return "已整体平移 (" + delta.getX() + ", " + delta.getY() + ", " + delta.getZ() + ")";
        }
        if (kind == PickKind.AREA_MIN) {
            setRegionMin(index, pos);
            return "可写区 " + index + " 的角已设为 (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
        }
        if (kind == PickKind.AREA_MAX) {
            setRegionMax(index, pos);
            return "可写区 " + index + " 的角已设为 (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
        }
        if (index < 0 || index >= setup.size() + steps.size()) {
            return null;
        }
        if (index < setup.size()) {
            SetupBlock old = setup.get(index);
            setup.set(index, new SetupBlock(pos, old.block()));
        } else {
            int stepIndex = index - setup.size();
            steps.set(stepIndex, StepEditor.withPos(steps.get(stepIndex), pos));
        }
        return "已填入坐标 (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public TestDefinition toDefinition() {
        return new TestDefinition(id, description, dimension, origin, timeoutTicks, area,
                List.copyOf(setup), List.copyOf(steps), record);
    }

    /** 交给服务端落盘的线格式。与磁盘上的 JSON 完全同形（同一个 writer），所以服务端只需解析再写。 */
    public String json() {
        return TestDefinitionWriter.toJson(toDefinition());
    }

    public String id() {
        return id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public String description() {
        return description;
    }

    public void setDescription(String value) {
        this.description = value;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos origin() {
        return origin;
    }

    public long timeoutTicks() {
        return timeoutTicks;
    }

    public void setTimeoutTicks(long value) {
        this.timeoutTicks = value;
    }

    public boolean record() {
        return record;
    }

    public void setRecord(boolean value) {
        this.record = value;
    }

    public AreaSpec area() {
        return area;
    }

    public List<SetupBlock> setup() {
        return setup;
    }

    public List<Step> steps() {
        return steps;
    }
}
