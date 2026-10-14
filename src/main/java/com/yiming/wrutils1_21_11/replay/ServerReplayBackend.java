package com.yiming.wrutils1_21_11.replay;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.nio.file.Path;
import java.util.Optional;

/**
 * ServerReplay 后端。**只走字符串命令**，因此没有 ServerReplay jar 也能编译、能运行。
 *
 * <p>两块必须记住的事实：
 * <ul>
 *   <li>只能用 {@code chunks} 模式——{@code players} 模式按在线玩家建录制器，而 {@code FakePlayer}
 *       不在 {@code PlayerManager} 里。</li>
 *   <li>命令源用 {@code server.getCommandSource().withSilent()}（OWNERS = OP 4，正好满足 ServerReplay 要求，
 *       且 {@code getServer()} 非 null）；**不要**用 {@code CommandManager.createSource(...)}。</li>
 * </ul>
 */
public final class ServerReplayBackend implements RecordingBackend {
    private final Path chunkRoot;

    public ServerReplayBackend(Path chunkRoot) {
        this.chunkRoot = chunkRoot;
    }

    @Override
    public String id() {
        return "server-replay";
    }

    @Override
    public boolean isAvailable(MinecraftServer server) {
        if (!FabricLoader.getInstance().isModLoaded("server-replay")) {
            return false;
        }
        CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
        return dispatcher != null && dispatcher.getRoot().getChild("replay") != null;
    }

    @Override
    public RecordingSession start(MinecraftServer server, RecordingTarget target) {
        run(server, "replay start chunks around " + target.centerChunkX() + " " + target.centerChunkZ()
                + " radius " + target.radiusChunks() + " in " + target.dimension() + " named " + target.label());
        return new Session(target);
    }

    private static void run(MinecraftServer server, String command) {
        ServerCommandSource source = server.getCommandSource().withSilent();
        server.getCommandManager().parseAndExecute(source, command);
    }

    private final class Session implements RecordingSession {
        private final RecordingTarget target;
        private Path produced;

        private Session(RecordingTarget target) {
            this.target = target;
        }

        @Override
        public String label() {
            return target.label();
        }

        @Override
        public void stop(MinecraftServer server, boolean keep) {
            // 通过就传 false（ServerReplay 的参数即"是否保存"），省得事后删文件、绕开落盘竞态
            run(server, "replay stop chunks named " + target.label() + (keep ? "" : " false"));
            if (keep) {
                produced = RecordingFiles.newest(chunkRoot, target.label()).orElse(null);
            } else {
                // save=false 之外的兜底：文件仍可能已落盘，交给调用方延迟清理
                RecordingFiles.deleteAll(chunkRoot, target.label());
                produced = null;
            }
        }

        @Override
        public Optional<Path> producedFile() {
            return Optional.ofNullable(produced);
        }
    }
}
