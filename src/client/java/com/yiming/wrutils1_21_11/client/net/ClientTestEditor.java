package com.yiming.wrutils1_21_11.client.net;

import com.yiming.wrutils1_21_11.net.TestEditorRequest;
import com.yiming.wrutils1_21_11.net.TestEditorResponse;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 客户端侧：向服务端发起编辑器文件操作，并把响应配对回发起它的那次调用。
 *
 * <p>发起是**异步**的：界面发完请求就先返回，等响应到了再更新自己。这不是设计偏好——多人下
 * 客户端的文件系统本来就碰不到服务端的存档，同步根本无从谈起。
 *
 * <p>配对靠 {@code requestId} 而不是"反正一次只有一个请求"：连点两个测试会发出两条在途的 READ，
 * 谁先回来没有保证，没有配对就会把 A 的 JSON 显示成 B 的。
 *
 * <p>回调一律在**客户端线程**上执行（{@code context.client().execute}），界面回调里可以放心动控件。
 */
public final class ClientTestEditor {
    private static int nextRequestId;
    /** 在途请求的回调，按 requestId 索引；收到响应即摘除。 */
    private static final Map<Integer, Consumer<TestEditorResponse>> PENDING = new HashMap<>();

    private ClientTestEditor() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(TestEditorResponse.ID, (response, context) ->
                context.client().execute(() -> deliver(response)));
    }

    /**
     * 服务端是否装了 WRT 且通道已就绪。界面据此给出"该服务器没有 WRT"，而不是发出去石沉大海。
     */
    public static boolean isAvailable() {
        return ClientPlayNetworking.canSend(TestEditorRequest.ID);
    }

    public static void listTests(Consumer<TestEditorResponse> callback) {
        send(TestEditorRequest.list(nextId()), callback);
    }

    public static void readTest(String id, Consumer<TestEditorResponse> callback) {
        send(TestEditorRequest.read(nextId(), id), callback);
    }

    public static void writeTest(String id, String json, Consumer<TestEditorResponse> callback) {
        send(TestEditorRequest.write(nextId(), id, json), callback);
    }

    public static void deleteTest(String id, Consumer<TestEditorResponse> callback) {
        send(TestEditorRequest.delete(nextId(), id), callback);
    }

    /** 读取服务端权威的当前选区。 */
    public static void selection(Consumer<TestEditorResponse> callback) {
        send(TestEditorRequest.selection(nextId()), callback);
    }

    private static int nextId() {
        return ++nextRequestId;
    }

    private static void send(TestEditorRequest request, Consumer<TestEditorResponse> callback) {
        if (!isAvailable()) {
            // 同步走失败回调：调用方只需写一条处理分支，不必区分"发不出去"和"发出去失败了"
            callback.accept(TestEditorResponse.failed(request.requestId(), request.op(),
                    "该服务器没有安装 WRT（或尚未握手完成）"));
            return;
        }
        PENDING.put(request.requestId(), callback);
        ClientPlayNetworking.send(request);
    }

    private static void deliver(TestEditorResponse response) {
        Consumer<TestEditorResponse> callback = PENDING.remove(response.requestId());
        if (callback != null) {
            callback.accept(response);
        }
    }
}
