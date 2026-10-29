package com.musicmod.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 播放器界面用的半透明卡片按钮，替代原版灰色按钮。 */
public class CardButton extends Button {

    public CardButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        draw(g, this, mouseX, mouseY);
    }

    public static void draw(GuiGraphics g, Button button, int mouseX, int mouseY) {
        boolean light = Theme.isLight();
        int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        boolean active = button.active;

        int fill = light
                ? (hover ? 0x66FFF9EE : 0x4DFFFBF3)
                : (hover ? 0x664A4A68 : 0x3D202034);
        int border = light
                ? (hover ? 0xFFB4551E : 0xFFC9B79C)
                : (hover ? 0xFFB9A7D8 : 0xFF5A5A78);
        if (!active) {
            fill = light ? 0x22FFFBF3 : 0x1F202034;
            border = light ? 0x40C9B79C : 0x405A5A78;
        }

        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
        if (hover && active) {
            g.fill(x + 1, y + 1, x + w - 1, y + 2, light ? 0x40FFFFFF : 0x30FFFFFF);
        }

        Font font = ModFonts.get();
        Font gameFont = Minecraft.getInstance().font;
        int textColor = active
                ? (light ? 0xFF2B2722 : 0xFFF0F0F0)
                : (light ? 0x806B6259 : 0x80AAAAAA);
        String raw = button.getMessage().getString();
        String icon = "";
        String label = raw;
        int space = raw.indexOf(' ');
        if (space > 0 && isSymbol(raw.substring(0, space))) {
            icon = raw.substring(0, space + 1);
            label = raw.substring(space + 1);
        } else if (space < 0 && isSymbol(raw)) {
            icon = raw;
            label = "";
        }
        if (!icon.isEmpty() && label.isEmpty()) {
            // 纯图标按钮：用原版字体绘制 unicode 符号，避免模组字体缺字时偏移
            UiText.centered(g, gameFont, icon, x + w / 2,
                    y + (h - gameFont.lineHeight) / 2 + 1, textColor);
        } else if (!icon.isEmpty()) {
            int iw = gameFont.width(icon);
            int tw = font.width(label);
            int sx = x + (w - iw - tw) / 2;
            g.drawString(gameFont, icon, sx,
                    y + (h - gameFont.lineHeight) / 2 + 1, textColor, false);
            g.drawString(font, label, sx + iw,
                    y + (h - font.lineHeight) / 2 + 1, textColor, false);
        } else {
            UiText.centered(g, font, label, x + w / 2,
                    y + (h - font.lineHeight) / 2 + 1, textColor);
        }
    }

    private static boolean isSymbol(String text) {
        if (text == null || text.isEmpty()) return false;
        int cp = text.codePointAt(0);
        // 仅把真正的符号/emoji 当作图标；中文、日文、韩文等文字不能误判。
        if (cp >= 0x1F000) return true;                 // emoji
        if (cp >= 0x2190 && cp <= 0x2BFF) return true;   // 箭头、音乐符号、几何符号等
        return cp < 0x80 && !Character.isLetterOrDigit(cp) && cp != '§';
    }
}
