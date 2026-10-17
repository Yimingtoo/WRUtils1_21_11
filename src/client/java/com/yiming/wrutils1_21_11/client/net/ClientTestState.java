package com.yiming.wrutils1_21_11.client.net;

import com.yiming.wrutils1_21_11.net.TestStatusUpdate;
import com.yiming.wrutils1_21_11.test.runner.TestProgress;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端侧的测试运行状态镜像，由服务端每 tick 推送。
 *
 * <p>替代原先"客户端直读 {@code WrtTestEngine} 单例"的写法——那个只在单人（客户端与服务端
 * 同 JVM）成立，多人下读到的是另一个进程里恒为空的字段。
 *
 * <p>整份快照存进**一个** volatile 字段，而不是拆成 running / progress 两个字段分别更新：
 * 分两次写，读的人就可能撞见"running=true 但 progress 还是上一轮的"，而进度 HUD 恰好同时读这两个。
 */
public final class ClientTestState {
    private static volatile TestStatusUpdate state = TestStatusUpdate.idle();

    private ClientTestState() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(TestStatusUpdate.ID, (update, context) ->
                context.client().execute(() -> state = update));
    }

    /**
     * 连接建立 / 断开时复位，由 {@code Wrutils1_21_11Client} 挂的连接事件调用。
     *
     * <p><b>这一步不是保险，是必需</b>：本镜像只在**收到推送**时才更新，而服务端是**边沿触发**
     * 广播（空闲→空闲不发）。于是"最后收到的那条是 running=true、却在测试结束前就断了线"的客户端
     * 会永久卡在"正在跑测试"——燧石选不了点、编辑器打不开，而选区 HUD（它不查 isRunning）
     * 照旧显示"开启"。复位到 idle 即可闭合这个缺口：测试真在跑时服务端**每 tick** 都广播，
     * 新连上的客户端一 tick 内就会同步到真值。
     */
    public static void reset() {
        state = TestStatusUpdate.idle();
    }

    /** 对应服务端 {@code WrtTestEngine.isRunning()}：单跑 / 批跑队列 / 录制探测任一进行中。 */
    public static boolean isRunning() {
        return state.running();
    }

    /** 正在跑的测试的进度快照；没在跑时为 null（与服务端同名方法的语义一致）。 */
    public static TestProgress progress() {
        return state.progress();
    }
}
