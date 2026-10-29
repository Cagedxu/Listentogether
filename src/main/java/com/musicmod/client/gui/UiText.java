package com.musicmod.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 模组界面文字绘制：关闭原版阴影，避免平滑字体出现重影。 */
public final class UiText {

    private UiText() {}

    public static void centered(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color, false);
    }

    public static void centered(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        int w = font.width(text);
        g.drawString(font, text, x - w / 2, y, color, false);
    }
}
