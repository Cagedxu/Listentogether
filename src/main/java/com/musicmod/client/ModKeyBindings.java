package com.musicmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.musicmod.MusicMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键绑定。
 * 默认 F8 键打开播放器界面，可在"选项 - 控制"中重新绑定。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = MusicMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModKeyBindings {

    public static final KeyMapping OPEN_PLAYER = new KeyMapping(
            "key.musicmod.open_player",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.musicmod");

    /** K 键循环切换 HUD 显示模式：歌词 → 歌名 → 关闭 */
    public static final KeyMapping CYCLE_HUD_MODE = new KeyMapping(
            "key.musicmod.cycle_hud_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.musicmod");

    /** N 键循环切换播放模式：顺序 → 单曲循环 → 随机 */
    public static final KeyMapping CYCLE_PLAY_MODE = new KeyMapping(
            "key.musicmod.cycle_play_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.musicmod");

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PLAYER);
        event.register(CYCLE_HUD_MODE);
        event.register(CYCLE_PLAY_MODE);
    }
}