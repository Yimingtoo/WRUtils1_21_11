package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.test.model.Region;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 可写区管理（Phase 5.5）。{@code area.input} 可以有**多个**矩形，
 * 用于"两片互不相邻的输入区、且中间那片不想被放开"的场合。
 *
 * <p>只有这些区域里的方块能被 {@code place}/{@code use} 修改；断言位置不受限。
 * 两个角都可以拾取——{@code Region.of} 会归一化，先拾哪个角都行。
 */
public class TestAreaScreen extends Screen {
    private static final String TITLE = "WRT 可写区";
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 12;
    private static final int FIELD_HEIGHT = 16;
    private static final int LIST_TOP = 40;
    private static final int BUTTON_GAP = 4;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_LABEL = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_SELECTED_BG = 0xFF3A6EA5;
    private static final int COLOR_PANEL_BG = 0xC0000000;

    private final EditorSession session = EditorSession.get();

    private TextFieldWidget minField;
    private TextFieldWidget maxField;

    private int selected = -1;
    private int listScroll;
    private String error;
    private boolean suppress;
    private boolean clearFocusNextTick;

    public TestAreaScreen() {
        super(Text.literal(TITLE));
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    /** Esc / 「返回」都回到编辑屏。 */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void tick() {
        super.tick();
        // 同 TestEditScreen：清焦点只能延后一帧，且点文本框时不能清
        if (clearFocusNextTick) {
            clearFocusNextTick = false;
            this.setFocused(null);
        }
    }

    // ---- 布局 ----

    private List<Region> regions() {
        return session.inputs();
    }

    private int listBottom() {
        return this.height - 80;
    }

    private int formRowY() {
        return this.height - 64;
    }

    private int buttonRowY() {
        return this.height - 26;
    }

    private int visibleRows() {
        return Math.max(0, (listBottom() - LIST_TOP) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, regions().size() - visibleRows()) * ROW_HEIGHT;
    }

    // ---- 构建 ----

    @Override
    protected void init() {
        session.setInputs(regions());   // 保证 area 非 null（编辑过的测试 area 可能缺失）
        int usable = this.width - MARGIN * 2;
        int fieldWidth = (usable - BUTTON_GAP) / 2;

        minField = addDrawableChild(new TextFieldWidget(this.textRenderer, MARGIN, formRowY(),
                fieldWidth, FIELD_HEIGHT, Text.literal("min: x y z")));
        minField.setChangedListener(value -> {
            if (suppress || selected < 0 || selected >= regions().size()) {
                return;
            }
            Integer[] pos = BlockStringValidator.parsePos(value);
            minField.setEditableColor(pos == null ? COLOR_ERROR : COLOR_TEXT);
            if (pos != null) {
                session.setRegionMin(selected, new BlockPos(pos[0], pos[1], pos[2]));
            }
        });

        maxField = addDrawableChild(new TextFieldWidget(this.textRenderer,
                MARGIN + fieldWidth + BUTTON_GAP, formRowY(), fieldWidth, FIELD_HEIGHT,
                Text.literal("max: x y z")));
        maxField.setChangedListener(value -> {
            if (suppress || selected < 0 || selected >= regions().size()) {
                return;
            }
            Integer[] pos = BlockStringValidator.parsePos(value);
            maxField.setEditableColor(pos == null ? COLOR_ERROR : COLOR_TEXT);
            if (pos != null) {
                session.setRegionMax(selected, new BlockPos(pos[0], pos[1], pos[2]));
            }
        });

        int fiveWidth = (usable - BUTTON_GAP * 4) / 5;
        int x = MARGIN;
        addDrawableChild(ButtonWidget.builder(Text.literal("＋区域"), button -> {
            session.addRegion();
            selected = regions().size() - 1;
            reveal();
            syncForm();
        }).dimensions(x, buttonRowY(), fiveWidth, 20).build());
        x += fiveWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("删除"), button -> deleteRegion())
                .dimensions(x, buttonRowY(), fiveWidth, 20).build());
        x += fiveWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("拾取角1"),
                        button -> beginPick(EditorSession.PickKind.AREA_MIN))
                .dimensions(x, buttonRowY(), fiveWidth, 20).build());
        x += fiveWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("拾取角2"),
                        button -> beginPick(EditorSession.PickKind.AREA_MAX))
                .dimensions(x, buttonRowY(), fiveWidth, 20).build());
        x += fiveWidth + BUTTON_GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> back())
                .dimensions(x, buttonRowY(), fiveWidth, 20).build());

        if (selected >= regions().size()) {
            selected = regions().size() - 1;
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
        syncForm();
    }

    private TextFieldWidget add(TextFieldWidget field) {
        return addDrawableChild(field);
    }

    private void syncForm() {
        suppress = true;
        boolean has = selected >= 0 && selected < regions().size();
        if (!has) {
            minField.setText("");
            maxField.setText("");
        } else {
            Region region = regions().get(selected);
            minField.setText(formatPlain(region.min()));
            maxField.setText(formatPlain(region.max()));
        }
        setFieldEnabled(minField, has);
        setFieldEnabled(maxField, has);
        minField.setEditableColor(COLOR_TEXT);
        maxField.setEditableColor(COLOR_TEXT);
        suppress = false;
    }

    private void setFieldEnabled(TextFieldWidget field, boolean enabled) {
        field.setEditable(enabled);
        field.active = enabled;
    }

    private void deleteRegion() {
        if (selected < 0 || selected >= regions().size()) {
            return;
        }
        session.removeRegion(selected);
        selected = Math.min(selected, regions().size() - 1);
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
        syncForm();
    }

    private void beginPick(EditorSession.PickKind kind) {
        if (selected < 0 || selected >= regions().size()) {
            error = "先在列表里选中一个区域，再拾取它的角";
            return;
        }
        session.beginPick(kind, selected);
        error = null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "[WRT] 准星对准方块，按「拾取方块」键设为该角（默认 P）；两个角先拾哪个都行"), false);
        }
        client.setScreen(null);
    }

    private void back() {
        this.client.setScreen(new TestEditScreen());
    }

    private void reveal() {
        int rowY = selected * ROW_HEIGHT;
        int height = listBottom() - LIST_TOP;
        if (rowY < listScroll) {
            listScroll = rowY;
        } else if (rowY + ROW_HEIGHT > listScroll + height) {
            listScroll = rowY + ROW_HEIGHT - height;
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
    }

    // ---- 输入 ----

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            clearFocusNextTick = !(this.getFocused() instanceof TextFieldWidget);
            return true;
        }
        if (click.button() == 0 && click.y() >= LIST_TOP && click.y() < listBottom()) {
            int index = (int) ((click.y() - LIST_TOP + listScroll) / ROW_HEIGHT);
            if (index >= 0 && index < regions().size()) {
                selected = index;
                syncForm();
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
        context.drawTextWithShadow(this.textRenderer,
                TITLE + "  " + session.id() + "  (" + regions().size() + " 个区域)", MARGIN, 8, COLOR_TITLE);
        context.drawTextWithShadow(this.textRenderer,
                "只有这些区域里的方块可被 place/use 修改；断言位置不受限", MARGIN, 22, COLOR_LABEL);

        int bottom = listBottom();
        context.fill(MARGIN - 2, LIST_TOP - 2, this.width - MARGIN + 2, bottom, COLOR_PANEL_BG);
        context.enableScissor(MARGIN - 2, LIST_TOP, this.width - MARGIN + 2, bottom);
        if (regions().isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "（没有可写区：任何 place/use 都会被拒绝）",
                    MARGIN, LIST_TOP + 2, COLOR_ERROR);
        }
        for (int i = 0; i < regions().size(); i++) {
            int rowY = LIST_TOP + i * ROW_HEIGHT - listScroll;
            if (rowY + ROW_HEIGHT <= LIST_TOP || rowY >= bottom) {
                continue;
            }
            boolean isSelected = i == selected;
            if (isSelected) {
                context.fill(MARGIN - 2, rowY - 1, this.width - MARGIN + 2, rowY + ROW_HEIGHT - 1,
                        COLOR_SELECTED_BG);
            }
            context.drawTextWithShadow(this.textRenderer, describe(i), MARGIN, rowY,
                    isSelected ? COLOR_TITLE : COLOR_TEXT);
        }
        context.disableScissor();

        if (error != null) {
            context.drawTextWithShadow(this.textRenderer, error, MARGIN, formRowY() - 12, COLOR_ERROR);
        }
    }

    private String describe(int index) {
        Region region = regions().get(index);
        BlockPos min = region.min();
        BlockPos max = region.max();
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        return "area[" + index + "]  (" + min.getX() + ", " + min.getY() + ", " + min.getZ() + ") .. ("
                + max.getX() + ", " + max.getY() + ", " + max.getZ() + ")   体积 " + volume;
    }

    private static String formatPlain(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
