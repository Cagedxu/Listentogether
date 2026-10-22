package com.musicmod.client;

import com.musicmod.client.gui.MusicModConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * 客户端专用初始化。
 * 必须与主类隔离：本类引用了 Screen 等客户端类，
 * 若在主类中直接引用会导致专用服务器加载时被 RuntimeDistCleaner 拦截。
 * 主类通过 FMLEnvironment.dist 判断后才调用本类方法。
 */
public class ClientSetup {

    /**
     * 注册模组配置界面（模组列表"配置"按钮）
     */
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new MusicModConfigScreen(parent)));
    }
}