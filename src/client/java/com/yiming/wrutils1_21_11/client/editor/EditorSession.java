package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.test.io.TestDefinitionWriter;
import com.yiming.wrutils1_21_11.test.model.AreaSpec;
import com.yiming.wrutils1_21_11.test.model.CleanupMode;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编辑器的客户端工作副本。**内存里一律用绝对坐标**（与模型、引擎一致），
 * 只在 {@link #save} 时交给 {@link TestDefinitionWriter} 换算成相对 {@code origin} 的偏移。
 *
 * <p>做成进程级单例：选点这类操作必须"关屏 → 看世界 → 回屏"，只有跨界面的状态才接得住。
 */
public final class EditorSession {
    private static final EditorSession INSTANCE = new EditorSession();

    private Path file;
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

    public void load(Path file, TestDefinition definition) {
        cancelPick();
        this.file = file;
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
        load(null, new TestDefinition(id, "", dimension, origin, 200L,
                new AreaSpec(List.of(Region.single(origin)), CleanupMode.CLEAR_INPUT, false, false, Map.of()),
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

    public void save(Path target) throws IOException {
        TestDefinitionWriter.write(target, toDefinition());
        this.file = target;
    }

    public Path file() {
        return file;
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

    public void setOrigin(BlockPos value) {
        this.origin = value;
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
