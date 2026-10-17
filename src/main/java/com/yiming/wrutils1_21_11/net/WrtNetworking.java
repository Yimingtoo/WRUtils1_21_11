package com.yiming.wrutils1_21_11.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 在**两端**都注册 payload 类型。
 *
 * <p>必须从 common 源集调用（{@code Wrutils1_21_11#onInitialize}）：类型注册只写进本进程的
 * 编解码表，客户端不解开服务端发来的包。
 *
 * <p>接收器不在这里，两侧各注册自己那半边：服务端在 {@link TestEditorServer}，
 * 客户端在 client 源集的 {@code ClientWrtNetworking}。放在一起会让客户端侧误加载服务端类。
 */
public final class WrtNetworking {
    private WrtNetworking() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(TestEditorRequest.ID, TestEditorRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectionUpdate.ID, SelectionUpdate.CODEC);
        PayloadTypeRegistry.playS2C().register(TestEditorResponse.ID, TestEditorResponse.CODEC);
        PayloadTypeRegistry.playS2C().register(TestStatusUpdate.ID, TestStatusUpdate.CODEC);
    }
}
