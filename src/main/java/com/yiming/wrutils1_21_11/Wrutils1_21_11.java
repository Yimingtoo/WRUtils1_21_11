package com.yiming.wrutils1_21_11;

import com.yiming.wrutils1_21_11.test.WrtTestEngine;
import net.fabricmc.api.ModInitializer;

public class Wrutils1_21_11 implements ModInitializer {

    @Override
    public void onInitialize() {
        WrtTestEngine.bootstrap();
    }
}
