package com.yiming.wrutils1_21_11;

import com.yiming.wrutils1_21_11.net.TestEditorServer;
import com.yiming.wrutils1_21_11.net.TestStatusServer;
import com.yiming.wrutils1_21_11.net.WrtNetworking;
import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import net.fabricmc.api.ModInitializer;

public class Wrutils1_21_11 implements ModInitializer {

    @Override
    public void onInitialize() {
        // payload 类型必须在**两端**都注册（客户端否则解不开服务端发来的包）。
        // 接收器则两侧各注册自己那半边：客户端那份在 Wrutils1_21_11Client 里。
        WrtNetworking.registerPayloads();
        TestEditorServer.register();
        TestStatusServer.register();
        WrtTestEngine.bootstrap();
    }
}
