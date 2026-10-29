package com.musicmod.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 透明内部、仅显示细边框的输入框；文字自绘，避免原版阴影重影。 */
public class BorderedEditBox extends EditBox {

    private final Font uiFont;
    private Component hintText;

    public BorderedEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        this.uiFont = font;
        setBordered(false);
    }

    @Override
    public void setHint(Component hint) {
        this.hintText = hint;
        super.setHint(hint);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int color = Theme.isLight() ? 0xFF1E1E28 : 0xFFE8E8E8;
        int textY = y + (h - uiFont.lineHeight) / 2 + 1;
        int textX = x + 4;
        int innerW = w - 8;

        String value = getValue();
        int start = 0;
        // 长文本时保证末尾/光标在可见范围内
        while (start < value.length() && uiFont.width(value.substring(start)) > innerW) {
            start++;
        }
        String visible = uiFont.plainSubstrByWidth(value.substring(start), innerW);
        if (!visible.isEmpty()) {
            g.drawString(uiFont, visible, textX, textY, color, false);
        } else if (hintText != null && !isFocused()) {
            String hint = uiFont.plainSubstrByWidth(hintText.getString(), innerW);
            g.drawString(uiFont, hint, textX, textY,
                    Theme.c(0x888888), false);
        }

        if (isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursor = Math.max(0, Math.min(getCursorPosition(), value.length()));
            int rel = Math.max(0, Math.min(cursor - start, visible.length()));
            int cx = textX + uiFont.width(visible.substring(0, rel));
            g.fill(cx, textY - 1, cx + 1, textY + uiFont.lineHeight, color);
        }

        int border = isFocused() ? Theme.c(0xFFFF55) : Theme.c(0xFF4A4A60);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
    }
}
