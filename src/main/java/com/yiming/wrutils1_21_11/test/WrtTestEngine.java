package com.yiming.wrutils1_21_11.test;

import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.command.WrtTestCommand;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import com.yiming.wrutils1_21_11.test.runner.RunState;
import com.yiming.wrutils1_21_11.test.runner.TestRun;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;

public final class WrtTestEngine {
    private static final WrtTestEngine INSTANCE = new WrtTestEngine();

    private MinecraftServer server;
    private TestRun currentRun;

    private WrtTestEngine() {
    }

    public static WrtTestEngine get() {
        return INSTANCE;
    }

    public static void bootstrap() {
        ServerLifecycleEvents.SERVER_STARTED.register(INSTANCE::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCE::onServerStopped);
        ServerLifecycleEvents.SERVER_STOPPING.register(INSTANCE::onServerStopping);
        ServerTickEvents.END_WORLD_TICK.register(INSTANCE::onEndWorldTick);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> WrtTestCommand.register(dispatcher));
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean startRun(TestDefinition definition, ServerCommandSource feedback, ServerWorld world) {
        if (currentRun != null) {
            return false;
        }
        currentRun = new TestRun(world, definition, feedback);
        WrtLog.LOGGER.info("[WRT] started {} in {}", definition.id(), world.getRegistryKey().getValue());
        return true;
    }

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        WrtLog.LOGGER.info("WrtTestEngine attached");
    }

    private void onServerStopping(MinecraftServer server) {
        // 停机前必须还原 gamerule / 强制区块，否则会写进存档
        if (currentRun != null) {
            currentRun.forceRestore();
        }
    }

    private void onServerStopped(MinecraftServer server) {
        this.server = null;
        this.currentRun = null;
    }

    private void onEndWorldTick(ServerWorld world) {
        if (currentRun == null || currentRun.world() != world) {
            return;
        }
        currentRun.tick();
        if (currentRun.state() != RunState.RUNNING) {
            currentRun = null;
        }
    }
}
