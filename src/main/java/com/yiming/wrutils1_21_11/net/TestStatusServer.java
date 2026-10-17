package com.yiming.wrutils1_21_11.net;

import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import com.yiming.wrutils1_21_11.test.runner.TestProgress;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 每 tick 把 {@code WrtTestEngine} 的运行状态推给全部客户端。
 *
 * <p>原本客户端直读引擎单例（单人下同 JVM 所以能读到）。多人下那是另一个进程，读到的永远是
 * "没在跑"——编辑器那道互斥闸形同虚设、进度 HUD 永远不出现。单人下也走这条路，单人多人
 * 同一条码路，才不会出现"单人可以、多人坏"的漂移。
 *
 * <p>挂在 {@link ServerTickEvents#END_SERVER_TICK} 而不是引擎自己的 {@code END_WORLD_TICK}：
 * 后者**每个维度各触发一次**（默认三个世界就是三次），在这里广播会发三遍。
 */
public final class TestStatusServer {
    /** 上一次广播的 running，用来把"空闲→空闲"的 tick 全部省掉。 */
    private static boolean lastRunning;

    private TestStatusServer() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TestStatusServer::onEndTick);
    }

    private static void onEndTick(MinecraftServer server) {
        WrtTestEngine engine = WrtTestEngine.get();
        boolean running = engine.isRunning();
        // 空闲且上次也空闲 → 不发。状态没变就没有流量；一旦跑起来每 tick 都发，
        // 中途加入的玩家下一 tick 自然收到，不需要为"玩家加入"单独处理。
        if (!running && !lastRunning) {
            return;
        }
        lastRunning = running;
        TestProgress progress = running ? engine.progress() : null;
        TestStatusUpdate update = new TestStatusUpdate(running, progress);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, update);
        }
    }
}
