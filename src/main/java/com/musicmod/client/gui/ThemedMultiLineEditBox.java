package com.musicmod.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 主题化多行歌词输入框。
 * 不依赖原版 MultilineTextField 的包私有 StringView，避免服务端模块层 split package；
 * 鼠标拖动按列表习惯滚动，不再默认跳到文本末尾。
 */
public class ThemedMultiLineEditBox extends AbstractScrollWidget {
    private final Font font;
    private final MultilineTextField textField;
    private final Component placeholder;
    private boolean dragging;
    private double dragStartY;
    private double dragStartScroll;

    public ThemedMultiLineEditBox(Font font, int x, int y, int width, int height, Component placeholder) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.placeholder = placeholder;
        this.textField = new MultilineTextField(font, width - this.totalInnerPadding());
        this.textField.setCursorListener(this::scrollToCursor);
    }

    public void setCharacterLimit(int limit) {
        this.textField.setCharacterLimit(limit);
    }

    public void setValue(String value) {
        this.textField.setValue(value == null ? "" : value);
        this.setScrollAmount(0.0D);
    }

    public String getValue() {
        return this.textField.value();
    }

    @Override
    protected int getInnerHeight() {
        return this.lineCount() * 9 + this.innerPadding() * 2;
    }

    @Override
    protected double scrollRate() {
        return 9.0D;
    }

    private int lineCount() {
        String s = this.textField.value();
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private void scrollToCursor() {
        int line = this.textField.getLineAtCursor();
        int y = line * 9;
        if (y < this.scrollAmount()) {
            this.setScrollAmount(y);
        } else if (y + 9 > this.scrollAmount() + this.height - this.totalInnerPadding()) {
            this.setScrollAmount(y + 9 - this.height + this.totalInnerPadding());
        }
    }

    @Override
    protected void renderBackground(GuiGraphics g) {
        g.fill(this.getX() - 1, this.getY() - 1, this.getX() + this.width + 1, this.getY() + this.height + 1,
                Theme.c(0xFF4A4A60));
        g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                Theme.listBg());
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partial) {
        String value = this.textField.value();
        int baseX = this.getX() + this.innerPadding();
        int baseY = this.getY() + this.innerPadding();
        if (value.isEmpty() && !this.isFocused()) {
            g.drawWordWrap(this.font, this.placeholder, baseX, baseY,
                    this.width - this.totalInnerPadding(), Theme.c(0xAAAAAA));
            return;
        }

        String[] lines = value.split("\n", -1);
        int cursor = this.textField.cursor();
        int cursorLine = 0, cursorCol = 0, consumed = 0;
        for (int i = 0; i < lines.length; i++) {
            if (cursor <= consumed + lines[i].length()) {
                cursorLine = i;
                cursorCol = Math.max(0, cursor - consumed);
                break;
            }
            consumed += lines[i].length() + 1;
        }

        for (int i = 0; i < lines.length; i++) {
            int y = baseY + i * 9;
            g.drawString(this.font, lines[i], baseX, y, Theme.c(0xFFFFFF), false);
        }

        if (this.isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int y = baseY + cursorLine * 9;
            int x = baseX + this.font.width(lines[Math.min(cursorLine, lines.length - 1)]
                    .substring(0, Math.min(cursorCol, lines[Math.min(cursorLine, lines.length - 1)].length())));
            g.fill(x, y - 1, x + 1, y + 9, Theme.isLight() ? 0xFFB4551E : 0xFFB9A7D8);
        }
    }

    @Override
    public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
        out.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.placeholder);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.withinContentAreaPoint(mouseX, mouseY)) return false;
        this.setFocused(true);
        this.dragging = true;
        this.dragStartY = mouseY;
        this.dragStartScroll = this.scrollAmount();

        int line = Mth.clamp((int) ((mouseY - this.getY() - this.innerPadding() + this.scrollAmount()) / 9.0D),
                0, this.lineCount() - 1);
        String[] lines = this.textField.value().split("\n", -1);
        int start = 0;
        for (int i = 0; i < line && i < lines.length; i++) start += lines[i].length() + 1;
        String lineText = line < lines.length ? lines[line] : "";
        int col = Math.min(lineText.length(),
                this.font.plainSubstrByWidth(lineText, (int) Math.max(0, mouseX - this.getX() - this.innerPadding())).length());
        this.textField.seekCursor(Whence.ABSOLUTE, start + col);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!this.dragging) return false;
        this.setScrollAmount(this.dragStartScroll - (mouseY - this.dragStartY));
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.withinContentAreaPoint(mouseX, mouseY)) return false;
        this.setScrollAmount(this.scrollAmount() - delta * 18.0D);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.isFocused()) return false;
        if (this.textField.keyPressed(keyCode)) {
            this.scrollToCursor();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (!this.isFocused() || !SharedConstants.isAllowedChatCharacter(c)) return false;
        this.textField.insertText(Character.toString(c));
        this.scrollToCursor();
        return true;
    }
}
