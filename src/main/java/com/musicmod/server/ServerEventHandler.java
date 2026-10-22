package com.musicmod.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 服务器端事件处理器：监听玩家加入/离开，同步播放状态；
 * 每 tick 检测歌曲播放完毕并按播放模式自动处理。
 */
public class ServerEventHandler {

    private static boolean registered = false;

    public static void register() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
            registered = true;
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            MusicServerManager.getInstance().onPlayerJoined(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            MusicServerManager.getInstance().onPlayerLeft(serverPlayer);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MusicServerManager.getInstance().tick();
        }
    }
}