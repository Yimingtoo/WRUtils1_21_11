package com.yiming.wrutils1_21_11.net;

import com.yiming.wrutils1_21_11.test.runner.TestProgress;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：测试运行状态推送，测试在跑时每 tick 一次。
 *
 * <p>客户端不再直读 {@code WrtTestEngine} 单例——多人下那是另一个 JVM 里的静态字段，读到的
 * 永远是"没在跑"。单人下也走这条路：单人多人同一条码路，才不会出现"单人可以、多人坏"的漂移。
 *
 * <p>{@code running} 与 {@code progress} **不是冗余的**：{@code running} 对应引擎的
 * {@code isRunning()}（单跑 / 批跑队列 / 录制探测任一为真），而 {@code progress} 只在某个测试
 * 真正推进时才有值。编辑器互斥用前者、进度 HUD 用后者，少一个都会漏状态。
 *
 * <p>{@code progress} 是否为 null 另用一个布尔前缀显式编码，不靠"全零即 null"的隐式约定——
 * 那样真跑了一个 executed=0 的测试会被误读成没在跑。
 */
public record TestStatusUpdate(boolean running, TestProgress progress) implements CustomPayload {
    public static final CustomPayload.Id<TestStatusUpdate> ID =
            new CustomPayload.Id<>(Identifier.of("wrutils1_21_11", "test_status"));

    public static final PacketCodec<RegistryByteBuf, TestStatusUpdate> CODEC =
            PacketCodec.ofStatic(TestStatusUpdate::write, TestStatusUpdate::read);

    public static TestStatusUpdate idle() {
        return new TestStatusUpdate(false, null);
    }

    private static void write(RegistryByteBuf buf, TestStatusUpdate value) {
        buf.writeBoolean(value.running());
        TestProgress progress = value.progress();
        buf.writeBoolean(progress != null);
        if (progress == null) {
            return;
        }
        buf.writeString(progress.id());
        buf.writeVarInt(progress.executed());
        buf.writeVarInt(progress.totalSteps());
        buf.writeVarInt(progress.asserts());
        buf.writeVarInt(progress.failures());
        buf.writeLong(progress.tick());
        buf.writeLong(progress.timeoutTicks());
        buf.writeVarInt(progress.queued());
    }

    private static TestStatusUpdate read(RegistryByteBuf buf) {
        boolean running = buf.readBoolean();
        if (!buf.readBoolean()) {
            return new TestStatusUpdate(running, null);
        }
        return new TestStatusUpdate(running, new TestProgress(
                buf.readString(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readLong(), buf.readLong(),
                buf.readVarInt()));
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
