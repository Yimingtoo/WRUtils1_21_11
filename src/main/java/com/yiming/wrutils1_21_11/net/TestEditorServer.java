package com.yiming.wrutils1_21_11.net;

import com.yiming.wrutils1_21_11.WrtLog;
import com.yiming.wrutils1_21_11.command.SelectionManager;
import com.yiming.wrutils1_21_11.test.io.TestDefinitionLoader;
import com.yiming.wrutils1_21_11.test.io.TestDefinitionWriter;
import com.yiming.wrutils1_21_11.test.io.TestPaths;
import com.yiming.wrutils1_21_11.test.model.TestDefinition;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服务端侧的接收器：编辑器的文件操作 + 燧石选区上报。
 *
 * <p><b>为什么文件 IO 要搬到这里</b>：多人下客户端没有服务端存档的路径，{@code client.getServer()}
 * 恒为 null。写文件必须由真正持有存档的那个进程做。{@code TestPaths} / loader / writer 原本就在
 * {@code src/main}，所以只是把调用方挪了个位置，IO 代码一行没改。
 *
 * <p><b>下面两条校验是"新增"的，不是"防御性编程"</b>：payload 的内容完全由客户端决定，而服务端
 * 此前从不校验任何客户端输入——因为此前根本没有客户端输入。
 * <ul>
 *   <li>{@link #requirePermission(String)} 挡权限：不挡的话任何玩家都能往服务端存档里写文件；</li>
 *   <li>{@link #requireValidId} 挡 id：{@code "../../server.properties"} 是个完全合法的字符串，
 *       它会顺着 {@code testsDir.resolve(id + ".json")} 逃出 tests 目录。</li>
 * </ul>
 */
public final class TestEditorServer {
    /** 与客户端 {@code TestEditScreen.validateAll} 用的是同一条正则，别让两侧的合法集不一致。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+");

    private TestEditorServer() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(TestEditorRequest.ID, TestEditorServer::onRequest);
        ServerPlayNetworking.registerGlobalReceiver(SelectionUpdate.ID, TestEditorServer::onSelection);
    }

    private static void onRequest(TestEditorRequest request, ServerPlayNetworking.Context context) {
        MinecraftServer server = context.server();
        ServerPlayerEntity player = context.player();
        // Fabric 在非主线程时代为切到服务端主线程；这里显式包一层是兜底，而身处主线程时
        // execute() 立即执行，不引入额外延迟。文件 IO 只该在服务端主线程做。
        server.execute(() -> ServerPlayNetworking.send(player, handle(server, player, request)));
    }

    private static TestEditorResponse handle(MinecraftServer server, ServerPlayerEntity player,
                                             TestEditorRequest request) {
        int requestId = request.requestId();
        TestEditorOp op = request.op();
        try {
            // READ 也要挡：定义里可能带着作者机器的结构，不该给无权限的玩家看
            requirePermission(player);
            return switch (op) {
                case LIST -> TestEditorResponse.ids(requestId, listTests(server));
                case READ -> readDefinition(server, requestId, request.id());
                case WRITE -> writeDefinition(server, player, requestId, request.id(), request.json());
            };
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return TestEditorResponse.failed(requestId, op, message);
        }
    }

    /**
     * 权限门槛必须与 {@code /wrutils} 命令一致——命令挂的是 {@link CommandManager#GAMEMASTERS_CHECK}。
     * 不一致的话，非 op 玩家能绕过命令权限限制，直接经编辑器读写服务端存档。
     */
    private static void requirePermission(ServerPlayerEntity player) {
        if (!CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions())) {
            throw new IllegalStateException("需要 OP 权限（与 /wrutils 命令一致）");
        }
    }

    /** id 会被直接拼进文件名，必须挡住 {@code ../} 这类逃逸。空 id 也一并挡掉。 */
    private static String requireValidId(String id) {
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("非法的测试 id: \"" + id + "\"");
        }
        return id;
    }

    private static List<String> listTests(MinecraftServer server) throws IOException {
        Path dir = TestPaths.testsDir(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }
    }

    private static TestEditorResponse readDefinition(MinecraftServer server, int requestId, String rawId)
            throws IOException {
        String id = requireValidId(rawId);
        Path file = TestPaths.testFile(server, id);
        if (!Files.isRegularFile(file)) {
            throw new IOException("找不到测试定义: " + id);
        }
        TestDefinition definition = TestDefinitionLoader.load(file);
        // 走一遍 writer 再发：客户端拿到的永远是规范形状（相对坐标、省略可省的字段），
        // 它改完存回来才不会因为形状差异而变形。
        return TestEditorResponse.definition(requestId, id, TestDefinitionWriter.toJson(definition));
    }

    private static TestEditorResponse writeDefinition(MinecraftServer server, ServerPlayerEntity player,
                                                      int requestId, String rawId, String json)
            throws IOException {
        String id = requireValidId(rawId);
        // 先解析再落盘。坏 JSON 绝不能进存档——否则这个测试以后每次 runall 都报一次错，
        // 而且报错点在"加载"而不是"保存"，很难联想到是编辑器写坏的。
        TestDefinition definition = TestDefinitionLoader.parse(json, id);
        TestDefinitionWriter.write(TestPaths.testFile(server, id), definition);
        WrtLog.LOGGER.info("[WRT] {} 保存了测试 {}", player.getGameProfile().name(), id);
        return TestEditorResponse.saved(requestId, id);
    }

    /**
     * 选区上报。**不查权限**：它只往服务端那张按 UUID 索引的小表里写两个坐标，没有安全影响
     * （每个玩家至多两个条目），而消费它的 {@code /wrutils test new} 自己就挂 gamemaster 门槛。
     * 查了反而两难——静默丢弃让非 op 的燧石点击毫无反馈，回错误消息又会每次点击刷屏。
     */
    private static void onSelection(SelectionUpdate update, ServerPlayNetworking.Context context) {
        ServerPlayerEntity player = context.player();
        context.server().execute(() -> {
            if (update.pos1()) {
                SelectionManager.get().setPos1(player.getUuid(), update.pos());
            } else {
                SelectionManager.get().setPos2(player.getUuid(), update.pos());
            }
        });
    }
}
