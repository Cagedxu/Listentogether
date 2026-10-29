package com.musicmod.client.gui;

import com.musicmod.i18n.Lang;

import com.musicmod.client.ClientFavorites;
import com.musicmod.client.ClientMusicManager;
import com.musicmod.client.ClientPlaylists;
import com.musicmod.client.ClientTagStore;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SPlaylistActionPacket;
import com.musicmod.playlist.Playlist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「添加歌曲」独立界面（全新 Screen）。
 * <p>
 * 打开时替换掉歌单界面，因此歌单详情页完全不会渲染，
 * 后方的歌曲名、作者名、专辑等信息不可能透到本界面。
 * <p>
 * 行为：勾选歌曲（可多选）只是暂存；点「确定」才真正写入（收藏歌单写本地、
 * 自定义歌单批量提交服务器）；点「取消」/ESC/回车无勾选时放弃本次勾选并返回歌单详情。
 */
@OnlyIn(Dist.CLIENT)
public class AddSongsToPlaylistScreen extends Screen {

    private static final int ROW_H = 16;
    private static final int LIST_TOP = 54;
    /** 窄而高的面板宽度（x 短 y 长） */
    private static final int PANEL_W = 320;

    private final Screen parent;
    private final String playlistId;

    /** 暂存勾选的歌曲（点「确定」才写入） */
    private final Set<String> pending = new LinkedHashSet<>();

    private int scroll = 0;
    private int visibleRows = 1;

    private EditBox searchBox;
    private String searchFilter = "";

    private Button cancelBtn;
    private Button confirmBtn;

    public AddSongsToPlaylistScreen(Screen parent, String playlistId) {
        super(Component.literal(Theme.tr("一起听 · 添加歌曲")));
        this.parent = parent;
        this.playlistId = playlistId == null ? "" : playlistId;
    }

    private Playlist target() {
        return ClientPlaylists.getInstance().getById(playlistId);
    }

    private boolean favorites() {
        return ClientPlaylists.isFavorites(playlistId);
    }

    /** 窄面板宽度（窗口过窄时收缩） */
    private int panelW() {
        return Math.min(PANEL_W, this.width - 16);
    }

    private int panelX() {
        return (this.width - panelW()) / 2;
    }

    private int panelY() {
        return LIST_TOP;
    }

    /** 面板高度：从列表顶部一直延伸到底部按钮上方（y 长） */
    private int panelH() {
        return this.height - panelY() - 44;
    }

    /** 全部歌曲，按当前排序配置排序 */
    private List<String> songs() {
        List<String> all = new ArrayList<>(ClientMusicManager.getInstance().getLastSongList());
        if (all.size() > 1) {
            ClientTagStore tags = ClientTagStore.getInstance();
            Comparator<String> cmp;
            switch (MusicModConfig.getSortMode()) {
                case ARTIST:
                    cmp = Comparator.comparing(tags::artist, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(tags::displayTitle, String.CASE_INSENSITIVE_ORDER);
                    break;
                case ALBUM:
                    cmp = Comparator.comparing(tags::album, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(tags::displayTitle, String.CASE_INSENSITIVE_ORDER);
                    break;
                case DURATION:
                    cmp = Comparator.comparingInt((String s) -> {
                        int d = tags.durationSec(s);
                        return d > 0 ? d : Integer.MAX_VALUE;
                    });
                    break;
                default:
                    cmp = Comparator.comparing(tags::displayTitle, String.CASE_INSENSITIVE_ORDER);
            }
            all.sort(cmp);
        }
        if (!searchFilter.isEmpty()) {
            ClientTagStore tags = ClientTagStore.getInstance();
            all.removeIf(song -> {
                String q = searchFilter;
                return !tags.displayTitle(song).toLowerCase().contains(q)
                        && !tags.artist(song).toLowerCase().contains(q)
                        && !song.toLowerCase().contains(q);
            });
        }
        return all;
    }

    @Override
    protected void init() {
        super.init();
        this.font = ModFonts.get();
        cancelBtn = Button.builder(Component.literal(Theme.tr("取消")), b -> onClose())
                .bounds(this.width / 2 - 92, this.height - 28, 88, 20).build();
        confirmBtn = Button.builder(Component.literal(Theme.tr("确定")), b -> confirm())
                .bounds(this.width / 2 + 4, this.height - 28, 88, 20).build();
        this.addRenderableWidget(cancelBtn);
        this.addRenderableWidget(confirmBtn);

        searchBox = new BorderedEditBox(this.font, panelX() + 10, LIST_TOP - 18, panelW() - 20, 14,
                Component.literal(Theme.tr("搜索")));
        searchBox.setMaxLength(64);
        searchBox.setBordered(false);
        searchBox.setHint(Component.literal(Theme.tr("§7搜索歌曲 / 歌手...")));
        searchBox.setResponder(text -> {
            searchFilter = text.trim().toLowerCase();
            scroll = 0;
        });
        this.addRenderableWidget(searchBox);
    }

    @Override
    public void tick() {
        super.tick();
        if (target() == null) {
            // 目标歌单被删除时自动返回歌单详情
            onClose();
            return;
        }
        if (confirmBtn != null) {
            confirmBtn.active = !pending.isEmpty();
        }
        if (searchBox != null) searchBox.tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        drawHeaderAndList(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawHeaderAndList(GuiGraphics g, int mouseX, int mouseY) {
        Font font = this.font;
        Playlist target = target();
        if (target == null) {
            return;
        }
        List<String> songs = songs();

        int x = panelX();
        int w = panelW();
        int y = panelY();
        int h = panelH();
        visibleRows = Math.max(1, h / ROW_H);

        // 顶部标题 / 说明 / 计数（限制在窄面板内）
        g.drawString(font, Theme.tr("§e添加歌曲到「")
                + truncate(font, Theme.tr(target.getName()), Math.max(40, w - 110)) + "」",
                x + 10, 8, Theme.c(0xFFFFFF), false);
        String count = Theme.tr("§7共 ") + songs.size() + Theme.tr(" 首");
        g.drawString(font, count, x + w - font.width(count) - 10, 8, Theme.c(0xAAAAAA), false);
        String sub = favorites() ? Theme.tr("§7勾选后点「确定」加入收藏") : Theme.tr("§7勾选后点「确定」加入歌单");
        g.drawString(font, sub, x + 10, 22, Theme.c(0xAAAAAA), false);

        // 窄而高的列表面板（与播放器其它页面同一色调）
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + w, y + h, Theme.listBg());

        if (songs.isEmpty()) {
            String empty = searchFilter.isEmpty()
                    ? Theme.tr("§7歌曲列表为空，请先放入歌曲并刷新")
                    : Theme.tr("§7（无匹配歌曲）");
            UiText.centered(g, font, empty, x + w / 2, y + h / 2 - 4, Theme.c(0xAAAAAA));
        } else {
            int maxScroll = Math.max(0, songs.size() - visibleRows);
            scroll = Math.max(0, Math.min(maxScroll, scroll));
            ClientTagStore tags = ClientTagStore.getInstance();
            int titleX = x + 26;
            int titleMax = Math.max(40, w - 26 - 66);
            for (int i = 0; i < visibleRows; i++) {
                int idx = scroll + i;
                if (idx >= songs.size()) break;
                int rowY = y + i * ROW_H;
                boolean hover = mouseX >= x && mouseX <= x + w
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                if (hover) g.fill(x, rowY, x + w, rowY + ROW_H - 1, Theme.c(0x80555577));
                String song = songs.get(idx);
                boolean already = target.contains(song);
                boolean isPending = pending.contains(song);
                String check = already ? "§8☑" : isPending ? "§b☑" : "§7☐";
                g.drawString(font, check, x + 8, rowY + 4, Theme.c(0xFFFFFF), false);
                String display = tags.displayTitle(song);
                String artist = tags.artist(song);
                if (!artist.isEmpty()) {
                    display += " §8· " + artist;
                }
                g.drawString(font, (already ? "§8" : "§f")
                                + truncate(font, display, titleMax),
                        titleX, rowY + 4, already ? Theme.c(0x888888) : Theme.c(0xF0F0F0), false);
                String status = already ? Theme.tr("§a✓ 已添加") : isPending ? Theme.tr("§b待添加") : "";
                if (!status.isEmpty()) {
                    g.drawString(font, status, x + w - font.width(status) - 12,
                            rowY + 4, already ? Theme.c(0x55FF55) : Theme.c(0x55FFFF), false);
                }
            }
            if (maxScroll > 0) {
                int barX = x + w - 5;
                float ratio = (float) scroll / maxScroll;
                int barH = Math.max(12, h * visibleRows / songs.size());
                int barY = y + (int) ((h - barH) * ratio);
                g.fill(barX, y, barX + 4, y + h, Theme.c(0x40000000));
                g.fill(barX, barY, barX + 4, barY + barH, Theme.c(0xFFAAAAAA));
            }
        }

        // 底部：已勾选数量（取消 / 确定按钮为真实控件，在 super.render 中绘制）
        g.drawString(font, Theme.tr("§7已勾选 §e") + pending.size() + Theme.tr(" §7首"),
                x + 10, this.height - 16, Theme.c(0xAAAAAA), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchBox != null && searchBox.visible
                && !(mouseX >= searchBox.getX() && mouseX < searchBox.getX() + searchBox.getWidth()
                && mouseY >= searchBox.getY() && mouseY < searchBox.getY() + searchBox.getHeight())) {
            searchBox.setFocused(false);
            if (getFocused() instanceof EditBox) setFocused(null);
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int mx = (int) mouseX, my = (int) mouseY;
        int x = panelX(), y = panelY(), w = panelW();
        int listH = visibleRows * ROW_H;
        if (my >= y && my < y + listH && mx >= x && mx <= x + w) {
            // 滚动条点击直接跳转
            if (mx >= x + w - 5) {
                List<String> songs = songs();
                int maxScroll = Math.max(0, songs.size() - visibleRows);
                if (maxScroll > 0) {
                    int barH = Math.max(12, listH * visibleRows / songs.size());
                    float ratio = Math.max(0f, Math.min(1f,
                            (float) (my - y - barH / 2) / Math.max(1, listH - barH)));
                    scroll = (int) (ratio * maxScroll);
                }
                return true;
            }
            int row = (my - y) / ROW_H;
            int idx = scroll + row;
            List<String> songs = songs();
            if (idx >= 0 && idx < songs.size()) {
                String song = songs.get(idx);
                Playlist target = target();
                if (target != null && !target.contains(song)) {
                    if (!pending.remove(song)) {
                        pending.add(song);
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<String> songs = songs();
        int maxScroll = Math.max(0, songs.size() - visibleRows);
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
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && !pending.isEmpty()
                && (searchBox == null || !searchBox.isFocused())) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 确认：收藏歌单写入本地，自定义歌单一次性批量提交服务器 */
    private void confirm() {
        Playlist target = target();
        if (target == null || pending.isEmpty()) {
            onClose();
            return;
        }
        if (ClientPlaylists.isFavorites(playlistId)) {
            ClientFavorites.addAll(pending);
        } else if (!ClientPlaylists.isRecent(playlistId)) {
            Set<String> library = new java.util.HashSet<>(
                    ClientMusicManager.getInstance().getLastSongList());
            List<String> toAdd = new ArrayList<>();
            for (String song : pending) {
                if (library.contains(song) && !target.contains(song) && !toAdd.contains(song)) {
                    toAdd.add(song);
                }
            }
            if (!toAdd.isEmpty()) {
                NetworkHandler.sendToServer(new C2SPlaylistActionPacket(playlistId, toAdd));
            }
        }
        pending.clear();
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return com.musicmod.config.MusicModConfig.isPauseSingleplayer();
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    private String truncate(Font font, String s, int maxWidth) {
        if (s == null) return "";
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        while (!s.isEmpty() && font.width(s + ellipsis) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ellipsis;
    }
}
