package com.musicmod;

import com.musicmod.command.MusicCommand;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.server.MusicServerManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MusicMod.MOD_ID)
public class MusicMod {
    public static final String MOD_ID = "musicmod";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public MusicMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // 注册 Forge 配置（客户端配置 + 服务器配置）
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MusicModConfig.CLIENT_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MusicModConfig.SERVER_SPEC);

        // 客户端专用初始化（配置界面注册等）。
        // 必须通过 dist 判断间接调用 ClientSetup，主类字节码不能直接引用
        // Screen 等客户端类，否则专用服务器会被 RuntimeDistCleaner 拦截。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 尽可能早地关闭 AWT 无头模式，避免上传时的系统文件选择器直接抛 HeadlessException。
            // 若 JVM/AWT 已提前初始化，上传界面会自动回退到游戏内路径输入。
            try {
                System.setProperty("java.awt.headless", "false");
            } catch (Throwable ignored) {}
            com.musicmod.client.ClientSetup.registerConfigScreen();
        }

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            NetworkHandler.register();
            LOGGER.info("一起听 network channel registered");
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MusicServerManager.getInstance().onServerStart(event.getServer());
        LOGGER.info("一起听 server manager initialized");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MusicServerManager.getInstance().onServerStop();
        LOGGER.info("一起听 server manager stopped");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MusicCommand.register(event.getDispatcher());
    }
}