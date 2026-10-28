package com.musicmod.client.gui;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.client.ClientTagStore;
import com.musicmod.i18n.Lang;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SRequestListPacket;
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

    public QueueScreen(Screen parent) {
        super(Component.literal(Lang.tr("播放队列")));
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
        NetworkHandler.sendToServer(new C2SRequestListPacket());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.tr("关闭")), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Font font = this.font;
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        int cx = x + w / 2;

        g.drawCenteredString(font, Lang.tr("§e§l播放队列"), cx, 8, 0xFFFFFF);

        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        String current = ClientMusicManager.getInstance().getCurrentSong();
        int rows = visibleRows();
        int maxScroll = Math.max(0, queue.size() - rows);
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        // 面板
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF4A4A60);
        g.fill(x, y, x + w, y + h, 0x90000010);

        g.drawString(font, Lang.tr("§7共 ") + queue.size() + Lang.tr(" 首"), x + 8, y + 6, 0xAAAAAA);

        int listY = y + 22;
        int listH = rows * ROW_H;
        if (queue.isEmpty()) {
            g.drawCenteredString(font, Lang.tr("§7播放队列为空"), cx, listY + listH / 2 - 4, 0xAAAAAA);
        } else {
            ClientTagStore tags = ClientTagStore.getInstance();
            for (int i = 0; i < rows; i++) {
                int idx = scroll + i;
                if (idx >= queue.size()) break;
                int rowY = listY + i * ROW_H;
                String song = queue.get(idx);
                boolean active = song.equals(current);
                if (active) {
                    g.fill(x + 2, rowY, x + w - 2, rowY + ROW_H - 1, 0x6033AA66);
                }
                String color = active ? "§a" : "§f";
                String prefix = (idx + 1) + ". ";
                String title = tags.displayTitle(song);
                g.drawString(font, color + prefix + title, x + 8, rowY + 4, 0xFFFFFF);
                if (active) {
                    g.drawString(font, Lang.tr("§a正在播放"), x + w - font.width(Lang.tr("§a正在播放")) - 8,
                            rowY + 4, 0x55FF55);
                }
            }
            if (maxScroll > 0) {
                int barX = x + w - 5;
                float ratio = (float) scroll / maxScroll;
                int barH = Math.max(12, listH * rows / queue.size());
                int barY = listY + (int) ((listH - barH) * ratio);
                g.fill(barX, listY, barX + 4, listY + listH, 0x40000000);
                g.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<String> queue = ClientMusicManager.getInstance().getLastQueue();
        int maxScroll = Math.max(0, queue.size() - visibleRows());
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
    public void onClose() {
        if (parent != null) Minecraft.getInstance().setScreen(parent);
        else super.onClose();
    }
}
