package com.yiming.wrutils1_21_11.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * 编辑器一次文件操作的类别。请求与响应共用同一个枚举，客户端据此把响应配对回自己发出的那条请求。
 *
 * <p>只有"列 / 读 / 写"三种：编辑器**没有删除测试文件的功能**（界面上的两个"删除"删的都是内存里的
 * 条目与可写区），所以不需要删的类别。
 */
public enum TestEditorOp {
    /** 列出 tests 目录下全部测试 id。 */
    LIST,
    /** 读单个测试定义，响应带 JSON。 */
    READ,
    /** 写单个测试定义，请求带 JSON。 */
    WRITE;

    public static final PacketCodec<ByteBuf, TestEditorOp> CODEC =
            PacketCodecs.STRING.xmap(TestEditorOp::valueOf, TestEditorOp::name);
}
