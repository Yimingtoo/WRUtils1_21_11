package com.yiming.wrutils1_21_11.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端 → 服务端：燧石选区工具选到了一个点。
 *
 * <p>选区状态原本只有一份（{@code SelectionManager} 单例），单人下客户端与服务端同 JVM
 * 所以直接写就行。多人下客户端写进的是**自己这个 JVM** 的副本，服务端那份纹丝不动，
 * 于是 {@code /wrutils test new} 仍然报"请先设置选区"。
 *
 * <p>客户端**同时**保留自己那份本地写入——线框要即时画出来，等一个来回会有肉眼可见的延迟。
 * 代价是两份副本理论上可能漂移（玩家若改敲 {@code /wrutils sel pos1}，线框不会跟着变），
 * 这是刻意换来的手感。
 */
public record SelectionUpdate(boolean pos1, BlockPos pos) implements CustomPayload {
    public static final CustomPayload.Id<SelectionUpdate> ID =
            new CustomPayload.Id<>(Identifier.of("wrutils1_21_11", "selection_update"));

    public static final PacketCodec<RegistryByteBuf, SelectionUpdate> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, SelectionUpdate::pos1,
            BlockPos.PACKET_CODEC, SelectionUpdate::pos,
            SelectionUpdate::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
