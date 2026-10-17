package com.yiming.wrutils1_21_11.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：{@link TestEditorRequest} 的结果。
 *
 * <p>按 {@code op} 取用对应字段，其余一律是空值（空列表 / 空串），**不传 null**。
 * {@code error} 非空即失败，客户端应显示它而不是当作成功（尤其是 WRITE——写失败却提示
 * "已保存"是最糟的失败方式）。
 */
public record TestEditorResponse(int requestId, TestEditorOp op,
                                 List<String> ids, String id, String json, String error)
        implements CustomPayload {
    public static final CustomPayload.Id<TestEditorResponse> ID =
            new CustomPayload.Id<>(Identifier.of("wrutils1_21_11", "test_editor_response"));

    /** 目录内容的编解码：解码出的 ArrayList 用 {@code List.copyOf} 收成不可变列表。 */
    private static final PacketCodec<RegistryByteBuf, List<String>> IDS =
            PacketCodecs.<RegistryByteBuf, String, ArrayList<String>>collection(ArrayList::new, PacketCodecs.STRING)
                    .xmap(List::copyOf, ArrayList::new);

    public static final PacketCodec<RegistryByteBuf, TestEditorResponse> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TestEditorResponse::requestId,
            TestEditorOp.CODEC, TestEditorResponse::op,
            IDS, TestEditorResponse::ids,
            PacketCodecs.STRING, TestEditorResponse::id,
            PacketCodecs.STRING, TestEditorResponse::json,
            PacketCodecs.STRING, TestEditorResponse::error,
            TestEditorResponse::new);

    public static TestEditorResponse ids(int requestId, List<String> ids) {
        return new TestEditorResponse(requestId, TestEditorOp.LIST, ids, "", "", "");
    }

    public static TestEditorResponse definition(int requestId, String id, String json) {
        return new TestEditorResponse(requestId, TestEditorOp.READ, List.of(), id, json, "");
    }

    public static TestEditorResponse saved(int requestId, String id) {
        return new TestEditorResponse(requestId, TestEditorOp.WRITE, List.of(), id, "", "");
    }

    public static TestEditorResponse failed(int requestId, TestEditorOp op, String error) {
        return new TestEditorResponse(requestId, op, List.of(), "", "", error);
    }

    public boolean isError() {
        return !error.isEmpty();
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
