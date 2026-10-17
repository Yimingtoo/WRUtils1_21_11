package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.client.net.ClientTestEditor;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * 测试编辑器（Phase 5.3）：整窗编辑**一个**测试。
 *
 * <p>改动直接写进 {@link EditorSession} 的工作副本（绝对坐标），保存时才经
 * {@code TestDefinitionWriter} 换算成相对 origin 的偏移。
 *
 * <p>方块串复用 {@code /setblock} 的解析器实时校验；字段一旦非法就标红，且**保存会被拒绝**——
 * 不让坏串落盘。条目列表与左列一样是自绘 + 手动命中检测。
 */
public class TestEditScreen extends Screen {
    private static final String TITLE = "WRT 编辑测试";
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 12;
    private static final int FIELD_HEIGHT = 16;
    private static final int META_ROW_1 = 30;
    private static final int META_ROW_2 = 52;
    private static final int LIST_TOP = 74;
    private static final int BUTTON_GAP = 4;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_LABEL = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_OK = 0xFF55FF55;
    private static final int COLOR_SELECTED_BG = 0xFF3A6EA5;
    private static final int COLOR_PANEL_BG = 0xC0000000;

    private final EditorSession session = EditorSession.get();

    private TextFieldWidget idField;
    private TextFieldWidget timeoutField;
    private TextFieldWidget originField;
    private TextFieldWidget descField;
    private ButtonWidget recordButton;
    private TextFieldWidget tickField;
    /** 仅 assert 可用：窗口宽度，0 = 只在该 tick 采样。 */
    private TextFieldWidget withinField;
    private TextFieldWidget posField;
    private TextFieldWidget mainField;
    private ButtonWidget typeButton;
    private ButtonWidget pickPosButton;

    /** -1 = 没选中；0..setup.size()-1 = setup 项；其余为 steps 项。 */
    private int entryIndex = -1;
    private int listScroll;
    private String error;
    private String status;
    /** 程序化 setText 时抑制监听器回写，避免把旧值写到新选中的条目上。 */
    private boolean suppress;
    /** 当前选中项是否有"主字段"（方块串）：setup / place / assert 有，use 没有。 */
    private boolean mainFieldApplies;
    /** 焦点是在按钮回调之后才被 ParentElement 设置上去的，只能延后一帧清（见 {@link #tick()}）。 */
    private boolean clearFocusNextTick;
    /** 返回列表的自动保存正在进行；挡住重复的 ESC / 返回点击。 */
    private boolean leaving;

    public TestEditScreen() {
        super(Text.literal(TITLE));
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    /** Esc 返回上一级（列表屏）并保留选中项，而不是直接关回游戏。 */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            backToList();
            return true;
        }
        return super.keyPressed(input);
    }

    /**
     * 返回列表 = 自动保存。校验失败或保存失败**先拦下**，给出「丢弃离开 / 继续编辑」的选择；
     * 成功则切回列表，并在列表屏顶部显示保存提示（保存结果原本只显示在编辑屏里，切走就看不见了）。
     */
    private void backToList() {
        if (leaving) {
            return;
        }
        leaving = true;
        error = null;
        status = null;
        String problem = validateAll();
        if (problem != null) {
            leaving = false;
            error = problem;
            promptDiscard(problem);
            return;
        }
        long timeoutBefore = session.timeoutTicks();
        long raisedTo = prepareForSave();
        boolean timeoutRaised = raisedTo != timeoutBefore;
        ClientTestEditor.writeTest(session.id().trim(), session.json(), response -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (response.isError()) {
                leaving = false;
                error = response.error();
                if (client.currentScreen == this) {
                    promptDiscard(response.error());
                }
                return;
            }
            if (client.currentScreen == this) {
                // 提示显示在返回后的列表屏顶部，而不是聊天栏
                client.setScreen(new TestEditorScreen(response.id(),
                        "已保存 " + response.id() + ".json"
                                + (timeoutRaised ? "（超时自动提高到 " + raisedTo + "）" : "")));
            }
        });
    }

    /** 拦下离开：yes = 丢弃改动去列表，no = 留在编辑屏。 */
    private void promptDiscard(String problem) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                client.setScreen(new TestEditorScreen(session.id()));
            } else {
                client.setScreen(this);
            }
        }, Text.literal("无法保存"), Text.literal(problem),
                Text.literal("丢弃离开"), Text.literal("继续编辑")));
    }

    /**
     * 开始拾取：记下意图（写进哪个条目 / 当原点整体平移），关屏让玩家看世界，
     * 按「拾取方块」键时由客户端 tick 里兑现（见 {@code Wrutils1_21_11Client.pickBlock}）。
     */
    private void beginPick(EditorSession.PickKind kind) {
        if (kind == EditorSession.PickKind.ENTRY_POS) {
            if (entryIndex < 0 || entryIndex >= entryCount()) {
                error = "先在列表里选中一个条目，再拾取坐标";
                return;
            }
            session.beginPick(kind, entryIndex);
        } else {
            session.beginPick(kind, -1);
        }
        error = null;
        if (this.client.player != null) {
            this.client.player.sendMessage(Text.literal(
                    "[WRT] 准星对准方块，按「拾取方块」键填入坐标（默认 P）；想取消就按 K 回到列表"), false);
        }
        this.client.setScreen(null);
    }

    @Override
    public void tick() {
        super.tick();
        // ParentElement.mouseClicked 的顺序是"先触发按钮回调、再 setFocused(按钮)"，
        // 所以在回调里清焦点会被覆盖——只能延后一帧清，否则点过的按钮会一直保持高亮。
        if (clearFocusNextTick) {
            clearFocusNextTick = false;
            this.setFocused(null);
        }
    }

    // ---- 布局 ----

    private int listBottom() {
        return this.height - 84;
    }

    private int formRowY() {
        return this.height - 78;
    }

    private int buttonRow1Y() {
        return this.height - 50;
    }

    private int buttonRow2Y() {
        return this.height - 26;
    }

    private int entryCount() {
        return session.setup().size() + session.steps().size();
    }

    private boolean isSetupSelected() {
        return entryIndex >= 0 && entryIndex < session.setup().size();
    }

    private int stepIndexOf() {
        return entryIndex - session.setup().size();
    }

    private int maxScroll() {
        int visible = Math.max(0, (listBottom() - LIST_TOP) / ROW_HEIGHT);
        return Math.max(0, entryCount() - visible) * ROW_HEIGHT;
    }

    // ---- 构建 ----

    @Override
    protected void init() {
        int usable = this.width - MARGIN * 2;

        idField = add(new TextFieldWidget(this.textRenderer, 22, META_ROW_1, 74, FIELD_HEIGHT, Text.literal("id")));
        idField.setMaxLength(64);
        idField.setText(session.id());
        idField.setChangedListener(value -> {
            if (!suppress) {
                session.setId(value);
            }
        });

        timeoutField = add(new TextFieldWidget(this.textRenderer, 126, META_ROW_1, 36, FIELD_HEIGHT, Text.literal("超时")));
        timeoutField.setText(Long.toString(session.timeoutTicks()));
        timeoutField.setChangedListener(value -> {
            if (suppress) {
                return;
            }
            String problem = BlockStringValidator.validateNonNegativeInteger(value);
            timeoutField.setEditableColor(problem == null ? COLOR_TEXT : COLOR_ERROR);
            if (problem == null) {
                session.setTimeoutTicks(Long.parseLong(value.trim()));
            }
        });

        recordButton = ButtonWidget.builder(Text.literal(""), button -> {
            session.setRecord(!session.record());
            button.setMessage(recordLabel());
        }).dimensions(168, META_ROW_1, 64, FIELD_HEIGHT).build();
        recordButton.setMessage(recordLabel());
        addDrawableChild(recordButton);

        originField = add(new TextFieldWidget(this.textRenderer, 34, META_ROW_2, 90, FIELD_HEIGHT, Text.literal("x y z")));
        originField.setText(formatPlain(session.origin()));
        originField.setChangedListener(value -> {
            if (suppress) {
                return;
            }
            Integer[] pos = BlockStringValidator.parsePos(value);
            originField.setEditableColor(pos == null ? COLOR_ERROR : COLOR_TEXT);
            if (pos != null) {
                // 改 origin 一律是**整体平移**：所有坐标同加 delta，相对偏移不变
                session.translateOriginTo(new BlockPos(pos[0], pos[1], pos[2]));
            }
        });

        descField = add(new TextFieldWidget(this.textRenderer, 154, META_ROW_2, usable - 146, FIELD_HEIGHT,
                Text.literal("描述")));
        descField.setMaxLength(200);
        descField.setText(session.description());
        descField.setChangedListener(value -> {
            if (!suppress) {
                session.setDescription(value);
            }
        });

        // 条目表单：四格都靠 placeholder 自解释（tick / 窗口 / 坐标 / 方块串）。
        // tick 与窗口用固定窄宽，剩下的全给方块串（它最需要横向空间）。
        int tickWidth = 44;
        int withinWidth = 54;
        int posWidth = 90;
        int mainX = MARGIN + tickWidth + withinWidth + posWidth + BUTTON_GAP * 3;
        int mainWidth = Math.max(60, this.width - MARGIN - mainX);
        tickField = add(new TextFieldWidget(this.textRenderer, MARGIN, formRowY(), tickWidth, FIELD_HEIGHT,
                Text.literal("tick")));
        tickField.setChangedListener(value -> {
            if (suppress || entryIndex < 0 || isSetupSelected()) {
                return;
            }
            String problem = BlockStringValidator.validateNonNegativeInteger(value);
            tickField.setEditableColor(problem == null ? COLOR_TEXT : COLOR_ERROR);
            if (problem != null) {
                return;
            }
            int index = stepIndexOf();
            session.steps().set(index, StepEditor.withTick(session.steps().get(index), Long.parseLong(value.trim())));
        });

        withinField = add(new TextFieldWidget(this.textRenderer, MARGIN + tickWidth + BUTTON_GAP, formRowY(),
                withinWidth, FIELD_HEIGHT, Text.literal("窗口")));
        withinField.setChangedListener(value -> {
            if (suppress || entryIndex < 0 || isSetupSelected()) {
                return;
            }
            String problem = BlockStringValidator.validateNonNegativeInteger(value);
            withinField.setEditableColor(problem == null ? COLOR_TEXT : COLOR_ERROR);
            if (problem != null) {
                return;
            }
            int index = stepIndexOf();
            session.steps().set(index, StepEditor.withWithinTicks(session.steps().get(index),
                    Long.parseLong(value.trim())));
        });

        posField = add(new TextFieldWidget(this.textRenderer,
                MARGIN + tickWidth + withinWidth + BUTTON_GAP * 2, formRowY(),
                posWidth, FIELD_HEIGHT, Text.literal("x y z")));
        posField.setChangedListener(value -> {
            if (suppress || entryIndex < 0) {
                return;
            }
            Integer[] pos = BlockStringValidator.parsePos(value);
            posField.setEditableColor(pos == null ? COLOR_ERROR : COLOR_TEXT);
            if (pos == null) {
                return;
            }
            BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
            if (isSetupSelected()) {
                SetupBlock old = session.setup().get(entryIndex);
                session.setup().set(entryIndex, new SetupBlock(blockPos, old.block()));
            } else {
                int index = stepIndexOf();
                session.steps().set(index, StepEditor.withPos(session.steps().get(index), blockPos));
            }
        });

        mainField = add(new TextFieldWidget(this.textRenderer, mainX, formRowY(),
                mainWidth, FIELD_HEIGHT, Text.literal("方块串")));
        mainField.setMaxLength(512);
        mainField.setChangedListener(value -> {
            if (suppress || entryIndex < 0) {
                return;
            }
            String problem = BlockStringValidator.validate(value);
            mainField.setEditableColor(problem == null ? COLOR_TEXT : COLOR_ERROR);
            if (isSetupSelected()) {
                session.setup().set(entryIndex, new SetupBlock(session.setup().get(entryIndex).pos(), value));
            } else {
                int index = stepIndexOf();
                session.steps().set(index, StepEditor.withMainField(session.steps().get(index), value));
            }
        });

        int sixWidth = (usable - BUTTON_GAP * 5) / 6;
        int x = MARGIN;
        typeButton = ButtonWidget.builder(Text.literal("类型"), button -> cycleType())
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build();
        addDrawableChild(typeButton);
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("＋setup"), button -> addSetup())
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("＋step"), button -> addStep())
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("删除"), button -> deleteEntry())
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("上移"), button -> moveEntry(-1))
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("下移"), button -> moveEntry(1))
                .dimensions(x, buttonRow1Y(), sixWidth, 20).build());

        x = MARGIN;
        pickPosButton = ButtonWidget.builder(Text.literal("拾取坐标"),
                        button -> beginPick(EditorSession.PickKind.ENTRY_POS))
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build();
        addDrawableChild(pickPosButton);
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("拾取原点"),
                        button -> beginPick(EditorSession.PickKind.ORIGIN))
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("可写区"),
                        button -> this.client.setScreen(new TestAreaScreen()))
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("保存"), button -> save(false))
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        // 「运行」= 先校验并保存，再跑——不允许"跑磁盘上的旧版本"，那只会让人困惑
        addDrawableChild(ButtonWidget.builder(Text.literal("运行"), button -> save(true))
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build());
        x += sixWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("返回列表"), button -> backToList())
                .dimensions(x, buttonRow2Y(), sixWidth, 20).build());

        if (entryIndex >= entryCount()) {
            entryIndex = entryCount() - 1;
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
        syncFormFromSelection();
    }

    private TextFieldWidget add(TextFieldWidget field) {
        return addDrawableChild(field);
    }

    /**
     * 禁用输入框要连 {@code active} 一起关：只 {@code setEditable(false)} 的话，
     * {@code ClickableWidget.mouseClicked} 照样会被调用（它只查 {@code isInteractable()}），
     * 表现为"点一下闪出高亮、随后又变灰"。
     */
    private void setFieldEnabled(TextFieldWidget field, boolean enabled) {
        field.setEditable(enabled);
        field.active = enabled;
    }

    private Text recordLabel() {
        return Text.literal(session.record() ? "录制:开" : "录制:关");
    }

    // ---- 条目操作 ----

    private void syncFormFromSelection() {
        suppress = true;
        boolean hasEntry = entryIndex >= 0 && entryIndex < entryCount();
        if (!hasEntry) {
            tickField.setText("");
            withinField.setText("");
            posField.setText("");
            mainField.setText("");
            setFieldEnabled(tickField, false);
            setFieldEnabled(withinField, false);
            setFieldEnabled(posField, false);
            setFieldEnabled(mainField, false);
            mainFieldApplies = false;
            typeButton.active = false;
            typeButton.setMessage(Text.literal("类型"));
        } else if (isSetupSelected()) {
            SetupBlock block = session.setup().get(entryIndex);
            tickField.setText("");
            withinField.setText("");
            setFieldEnabled(tickField, false);
            setFieldEnabled(withinField, false);
            posField.setText(formatPlain(block.pos()));
            setFieldEnabled(posField, true);
            mainField.setText(block.block());
            setFieldEnabled(mainField, true);
            mainFieldApplies = true;
            typeButton.active = false;
            typeButton.setMessage(Text.literal("类型:setup"));
        } else {
            Step step = session.steps().get(stepIndexOf());
            tickField.setText(Long.toString(step.tick()));
            setFieldEnabled(tickField, true);
            boolean isAssert = step instanceof AssertStep;
            withinField.setText(isAssert ? Long.toString(((AssertStep) step).withinTicks()) : "");
            setFieldEnabled(withinField, isAssert);
            posField.setText(formatPlain(StepEditor.posOf(step)));
            setFieldEnabled(posField, true);
            mainField.setText(StepEditor.mainField(step));
            mainFieldApplies = StepEditor.typeOf(step) != StepEditor.Type.USE;
            setFieldEnabled(mainField, mainFieldApplies);
            typeButton.active = true;
            typeButton.setMessage(Text.literal("类型:" + StepEditor.typeOf(step).label()));
        }
        tickField.setEditableColor(COLOR_TEXT);
        withinField.setEditableColor(COLOR_TEXT);
        posField.setEditableColor(COLOR_TEXT);
        mainField.setEditableColor(COLOR_TEXT);
        pickPosButton.active = hasEntry;
        suppress = false;
    }

    private void cycleType() {
        if (entryIndex < 0 || isSetupSelected() || stepIndexOf() >= session.steps().size()) {
            return;
        }
        int index = stepIndexOf();
        Step step = session.steps().get(index);
        session.steps().set(index, StepEditor.convert(step, StepEditor.typeOf(step).next()));
        syncFormFromSelection();
    }

    private void addSetup() {
        session.setup().add(new SetupBlock(session.origin(), "minecraft:stone"));
        entryIndex = session.setup().size() - 1;
        revealSelection();
        syncFormFromSelection();
    }

    private void addStep() {
        session.steps().add(new PlaceStep(0L, session.origin(), "minecraft:stone"));
        entryIndex = entryCount() - 1;
        revealSelection();
        syncFormFromSelection();
    }

    private void deleteEntry() {
        if (entryIndex < 0 || entryIndex >= entryCount()) {
            return;
        }
        if (isSetupSelected()) {
            session.setup().remove(entryIndex);
        } else {
            session.steps().remove(stepIndexOf());
        }
        entryIndex = Math.min(entryIndex, entryCount() - 1);
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
        syncFormFromSelection();
    }

    /** 只在同一段内换位（setup 与 steps 是两个独立列表，跨界换位没有意义）。 */
    private void moveEntry(int delta) {
        if (entryIndex < 0 || entryIndex >= entryCount()) {
            return;
        }
        if (isSetupSelected()) {
            int target = entryIndex + delta;
            if (target < 0 || target >= session.setup().size()) {
                return;
            }
            SetupBlock block = session.setup().remove(entryIndex);
            session.setup().add(target, block);
        } else {
            int index = stepIndexOf();
            int target = index + delta;
            if (target < 0 || target >= session.steps().size()) {
                return;
            }
            Step step = session.steps().remove(index);
            session.steps().add(target, step);
        }
        entryIndex += delta;
        revealSelection();
    }

    private void revealSelection() {
        int rowY = (entryIndex) * ROW_HEIGHT;
        if (rowY < listScroll) {
            listScroll = rowY;
        } else if (rowY + ROW_HEIGHT > listScroll + (listBottom() - LIST_TOP)) {
            listScroll = rowY + ROW_HEIGHT - (listBottom() - LIST_TOP);
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
    }

    // ---- 保存 ----

    private void save(boolean andRun) {
        if (leaving) {
            return;   // 自动保存返回正在进行，别让重复点击再发一次写请求
        }
        error = null;
        status = null;
        String problem = validateAll();
        if (problem != null) {
            error = problem;
            return;
        }
        long timeoutBefore = session.timeoutTicks();
        long raisedTo = prepareForSave();
        boolean timeoutRaised = raisedTo != timeoutBefore;
        String id = session.id().trim();
        // 落盘交给服务端：多人下客户端没有存档路径（见 TestEditorServer）。
        // 注意保存是异步的——写失败时界面得留着并报错，不能像以前那样立刻当成功。
        ClientTestEditor.writeTest(id, session.json(), response -> {
            if (response.isError()) {
                error = response.error();
                return;
            }
            if (!andRun) {
                status = "已保存 " + response.id() + ".json"
                        + (timeoutRaised ? "（超时自动提高到 " + raisedTo + "）" : "");
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand("wrutils test run " + id);
            }
            // 必须先关屏：shouldPause() = true 会让世界暂停，测试的 run 推进不了。
            // 但这个 close() 只在"自己还是当前屏"时才做——保存改成异步之后，响应可能晚于
            // 玩家按 ESC 到达，那时 close() 关掉的就是"别人的"屏了（同步保存时代不存在这个竞态）。
            if (client.currentScreen == this) {
                this.close();
            }
        });
    }

    /**
     * 保存前的公共准备：扩可写区 + 超时自动抬高（至少给最晚步骤，窗口断言算到窗口末端，
     * 留 100 tick；**从不降低**——用户显式填了更长超时不该被改回去）。
     *
     * @return 准备后的 {@code timeoutTicks}
     */
    private long prepareForSave() {
        session.widenInputToFit();
        long before = session.timeoutTicks();
        session.raiseTimeoutToFit();
        if (session.timeoutTicks() != before) {
            suppress = true;
            timeoutField.setText(Long.toString(session.timeoutTicks()));
            suppress = false;
        }
        return session.timeoutTicks();
    }

    /** 返回第一条问题；全通过返回 null。坏串一律不许落盘。 */
    private String validateAll() {
        String id = session.id().trim();
        if (id.isEmpty()) {
            return "id 不能为空";
        }
        if (!id.matches("[A-Za-z0-9_\\-]+")) {
            return "id 只能包含字母、数字、下划线、减号";
        }
        if (BlockStringValidator.validateNonNegativeInteger(timeoutField.getText()) != null) {
            return "超时 tick 不合法";
        }
        if (BlockStringValidator.parsePos(originField.getText()) == null) {
            return "原点不合法（应为 \"x y z\"）";
        }
        if (entryIndex >= 0 && entryIndex < entryCount()) {
            if (BlockStringValidator.parsePos(posField.getText()) == null) {
                return "选中项的坐标不合法（应为 \"x y z\"）";
            }
            if (!isSetupSelected() && BlockStringValidator.validateNonNegativeInteger(tickField.getText()) != null) {
                return "选中项的 tick 不合法";
            }
            if (mainFieldApplies) {
                // assert 的方块串**可以为空**：只查 NBT / 红石时本来就没有 block。
                // 这里必须与下面遍历模型时的规则一致（那里对 assert 明确允许空串），
                // 否则"选中它再保存"会报错、"不选中保存"却通过。
                boolean emptyAssertBlock = !isSetupSelected() && stepIndexOf() >= 0
                        && session.steps().get(stepIndexOf()) instanceof AssertStep
                        && mainField.getText().isBlank();
                if (!emptyAssertBlock) {
                    String problem = BlockStringValidator.validate(mainField.getText());
                    if (problem != null) {
                        return "选中项的方块串非法: " + problem;
                    }
                }
            }
        }
        for (int i = 0; i < session.setup().size(); i++) {
            String problem = BlockStringValidator.validate(session.setup().get(i).block());
            if (problem != null) {
                return "setup[" + i + "] 方块串非法: " + problem;
            }
        }
        for (int i = 0; i < session.steps().size(); i++) {
            Step step = session.steps().get(i);
            if (step instanceof PlaceStep place) {
                String problem = BlockStringValidator.validate(place.block());
                if (problem != null) {
                    return "steps[" + i + "] 方块串非法: " + problem;
                }
            } else if (StepEditor.typeOf(step) == StepEditor.Type.ASSERT) {
                String block = StepEditor.mainField(step);
                if (!block.isBlank()) {
                    String problem = BlockStringValidator.validate(block);
                    if (problem != null) {
                        return "steps[" + i + "] 期望方块串非法: " + problem;
                    }
                }
            }
        }
        return null;
    }

    // ---- 输入 ----

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            // 点按钮会拿到焦点、留下高亮框，所以要清；
            // 但点文本框必须**保留**焦点，否则一松手就失去焦点、没法输入。
            clearFocusNextTick = !(this.getFocused() instanceof TextFieldWidget);
            return true;
        }
        if (click.button() == 0 && click.y() >= LIST_TOP && click.y() < listBottom()) {
            int index = (int) ((click.y() - LIST_TOP + listScroll) / ROW_HEIGHT);
            if (index >= 0 && index < entryCount()) {
                entryIndex = index;
                syncFormFromSelection();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= LIST_TOP && mouseY < listBottom()) {
            int step = (int) Math.round(verticalAmount) * ROW_HEIGHT;
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll - step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---- 绘制 ----

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(this.textRenderer, TITLE + "  " + session.id() + "  (" + entryCount() + " 项)",
                MARGIN, 8, COLOR_TITLE);
        context.drawTextWithShadow(this.textRenderer, "id", MARGIN, META_ROW_1 + 4, COLOR_LABEL);
        context.drawTextWithShadow(this.textRenderer, "超时", 102, META_ROW_1 + 4, COLOR_LABEL);
        context.drawTextWithShadow(this.textRenderer, "原点", MARGIN, META_ROW_2 + 4, COLOR_LABEL);
        context.drawTextWithShadow(this.textRenderer, "描述", 128, META_ROW_2 + 4, COLOR_LABEL);

        renderEntryList(context);

        if (error != null) {
            context.drawTextWithShadow(this.textRenderer, error, MARGIN, this.height - 70, COLOR_ERROR);
        } else if (status != null) {
            context.drawTextWithShadow(this.textRenderer, status, MARGIN, this.height - 70, COLOR_OK);
        } else {
            int usable = this.width - MARGIN * 2;
            String hint = "setup[" + session.setup().size() + "] / steps[" + session.steps().size() + "]"
                    + "  选中条目后可改 tick、窗口、坐标、方块串";
            context.drawTextWithShadow(this.textRenderer, hint, MARGIN, formRowY() - 12, COLOR_LABEL);
        }
    }

    private void renderEntryList(DrawContext context) {
        int bottom = listBottom();
        context.fill(MARGIN - 2, LIST_TOP - 2, this.width - MARGIN + 2, bottom, COLOR_PANEL_BG);
        context.enableScissor(MARGIN - 2, LIST_TOP, this.width - MARGIN + 2, bottom);
        for (int i = 0; i < entryCount(); i++) {
            int rowY = LIST_TOP + i * ROW_HEIGHT - listScroll;
            if (rowY + ROW_HEIGHT <= LIST_TOP || rowY >= bottom) {
                continue;
            }
            boolean selected = i == entryIndex;
            if (selected) {
                context.fill(MARGIN - 2, rowY - 1, this.width - MARGIN + 2, rowY + ROW_HEIGHT - 1, COLOR_SELECTED_BG);
            }
            context.drawTextWithShadow(this.textRenderer, entryLabel(i), MARGIN, rowY,
                    selected ? COLOR_TITLE : COLOR_TEXT);
        }
        context.disableScissor();
    }

    private String entryLabel(int index) {
        if (index < session.setup().size()) {
            SetupBlock block = session.setup().get(index);
            return "setup[" + index + "] " + format(block.pos()) + " " + block.block();
        }
        int stepIndex = index - session.setup().size();
        Step step = session.steps().get(stepIndex);
        String head = "step[" + stepIndex + "] t" + step.tick() + " ";
        if (step instanceof PlaceStep place) {
            return head + "place " + format(place.pos()) + " " + place.block();
        }
        if (step instanceof UseStep use) {
            return head + "use " + format(use.pos()) + " " + use.side().name() + "/" + use.hand().name();
        }
        AssertStep assertStep = (AssertStep) step;
        return head + "assert" + (assertStep.withinTicks() > 0 ? "±" + assertStep.withinTicks() : "")
                + " " + format(assertStep.pos()) + " " + StepEditor.mainField(step);
    }

    private static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String formatPlain(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
