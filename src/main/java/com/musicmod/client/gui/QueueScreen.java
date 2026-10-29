package com.musicmod.client.gui;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.client.ClientTagStore;
import com.musicmod.config.MusicModConfig;
import com.musicmod.i18n.Lang;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SRequestListPacket;
import com.musicmod.network.packet.C2SMusicActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** 正在播放的队列列表。 */
@OnlyIn(Dist.CLIENT)
public class QueueScreen extends Screen {

    private static final int ROW_H = 16;
    private static final int PANEL_W = 320;

    private final Screen parent;
    private int scroll = 0;
    private boolean scrollToCurrentPending = true;
    private boolean dragging = false;
    private boolean draggingScrollbar = false;
    private int selectedIndex = -1;
    private int dragStartY = 0;
    private int dragStartScroll = 0;
    private int lastClickRow = -1;
    private long lastClickTime = 0;

    public QueueScreen(Screen parent) {
        super(Component.literal(Theme.tr("播放队列")));
        this.parent = parent;
    }

    private int panelW() {
        return Math.min(PANEL_W, this.width - 24);
    }

    private int panelX() {
        return (this.width - panelW()) / 2;
    }

    private int panelY() {
        return 28;
    }

    private int panelH() {
        return Math.min(320, this.height - panelY() - 48);
    }

    private int visibleRows() {
        return Math.max(1, (panelH() - 26) / ROW_H);
    }

    @Override
    protected void init() {
        this.font = ModFonts.get();
        NetworkHandler.sendToServer(new C2SRequestListPacket());
        this.addRenderableWidget(Button.builder(Component.literal(Theme.tr("关闭")), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Font font = this.font;
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        int cx = x + w / 2;

        UiText.centered(g, font, Theme.tr("§e播放队列"), cx, 8, Theme.c(0xFFFFFF));

        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        String current = ClientMusicManager.getInstance().getCurrentSong();
        int currentIndex = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).equalsIgnoreCase(current)) {
                currentIndex = i;
                break;
            }
        }
        // 显示已播放歌曲时保持完整队列和当前歌曲的真实位置，不自动滚动到当前歌曲；
        // 隐藏已播放歌曲时才从当前歌曲开始，并置顶显示。
        boolean showPlayed = MusicModConfig.getQueueShowPlayed();
        List<String> display;
        int currentDisplayIndex;
        if (showPlayed || currentIndex < 0) {
            display = queue;
            currentDisplayIndex = currentIndex;
        } else {
            display = queue.subList(currentIndex, queue.size());
            currentDisplayIndex = 0;
        }
        int rows = visibleRows();
        int maxScroll = Math.max(0, display.size() - rows);
        if (scrollToCurrentPending && !display.isEmpty()) {
            scrollToCurrentPending = false;
            if (currentDisplayIndex >= 0) {
                scroll = Math.max(0, Math.min(currentDisplayIndex - rows / 2, maxScroll));
            }
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        // 面板
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + w, y + h, Theme.listBg());

        g.drawString(font, Theme.tr("§7共 ") + display.size() + Theme.tr(" 首"), x + 8, y + 6, Theme.c(0xAAAAAA), false);

        int listY = y + 22;
        int listH = rows * ROW_H;
        if (display.isEmpty()) {
            UiText.centered(g, font, Theme.tr("§7播放队列为空"), cx, listY + listH / 2 - 4, Theme.c(0xAAAAAA));
        } else {
            ClientTagStore tags = ClientTagStore.getInstance();
            for (int i = 0; i < rows; i++) {
                int idx = scroll + i;
                if (idx >= display.size()) break;
                int rowY = listY + i * ROW_H;
                String song = display.get(idx);
                boolean active = idx == currentDisplayIndex;
                if (active) {
                    g.fill(x + 2, rowY, x + w - 8, rowY + ROW_H - 1, Theme.nowPlayingBg());
                }
                if (idx == selectedIndex) {
                    int accent = Theme.c(0xFFFF55);
                    g.fill(x + 2, rowY, x + w - 8, rowY + 1, accent);
                    g.fill(x + 2, rowY + ROW_H - 2, x + w - 8, rowY + ROW_H - 1, accent);
                    g.fill(x + 2, rowY, x + 3, rowY + ROW_H - 1, accent);
                    g.fill(x + w - 9, rowY, x + w - 8, rowY + ROW_H - 1, accent);
                }
                String color = active ? "§a" : Theme.isLight() ? "§0" : "§f";
                String prefix = (idx + 1) + ". ";
                int maxTitleW = Math.max(20, w - 24 - font.width(prefix));
                String title = truncate(font, tags.displayTitle(song), maxTitleW);
                g.drawString(font, color + prefix + title, x + 8, rowY + 4, Theme.c(0xFFFFFF), false);
            }
            if (maxScroll > 0) {
                int barX = x + w - 5;
                float ratio = (float) scroll / maxScroll;
                int barH = Math.max(12, listH * rows / display.size());
                int barY = listY + (int) ((listH - barH) * ratio);
                g.fill(barX, listY, barX + 4, listY + listH, Theme.c(0x40000000));
                g.fill(barX, barY, barX + 4, barY + barH, Theme.c(0xFFAAAAAA));
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private String truncate(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        while (!text.isEmpty() && font.width(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }

    private int displayOffset() {
        if (MusicModConfig.getQueueShowPlayed()) return 0;
        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        String current = ClientMusicManager.getInstance().getCurrentSong();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).equalsIgnoreCase(current)) return i;
        }
        return 0;
    }

    private int displaySize() {
        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        if (MusicModConfig.getQueueShowPlayed()) return queue.size();
        String current = ClientMusicManager.getInstance().getCurrentSong();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).equalsIgnoreCase(current)) return queue.size() - i;
        }
        return queue.size();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = panelX(), y = panelY(), w = panelW();
            int listY = y + 22;
            int listH = visibleRows() * ROW_H;
            if (mouseX >= x && mouseX <= x + w && mouseY >= listY && mouseY < listY + listH) {
                int maxScroll = Math.max(0, displaySize() - visibleRows());
                if (mouseX >= x + w - 8 && maxScroll > 0) {
                    draggingScrollbar = true;
                    int barH = Math.max(12, listH * visibleRows() / Math.max(1, displaySize()));
                    int track = Math.max(1, listH - barH);
                    float ratio = (float) Math.max(0, Math.min(track, (int) (mouseY - listY))) / track;
                    scroll = Math.round(ratio * maxScroll);
                    return true;
                }
                int row = (int) ((mouseY - listY) / ROW_H);
                List<String> queue = ClientMusicManager.getInstance().getLastQueue();
                int base = displayOffset();
                int displayIdx = scroll + row;
                selectedIndex = displayIdx;
                long now = System.currentTimeMillis();
                if (lastClickRow == displayIdx && now - lastClickTime < 350
                        && displayIdx >= 0 && base + displayIdx < queue.size()) {
                    String song = queue.get(base + displayIdx);
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_PLAY, song));
                    lastClickRow = -1;
                    lastClickTime = 0;
                    return true;
                }
                lastClickRow = displayIdx;
                lastClickTime = now;
                dragging = true;
                dragStartY = (int) mouseY;
                dragStartScroll = scroll;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            int x = panelX(), y = panelY(), w = panelW();
            int listY = y + 22, listH = visibleRows() * ROW_H;
            int maxScroll = Math.max(0, displaySize() - visibleRows());
            int barH = Math.max(12, listH * visibleRows() / Math.max(1, displaySize()));
            int track = Math.max(1, listH - barH);
            float ratio = (float) Math.max(0, Math.min(track, (int) (mouseY - listY))) / track;
            scroll = Math.round(ratio * maxScroll);
            return true;
        }
        if (dragging && button == 0) {
            int maxScroll = Math.max(0, displaySize() - visibleRows());
            int deltaRows = (int) ((mouseY - dragStartY) / ROW_H);
            scroll = Math.max(0, Math.min(maxScroll, dragStartScroll - deltaRows));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if ((dragging || draggingScrollbar) && button == 0) {
            dragging = false;
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        int visibleSize = queue.size();
        if (!MusicModConfig.getQueueShowPlayed()) {
            String current = ClientMusicManager.getInstance().getCurrentSong();
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).equalsIgnoreCase(current)) {
                    visibleSize = queue.size() - i;
                    break;
                }
            }
        }
        int maxScroll = Math.max(0, visibleSize - visibleRows());
        if (maxScroll > 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return com.musicmod.config.MusicModConfig.isPauseSingleplayer();
    }

    @Override
    public void onClose() {
        if (parent != null) Minecraft.getInstance().setScreen(parent);
        else super.onClose();
    }
}
