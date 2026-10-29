package com.musicmod.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** 已移除自定义字体功能：模组统一使用游戏原版字体。 */
public final class ModFonts {

    private ModFonts() {}

    public static Font get() {
        return Minecraft.getInstance().font;
    }

    public static void invalidate() {
    }
}
