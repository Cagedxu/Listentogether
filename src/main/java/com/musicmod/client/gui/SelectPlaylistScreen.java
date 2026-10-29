package com.musicmod.client.gui;

import com.musicmod.client.ClientPlaylists;
import com.musicmod.client.ClientTagStore;
import com.musicmod.i18n.Lang;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SPlaylistActionPacket;
import com.musicmod.playlist.Playlist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 从歌曲列表添加到歌单：独立界面选择目标歌单。 */
@OnlyIn(Dist.CLIENT)
public class SelectPlaylistScreen extends Screen {

    private static final int ROW_H = 18;
    private static final int PANEL_W = 320;

    private final Screen parent;
    private final String song;
    private int scroll = 0;
    private String selectedPlaylistId = null;
    private Button confirmBtn;

    public SelectPlaylistScreen(Screen parent, String song) {
        super(Component.literal(Theme.tr("添加到歌单")));
        this.parent = parent;
        this.song = song == null ? "" : song;
    }

    private int panelW() {
        return Math.min(PANEL_W, this.width - 24);
    }

    private int panelX() {
        return (this.width - panelW()) / 2;
    }

    private int panelY() {
        return 32;
    }

    private int panelH() {
        return Math.min(320, this.height - panelY() - 48);
    }

    private int rows() {
        return Math.max(1, (panelH() - 34) / ROW_H);
    }

    private List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<>();
        for (Playlist p : ClientPlaylists.getInstance().getAll()) {
            if (!ClientPlaylists.isRecent(p.getId())) result.add(p);
        }
        return result;
    }

    @Override
    public void tick() {
        super.tick();
        if (confirmBtn != null) confirmBtn.active = selectedPlaylistId != null;
    }

    @Override
    protected void init() {
        this.font = ModFonts.get();
        this.addRenderableWidget(Button.builder(Component.literal(Theme.tr("取消")), b -> onClose())
                .bounds(this.width / 2 - 92, this.height - 28, 88, 20).build());
        confirmBtn = Button.builder(Component.literal(Theme.tr("确定")), b -> confirm())
                .bounds(this.width / 2 + 4, this.height - 28, 88, 20).build();
        confirmBtn.active = selectedPlaylistId != null;
        this.addRenderableWidget(confirmBtn);
    }

    private void confirm() {
        if (selectedPlaylistId == null || song.isEmpty()) return;
        NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                C2SPlaylistActionPacket.ACTION_ADD_SONG, selectedPlaylistId, song));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Font font = this.font;
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        int cx = x + w / 2;

        UiText.centered(g, font, Theme.tr("§e添加到歌单"), cx, 10, Theme.c(0xFFFFFF));
        String title = ClientTagStore.getInstance().displayTitle(song);
        UiText.centered(g, font, (Theme.isLight() ? "§8" : "§7") + truncate(font, title, w - 20), cx, 24, Theme.c(0xAAAAAA));

        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + w, y + h, Theme.listBg());

        List<Playlist> list = playlists();
        int visible = rows();
        int maxScroll = Math.max(0, list.size() - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        int listY = y + 8;
        if (list.isEmpty()) {
            UiText.centered(g, font, Theme.tr("§7还没有歌单，请先在「♫ 歌单」页创建"),
                    cx, listY + 40, Theme.c(0xAAAAAA));
        } else {
            for (int i = 0; i < visible; i++) {
                int idx = scroll + i;
                if (idx >= list.size()) break;
                int rowY = listY + i * ROW_H;
                Playlist pl = list.get(idx);
                boolean hover = mouseX >= x + 4 && mouseX <= x + w - 4
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                boolean selected = pl != null && pl.getId().equals(selectedPlaylistId);
                if (hover || selected) {
                    g.fill(x + 4, rowY, x + w - 4, rowY + ROW_H - 1,
                            Theme.c(selected ? 0x60333350 : 0x80555577));
                }
                String icon = ClientPlaylists.isFavorites(pl.getId()) ? "❤ " : "♫ ";
                g.drawString(font, icon + truncate(font, Theme.tr(pl.getName()), w - 90),
                        x + 10, rowY + 4, Theme.c(0xF0F0F0), false);
                String count = (Theme.isLight() ? "§8" : "§7") + pl.size() + Theme.tr(" 首");
                g.drawString(font, count, x + w - font.width(count) - 12, rowY + 4, Theme.c(0xAAAAAA), false);
            }
            if (maxScroll > 0) {
                int barX = x + w - 6;
                float ratio = (float) scroll / maxScroll;
                int listH = visible * ROW_H;
                int barH = Math.max(10, listH * visible / list.size());
                int barY = listY + (int) ((listH - barH) * ratio);
                g.fill(barX, listY, barX + 3, listY + listH, Theme.c(0x40000000));
                g.fill(barX, barY, barX + 3, barY + barH, Theme.c(0xFFAAAAAA));
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = panelX(), y = panelY(), w = panelW();
        List<Playlist> list = playlists();
        int visible = rows();
        int listY = y + 8;
        if (mouseX >= x + 4 && mouseX <= x + w - 4
                && mouseY >= listY && mouseY < listY + visible * ROW_H) {
            int row = (int) ((mouseY - listY) / ROW_H);
            int idx = scroll + row;
            if (idx >= 0 && idx < list.size()) {
                selectedPlaylistId = list.get(idx).getId();
                if (confirmBtn != null) confirmBtn.active = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, playlists().size() - rows());
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

    private String truncate(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        while (!text.isEmpty() && font.width(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }
}
