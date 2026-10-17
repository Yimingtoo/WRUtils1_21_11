package com.yiming.wrutils1_21_11.client.editor;

import com.yiming.wrutils1_21_11.client.net.ClientTestEditor;
import com.yiming.wrutils1_21_11.command.SelectionManager;
import com.yiming.wrutils1_21_11.test.io.TestDefinitionLoader;
import com.yiming.wrutils1_21_11.test.model.AssertStep;
import com.yiming.wrutils1_21_11.test.model.PlaceStep;
import com.yiming.wrutils1_21_11.test.model.Region;
import com.yiming.wrutils1_21_11.test.model.SetupBlock;
import com.yiming.wrutils1_21_11.test.model.Step;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.model.UseStep;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内测试编辑器（Phase 5，当前为只读阶段）。
 *
 * <p>左列是测试列表（可滚轮滚动），右列是选中测试的详情。列表**自绘 + 手动命中检测**，不用原版的
 * {@code EntryListWidget} 系：1.21.11 那套控件的抽象方法（{@code selectableChildren()} 等）改动频繁，
 * 自绘只依赖 {@link DrawContext} 与 {@link Click}，代价更低。
 *
 * <p>两列都套 {@code enableScissor}，内容再长也不会画到界面外；宽度全部由 {@code this.width} 算出。
 *
 * <p><b>文字颜色一律用不透明 ARGB（{@code 0xFF……}）</b>：这个版本的文字渲染会尊重 alpha 分量，
 * 只写 {@code 0xFFFFFF} 的话 alpha=0，文字会完全看不见。
 */
public class TestEditorScreen extends Screen {
    private static final String TITLE = "WRT 测试编辑器";
    private static final int ROW_TOP = 34;
    private static final int ROW_HEIGHT = 12;
    private static final int BOTTOM_RESERVED = 34;
    private static final int MIN_LIST_WIDTH = 110;
    private static final int MAX_LIST_WIDTH = 190;

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HEADER = 0xFFFFFF55;
    private static final int COLOR_HINT = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_SELECTED_BG = 0xFF3A6EA5;
    private static final int COLOR_PANEL_BG = 0xC0000000;
    private static final int COLOR_SCROLLBAR = 0xFFFFFFFF;

    private final List<String> ids = new ArrayList<>();
    /** 从编辑屏返回时带上原来编辑的 id，让列表保留选中项。 */
    private final String initialSelection;
    private int selected = -1;
    private int scroll;
    private TestDefinition current;
    private String error;
    /** 有请求在途；驱动详情栏显示"加载中…"而不是误导性的"点击左侧选择一个测试"。 */
    private boolean loading;
    /** 每发起一次列表请求自增，回调据此丢弃过期响应（见 {@link #refresh()}）。 */
    private int listGeneration;
    /** 焦点是在按钮回调之后才被 ParentElement 设置上去的，只能延后一帧清（见 {@link #tick()}）。 */
    private boolean clearFocusNextTick;

    public TestEditorScreen() {
        this(null);
    }

    public TestEditorScreen(String initialSelection) {
        super(Text.literal(TITLE));
        this.initialSelection = initialSelection;
    }

    /** 编辑时暂停世界；因此"运行"必须先关屏，否则 run 永远等不到 tick。 */
    @Override
    public boolean shouldPause() {
        return true;
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

    @Override
    protected void init() {
        refresh();
        int gap = 4;
        int count = 5;
        int available = this.width - detailLeft() - gap * (count - 1) - 4;
        int buttonWidth = Math.max(28, available / count);
        int x = detailLeft();
        int y = this.height - 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("新建"), button -> createNew())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("编辑"), button -> editSelected())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("运行"), button -> runSelected())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        // 只重扫列表、保留选中项——不重建控件，避开 clearAndInit()
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新"), button -> refresh())
                .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> this.close())
                .dimensions(x, y, buttonWidth, 20).build());
    }

    /** 把选中的测试读进工作副本，打开整窗编辑屏。 */
    private void editSelected() {
        if (selected < 0 || selected >= ids.size()) {
            return;
        }
        String id = ids.get(selected);
        ClientTestEditor.readTest(id, response -> {
            if (response.isError()) {
                error = response.error();
                return;
            }
            try {
                EditorSession.get().load(TestDefinitionLoader.parse(response.json(), response.id()));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                return;
            }
            // 只在自己还是当前屏时才跳转：读定义是异步的，响应可能晚于玩家按 ESC 到达，
            // 那时 setScreen 会把编辑屏盖到人家正在看的界面上面。
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == this) {
                client.setScreen(new TestEditScreen());
            }
        });
    }

    /**
     * 新建一个空测试：id 取一个没被占用的默认名，进编辑屏后可改。
     *
     * <p>可写区优先取**燧石选中的范围**——两条新建入口（这里和 {@code /wrutils test new}）应当同义，
     * 否则"圈好了范围、点新建却建在脚底下"很费解。没选过（或只选了一端）才退回玩家脚下那一格。
     */
    private void createNew() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        String id = "untitled";
        for (int i = 2; ids.contains(id); i++) {
            id = "untitled" + i;
        }
        String dimension = client.player.getEntityWorld().getRegistryKey().getValue().toString();
        SelectionManager.Selection selection = SelectionManager.get().get(client.player.getUuid());
        EditorSession session = EditorSession.get();
        if (selection.isComplete()) {
            // Region.of 会归一化两角，谁先谁后都行；origin 取 min 角，与 /wrutils test new 一致
            Region region = Region.of(selection.pos1(), selection.pos2());
            session.reset(id, dimension, region.min(), List.of(region));
        } else {
            session.reset(id, dimension, client.player.getBlockPos());
        }
        client.setScreen(new TestEditScreen());
    }

    // ---- 布局 ----

    private int listWidth() {
        return Math.max(MIN_LIST_WIDTH, Math.min(MAX_LIST_WIDTH, this.width / 4));
    }

    private int detailLeft() {
        return listWidth() + 12;
    }

    private int listTop() {
        return ROW_TOP;
    }

    private int listBottom() {
        return this.height - BOTTOM_RESERVED;
    }

    private int visibleRows() {
        return Math.max(0, (listBottom() - listTop()) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, ids.size() - visibleRows()) * ROW_HEIGHT;
    }

    // ---- 数据 ----

    private void refresh() {
        String keep = selected >= 0 && selected < ids.size() ? ids.get(selected) : null;
        current = null;
        error = null;
        loading = true;
        int generation = ++listGeneration;
        // 关键：ids 只在回调里清空重建，不在这里就能避免刷新时列表闪空。
        // 但因此必须挡住过期响应——连点"刷新"、或窗口 resize 触发 init()，都会有多条在途的 LIST，
        // 旧的后到就会把列表追加成两份。
        ClientTestEditor.listTests(response -> {
            if (generation != listGeneration) {
                return;
            }
            loading = false;
            if (response.isError()) {
                error = response.error();
                return;
            }
            ids.clear();
            ids.addAll(response.ids());
            selected = keep == null ? -1 : ids.indexOf(keep);
            if (selected < 0 && initialSelection != null) {
                selected = ids.indexOf(initialSelection);
            }
            scrollToSelected();
            loadSelected();
        });
    }

    private void loadSelected() {
        current = null;
        if (selected < 0 || selected >= ids.size()) {
            return;
        }
        String id = ids.get(selected);
        error = null;
        loading = true;
        ClientTestEditor.readTest(id, response -> {
            loading = false;
            if (response.isError()) {
                error = response.error();
                return;
            }
            // 响应回来时选中项可能已经变了。连点两下会发出两条在途的 READ，回来顺序没有保证，
            // 不挡的话会把 A 的定义画成 B 的。requestId 只保证"响应找得到自己的回调"，挡不住这个。
            if (selected < 0 || selected >= ids.size() || !ids.get(selected).equals(response.id())) {
                return;
            }
            try {
                current = TestDefinitionLoader.parse(response.json(), response.id());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
    }

    private void scrollToSelected() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private void select(int index) {
        selected = index;
        loadSelected();
    }

    private void runSelected() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (selected < 0 || selected >= ids.size() || client.getNetworkHandler() == null) {
            return;
        }
        // sendChatCommand 不带前导 '/'
        client.getNetworkHandler().sendChatCommand("wrutils test run " + ids.get(selected));
        this.close();
    }

    // ---- 输入 ----

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            clearFocusNextTick = true;
            return true;
        }
        if (click.button() == 0 && click.x() >= 0 && click.x() < listWidth()
                && click.y() >= listTop() && click.y() < listBottom()) {
            int index = (int) ((click.y() - listTop() + scroll) / ROW_HEIGHT);
            if (index >= 0 && index < ids.size()) {
                select(index);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= 0 && mouseX < listWidth() && mouseY >= listTop() && mouseY < listBottom()) {
            int step = (int) Math.round(verticalAmount) * ROW_HEIGHT;
            scroll = Math.max(0, Math.min(maxScroll(), scroll - step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---- 绘制 ----

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(this.textRenderer, TITLE + "  (" + ids.size() + " 个测试)", 8, 8, COLOR_TITLE);

        int listWidth = listWidth();
        int top = listTop();
        int bottom = listBottom();
        context.fill(0, top - 2, listWidth, bottom, COLOR_PANEL_BG);

        context.enableScissor(0, top, listWidth, bottom);
        for (int i = 0; i < ids.size(); i++) {
            int rowY = top + i * ROW_HEIGHT - scroll;
            if (rowY + ROW_HEIGHT <= top || rowY >= bottom) {
                continue;
            }
            boolean isSelected = i == selected;
            if (isSelected) {
                context.fill(0, rowY - 1, listWidth, rowY + ROW_HEIGHT - 1, COLOR_SELECTED_BG);
            }
            context.drawTextWithShadow(this.textRenderer, ids.get(i), 6, rowY,
                    isSelected ? COLOR_TITLE : COLOR_TEXT);
        }
        context.disableScissor();

        if (maxScroll() > 0) {
            int trackTop = top;
            int trackHeight = bottom - top;
            int barHeight = Math.max(10, trackHeight * trackHeight / (ids.size() * ROW_HEIGHT));
            int barTop = trackTop + (trackHeight - barHeight) * scroll / maxScroll();
            context.fill(listWidth - 3, barTop, listWidth - 1, barTop + barHeight, COLOR_SCROLLBAR);
        }

        context.enableScissor(detailLeft(), top, this.width, bottom);
        renderDetails(context);
        context.disableScissor();
    }

    private void renderDetails(DrawContext context) {
        int y = listTop();
        if (current == null) {
            String message = error != null ? error : (loading ? "加载中…" : "点击左侧选择一个测试");
            context.drawTextWithShadow(this.textRenderer, message, detailLeft(), y,
                    error != null ? COLOR_ERROR : COLOR_HINT);
            return;
        }
        y = line(context, y, current.id() + "  " + current.description(), COLOR_HEADER);
        y = line(context, y, "dimension=" + current.dimension() + "  origin=" + format(current.origin())
                + "  timeout=" + current.timeoutTicks() + "  record=" + current.record(), COLOR_TEXT);
        y = line(context, y, "area=" + (current.area() == null ? "<无>" : current.area().toString()), COLOR_TEXT);
        y += 4;

        y = line(context, y, "setup (" + current.setup().size() + ")", COLOR_HEADER);
        for (int i = 0; i < current.setup().size(); i++) {
            SetupBlock block = current.setup().get(i);
            y = line(context, y, "  [" + i + "] " + format(block.pos()) + " " + block.block(), COLOR_TEXT);
        }
        y += 4;

        y = line(context, y, "steps (" + current.steps().size() + ")", COLOR_HEADER);
        for (int i = 0; i < current.steps().size(); i++) {
            y = line(context, y, "  [" + i + "] " + describe(current.steps().get(i)), COLOR_TEXT);
        }
    }

    private int line(DrawContext context, int y, String text, int color) {
        if (y <= listBottom()) {
            context.drawTextWithShadow(this.textRenderer, text, detailLeft(), y, color);
        }
        return y + 10;
    }

    private static String describe(Step step) {
        if (step instanceof PlaceStep place) {
            return "t" + place.tick() + " place " + format(place.pos()) + " " + place.block();
        }
        if (step instanceof UseStep use) {
            return "t" + use.tick() + " use " + format(use.pos())
                    + " side=" + use.side().name() + " hand=" + use.hand().name();
        }
        if (step instanceof AssertStep assertStep) {
            return "t" + assertStep.tick() + " assert " + format(assertStep.pos()) + " " + assertStep.expect();
        }
        return step.toString();
    }

    private static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
