package com.yiming.wrutils1_21_11.mixin.client;

import com.yiming.wrutils1_21_11.client.Wrutils1_21_11Client;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让掉落物在头顶显示其实体数字 ID，可用 I 键开关。
 *
 * <p>原版 {@code EntityRenderer.updateRenderState} 只在 {@code hasLabel(...)} 成立时才写入
 * {@code state.displayName} / {@code state.nameLabelPos}，掉落物默认不满足该条件，因此默认不显示名字。
 * 而 {@code EntityRenderer.render} 是无条件调用 {@code renderLabelIfPresent} 的，后者只判断
 * {@code displayName != null}。所以这里在渲染状态更新完成后补写这两个字段，即可复用原版的整条标签
 * 渲染链路（billboard、缩放、背景、光照），无需自行绘制文本。
 */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void wrutils$showEntityId(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (!Wrutils1_21_11Client.showItemEntityId) {
            return;
        }
        Vec3d labelPos = entity.getAttachments()
                .getPointNullable(EntityAttachmentType.NAME_TAG, 0, entity.getLerpedYaw(tickDelta));
        if (labelPos != null) {
            state.displayName = Text.literal(String.valueOf(entity.getId()));
            state.nameLabelPos = labelPos;
        }
    }
}
