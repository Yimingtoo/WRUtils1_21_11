package com.yiming.wrutils1_21_11.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端 → 服务端：测试编辑器的一次服务端操作。
 *
 * <p>这些操作合成一个包而不是各自拆成类型，是因为它们在服务端**共用同一段权限校验**，
 * 文件操作还共用 id 白名单。拆开就得把校验抄多遍，而漏抄任何一遍都是安全漏洞。
 * 服务端一个 {@code switch(op)} 把校验写在前面，漏不掉。
 *
 * <p>{@code id} / {@code json} 缺省时填空串，**不传 null**——{@link PacketCodecs#STRING} 遇到 null 会 NPE。
 *
 * <p>{@code requestId} 由客户端自增分配，响应原样带回。没有它的话，连点两个测试会发出两条
 * 在途的 READ，谁先回来不一定，右侧详情就会显示错的那条。
 *
 * <p>本包**未**用 {@code registerLarge} 注册。Fabric 的分包阈值双向不同：S2C 是 1 MB，
 * **C2S 只有 32767 字节**。测试定义 JSON 是 KB 级，正常远够；真写了个超大的定义，
 * Fabric 也会自动分包，不会失败。
 */
public record TestEditorRequest(int requestId, TestEditorOp op, String id, String json)
        implements CustomPayload {
    public static final CustomPayload.Id<TestEditorRequest> ID =
            new CustomPayload.Id<>(Identifier.of("wrutils1_21_11", "test_editor_request"));

    public static final PacketCodec<RegistryByteBuf, TestEditorRequest> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TestEditorRequest::requestId,
            TestEditorOp.CODEC, TestEditorRequest::op,
            PacketCodecs.STRING, TestEditorRequest::id,
            PacketCodecs.STRING, TestEditorRequest::json,
            TestEditorRequest::new);

    /** 列目录。 */
    public static TestEditorRequest list(int requestId) {
        return new TestEditorRequest(requestId, TestEditorOp.LIST, "", "");
    }

    /** 读单个定义。 */
    public static TestEditorRequest read(int requestId, String id) {
        return new TestEditorRequest(requestId, TestEditorOp.READ, id, "");
    }

    /** 写单个定义。 */
    public static TestEditorRequest write(int requestId, String id, String json) {
        return new TestEditorRequest(requestId, TestEditorOp.WRITE, id, json);
    }

    /** 删除单个定义。 */
    public static TestEditorRequest delete(int requestId, String id) {
        return new TestEditorRequest(requestId, TestEditorOp.DELETE, id, "");
    }

    /** 读取服务端当前选区。 */
    public static TestEditorRequest selection(int requestId) {
        return new TestEditorRequest(requestId, TestEditorOp.SELECTION, "", "");
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
