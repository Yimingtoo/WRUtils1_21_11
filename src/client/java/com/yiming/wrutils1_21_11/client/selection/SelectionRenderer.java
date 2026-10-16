package com.yiming.wrutils1_21_11.client.selection;

import com.yiming.wrutils1_21_11.command.SelectionManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * 把 {@link SelectionManager} 里的选区画成线框，方便木斧选点时肉眼确认范围。
 *
 * <p>挂 {@link WorldRenderEvents#BEFORE_DEBUG_RENDER}——原版文档给它的定位正是
 * "use to draw lines, overlays and other content similar to vanilla debug renders"。
 *
 * <p>坐标约定抄原版 {@code WorldRenderer.drawBlockOutline}：{@code VoxelShape} 用<b>绝对</b>方块坐标，
 * drawOutline 的偏移量传 {@code -相机位置}；顶点须相对相机，才能与渲染器其余内容一致。
 */
public final class SelectionRenderer {
    private static final int COLOR = 0xFF00E5FF;
    private static final float LINE_WIDTH = 2.0F;

    private SelectionRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(SelectionRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        SelectionManager.Selection selection = SelectionManager.get().get(client.player.getUuid());
        if (!selection.isComplete()) {
            return;
        }
        BlockPos min = BlockPos.min(selection.pos1(), selection.pos2());
        BlockPos max = BlockPos.max(selection.pos1(), selection.pos2());
        // 方块坐标含两端，故上界 +1 才包住最后一格
        Box box = new Box(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        VertexConsumer vertices = context.consumers().getBuffer(RenderLayers.lines());
        VertexRendering.drawOutline(context.matrices(), vertices, VoxelShapes.cuboid(box),
                -camera.x, -camera.y, -camera.z, COLOR, LINE_WIDTH);
    }
}
