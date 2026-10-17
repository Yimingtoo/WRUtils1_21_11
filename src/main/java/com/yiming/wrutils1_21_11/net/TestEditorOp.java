package com.yiming.wrutils1_21_11.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * 编辑器一次服务端操作的类别。请求与响应共用同一个枚举，客户端据此把响应配对回自己发出的那条请求。
 *
 * <p>文件操作有"列 / 读 / 写 / 删"四种；{@link #SELECTION} 用于读取服务端权威的选区。
 */
public enum TestEditorOp {
    /** 列出 tests 目录下全部测试 id。 */
    LIST,
    /** 读单个测试定义，响应带 JSON。 */
    READ,
    /** 写单个测试定义，请求带 JSON。 */
    WRITE,
    /** 删除单个测试文件；请求带 id。 */
    DELETE,
    /** 读取服务端保存的当前选区；响应把 "x1 y1 z1 x2 y2 z2" 放在 json 字段，空串表示未选。 */
    SELECTION;

    public static final PacketCodec<ByteBuf, TestEditorOp> CODEC =
            PacketCodecs.STRING.xmap(TestEditorOp::valueOf, TestEditorOp::name);
}
