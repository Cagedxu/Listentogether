package com.musicmod.client.tags;

import com.mojang.blaze3d.platform.NativeImage;
import com.musicmod.client.ClientFileUploader;
import com.musicmod.client.gui.BorderedEditBox;
import com.musicmod.client.gui.CardButton;
import com.musicmod.client.gui.ThemedMultiLineEditBox;
import com.musicmod.client.gui.Theme;
import com.musicmod.i18n.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.function.Consumer;

/** 标签编辑器：播放器风格，左侧封面，右侧字段，下方大歌词区。 */
public class TagEditorScreen extends Screen {
    private static final ResourceLocation COVER_ID = new ResourceLocation("musicmod", "tag_editor_cover");

    private final File file;
    private final Consumer<TagEditData> onSaved;
    private final Runnable onCancel;
    private boolean saved;
    private final TagEditData data = new TagEditData();

    private BorderedEditBox titleBox, artistBox, albumBox;
    private ThemedMultiLineEditBox lyricsBox;
    private String status = "";
    private ResourceLocation coverTexture;
    private int coverW, coverH;
    private int coverX, coverY;
    private int panelW = 600;
    private int panelH = 430;
    private int coverSize = 100;

    public TagEditorScreen(File file, TagEditData initial, Consumer<TagEditData> onSaved) {
        this(file, initial, onSaved, null);
    }

    public TagEditorScreen(File file, TagEditData initial, Consumer<TagEditData> onSaved, Runnable onCancel) {
        super(Component.literal(Lang.tr("编辑歌曲标签")));
        this.file = file;
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        TagEditData loaded = file == null ? new TagEditData() : AudioTagWriter.read(file);
        data.title = loaded.title;
        data.artist = loaded.artist;
        data.album = loaded.album;
        data.lyrics = loaded.lyrics;
        data.cover = loaded.cover;
        data.coverMime = loaded.coverMime;
        if (initial != null) {
            if (initial.title != null && !initial.title.isBlank()) data.title = initial.title;
            if (initial.artist != null && !initial.artist.isBlank()) data.artist = initial.artist;
            if (initial.album != null && !initial.album.isBlank()) data.album = initial.album;
            if (initial.lyrics != null && !initial.lyrics.isBlank()) data.lyrics = initial.lyrics;
            if (initial.cover != null && initial.cover.length > 0) {
                data.cover = initial.cover;
                data.coverMime = initial.coverMime == null ? "" : initial.coverMime;
            }
        }
        setCover(data.cover);
    }

    private int left() { return (this.width - panelW) / 2; }
    private int top() { return (this.height - panelH) / 2; }

    @Override
    protected void init() {
        // 按当前 GUI 逻辑分辨率缩放整体面板，避免高 GUI 缩放下固定 600×430 溢出屏幕。
        int margin = Math.max(6, Math.min(20, Math.min(this.width, this.height) / 40));
        panelW = Math.min(600, Math.max(220, this.width - margin * 2));
        panelH = Math.min(430, Math.max(220, this.height - margin * 2));
        coverSize = Math.max(42, Math.min(100, Math.min(panelW / 5, panelH / 4)));

        int x = left();
        int y = top();
        int fieldX = x + 24 + coverSize + 16;
        int fieldW = Math.max(80, panelW - (fieldX - x) - 24);

        int row1 = y + 40;
        int rowGap = Math.max(24, Math.min(36, (panelH - 160) / 3));
        titleBox = box(fieldX, row1, fieldW, data.title, "曲名");
        artistBox = box(fieldX, row1 + rowGap, fieldW, data.artist, "作者");
        albumBox = box(fieldX, row1 + rowGap * 2, fieldW, data.album, "专辑");

        int searchW = Math.min(92, Math.max(60, panelW / 6));
        addRenderableWidget(new CardButton(x + panelW - searchW - 16, y + 8, searchW, 20,
                Component.literal(Lang.tr("搜索信息")), b -> openSearch(TagSearchScreen.Mode.META)));
        int lyricsTop = row1 + rowGap * 2 + 18 + 20;
        int lyricsBottom = y + panelH - 46;
        int lyricsH = Math.max(36, lyricsBottom - lyricsTop);
        int lyricSearchW = Math.min(96, Math.max(64, panelW / 5));
        addRenderableWidget(new CardButton(x + panelW - lyricSearchW - 16, lyricsTop - 20, lyricSearchW, 16,
                Component.literal(Lang.tr("搜索歌词")), b -> openSearch(TagSearchScreen.Mode.LYRICS)));

        lyricsBox = new ThemedMultiLineEditBox(this.font, x + 24, lyricsTop, panelW - 48, lyricsH,
                Component.literal(Lang.tr("歌词，可留空")));
        lyricsBox.setCharacterLimit(10_000_000);
        lyricsBox.setValue(data.lyrics);
        addRenderableWidget(lyricsBox);

        int bw = Math.min(96, Math.max(56, (panelW - 72) / 3));
        int by = y + panelH - 28;
        int bx = x + 24;
        addRenderableWidget(new CardButton(bx, by, bw, 18,
                Component.literal(Lang.tr("清除标签")), b -> clearTags()));
        addRenderableWidget(new CardButton(bx + bw + 12, by, bw, 18,
                Component.literal(Lang.tr("保存")), b -> save()));
        addRenderableWidget(new CardButton(bx + (bw + 12) * 2, by, bw, 18,
                Component.literal(Lang.tr("取消")), b -> onClose()));

        coverX = x + 24;
        coverY = y + 42;
    }

    private BorderedEditBox box(int x, int y, int w, String value, String hint) {
        BorderedEditBox b = new BorderedEditBox(this.font, x, y, w, 18, Component.literal(hint));
        b.setMaxLength(500);
        b.setValue(value == null ? "" : value);
        b.setHint(Component.literal(hint));
        addRenderableWidget(b);
        return b;
    }

    private void openSearch(TagSearchScreen.Mode mode) {
        syncFields();
        String q = (data.title + " " + data.artist).trim();
        Minecraft.getInstance().setScreen(new TagSearchScreen(this, mode, q, candidate -> {
            applyCandidate(mode, candidate);
            Minecraft.getInstance().setScreen(this);
        }));
    }

    void openCoverChoice() {
        syncFields();
        Minecraft.getInstance().setScreen(new TagCoverChoiceScreen(this));
    }

    void openCoverSearch() {
        openSearch(TagSearchScreen.Mode.COVER);
    }

    void chooseLocalCover() {
        new Thread(() -> {
            File picked = null;
            Frame owner = null;
            FileDialog fd = null;
            try {
                owner = new Frame();
                owner.setType(Frame.Type.UTILITY);
                owner.setUndecorated(true);
                owner.setSize(1, 1);
                owner.setLocation(-10000, -10000);
                owner.setAlwaysOnTop(true);
                owner.setVisible(true);

                fd = new FileDialog(owner, Lang.tr("选择封面图片"), FileDialog.LOAD);
                fd.setAlwaysOnTop(true);
                fd.setFilenameFilter((dir, name) -> {
                    String n = name.toLowerCase();
                    return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                            || n.endsWith(".webp") || n.endsWith(".bmp");
                });
                final FileDialog dlg = fd;
                Thread toFront = new Thread(() -> {
                    try {
                        Thread.sleep(300);
                        java.awt.EventQueue.invokeLater(() -> {
                            try { dlg.toFront(); dlg.requestFocus(); } catch (Throwable ignored) {}
                            ClientFileUploader.forceForeground(Lang.tr("选择封面图片"));
                        });
                        Thread.sleep(700);
                        java.awt.EventQueue.invokeLater(() -> {
                            try { dlg.toFront(); } catch (Throwable ignored) {}
                            ClientFileUploader.forceForeground(Lang.tr("选择封面图片"));
                        });
                    } catch (InterruptedException ignored) {}
                }, "MusicMod-CoverDialog-ToFront");
                toFront.setDaemon(true);
                toFront.start();

                fd.setVisible(true);
                if (fd.getFile() != null) picked = new File(fd.getDirectory(), fd.getFile());
            } catch (Throwable ignored) {
            } finally {
                if (fd != null) fd.dispose();
                if (owner != null) owner.dispose();
            }
            File f = picked;
            Minecraft.getInstance().execute(() -> {
                if (f == null || !f.isFile()) {
                    Minecraft.getInstance().setScreen(this);
                    return;
                }
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    if (bytes.length > 5 * 1024 * 1024) {
                        status = Lang.tr("图片过大（最大 5MB）");
                        return;
                    }
                    data.cover = bytes;
                    String n = f.getName().toLowerCase();
                    data.coverMime = n.endsWith(".png") ? "image/png"
                            : n.endsWith(".webp") ? "image/webp"
                            : n.endsWith(".bmp") ? "image/bmp" : "image/jpeg";
                    setCover(bytes);
                    status = Lang.tr("已更改封面");
                } catch (Throwable t) {
                    status = Lang.tr("读取图片失败");
                } finally {
                    Minecraft.getInstance().setScreen(this);
                }
            });
        }, "MusicMod-CoverPicker").start();
    }

    private void syncFields() {
        data.title = titleBox.getValue().trim();
        data.artist = artistBox.getValue().trim();
        data.album = albumBox.getValue().trim();
        data.lyrics = lyricsBox.getValue();
    }

    public void applyCandidate(TagSearchScreen.Mode mode, TagCandidate c) {
        if (c == null) return;
        switch (mode) {
            case COVER:
                if (c.cover != null && c.cover.length > 0) {
                    data.cover = c.cover;
                    data.coverMime = c.coverMime;
                    setCover(c.cover);
                    status = Lang.tr("已应用封面");
                } else {
                    status = Lang.tr("该结果没有封面");
                }
                break;
            case LYRICS:
                if (c.lyrics != null && !c.lyrics.isBlank()) {
                    lyricsBox.setValue(c.lyrics);
                    data.lyrics = c.lyrics;
                    status = Lang.tr("已应用歌词");
                } else {
                    status = Lang.tr("该结果没有歌词");
                }
                break;
            default:
                titleBox.setValue(c.title);
                artistBox.setValue(c.artist);
                albumBox.setValue(c.album);
                data.title = c.title == null ? "" : c.title;
                data.artist = c.artist == null ? "" : c.artist;
                data.album = c.album == null ? "" : c.album;
                if (c.lyrics != null && !c.lyrics.isBlank()) {
                    lyricsBox.setValue(c.lyrics);
                    data.lyrics = c.lyrics;
                }
                if (c.cover != null && c.cover.length > 0) {
                    data.cover = c.cover;
                    data.coverMime = c.coverMime == null ? "" : c.coverMime;
                    setCover(c.cover);
                }
                status = Lang.tr("已应用歌曲信息");
                break;
        }
    }

    private void clearTags() {
        titleBox.setValue("");
        artistBox.setValue("");
        albumBox.setValue("");
        lyricsBox.setValue("");
        data.title = "";
        data.artist = "";
        data.album = "";
        data.lyrics = "";
        data.cover = null;
        data.coverMime = "";
        setCover(null);
        status = Lang.tr("已清除，保存后生效");
    }

    private void save() {
        syncFields();
        if (file != null) {
            AudioTagWriter.Result r = AudioTagWriter.write(file, data);
            status = r.message;
            if (!r.ok) return;
        }
        saved = true;
        if (onSaved != null) onSaved.accept(data);
        onClose();
    }

    private void setCover(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
            DynamicTexture tex = new DynamicTexture(img);
            tex.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(COVER_ID, tex);
            coverTexture = COVER_ID;
            coverW = img.getWidth();
            coverH = img.getHeight();
        } catch (Throwable ignored) {
            coverTexture = null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int x = left();
        int y = top();
        g.fill(x - 1, y - 1, x + panelW + 1, y + panelH + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + panelW, y + panelH, Theme.listBg());
        if (panelW < 420) {
            g.drawString(font, this.title, x + 16, y + 14, Theme.c(0xFFFFFF), false);
        } else {
            g.drawCenteredString(font, this.title, this.width / 2, y + 14, Theme.c(0xFFFFFF));
        }

        coverX = x + 24;
        coverY = y + 42;
        g.fill(coverX - 1, coverY - 1, coverX + coverSize + 1, coverY + coverSize + 1, Theme.c(0xFF4A4A60));
        if (coverTexture != null) {
            g.blit(coverTexture, coverX, coverY, coverSize, coverSize, 0, 0, coverW, coverH, coverW, coverH);
        } else {
            g.fill(coverX, coverY, coverX + coverSize, coverY + coverSize, Theme.c(0xFF202030));
            g.drawCenteredString(font, Lang.tr("无封面"), coverX + coverSize / 2, coverY + coverSize / 2 - 4, Theme.c(0xAAAAAA));
        }

        // 封面底部内嵌“更改封面”，不再占用封面下方空间
        int changeH = Math.max(12, Math.min(16, coverSize / 4));
        boolean hover = mouseX >= coverX && mouseX <= coverX + coverSize
                && mouseY >= coverY + coverSize - changeH && mouseY <= coverY + coverSize;
        g.fill(coverX, coverY + coverSize - changeH, coverX + coverSize, coverY + coverSize,
                Theme.c(hover ? 0xB04A4A60 : 0x90333350));
        g.drawCenteredString(font, Lang.tr("更改封面"), coverX + coverSize / 2,
                coverY + coverSize - changeH + 3, Theme.c(0xFFFFFF));

        int fieldX = x + 24 + coverSize + 16;
        int row1 = y + 40;
        int rowGap = Math.max(24, Math.min(36, (panelH - 160) / 3));
        label(g, "曲名", fieldX, row1 - 12);
        label(g, "作者", fieldX, row1 + rowGap - 12);
        label(g, "专辑", fieldX, row1 + rowGap * 2 - 12);
        int lyricsTop = row1 + rowGap * 2 + 18 + 20;
        label(g, "歌词", x + 24, lyricsTop - 12);
        super.render(g, mouseX, mouseY, partial);

        if (!status.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(status, panelW - 48), x + 24, y + panelH - 44,
                    Theme.c(0x88DD88), false);
        }
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(font, Lang.tr(key), x, y, Theme.c(0xAAAAAA), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int changeH = Math.max(12, Math.min(16, coverSize / 4));
        if (mx >= coverX && mx <= coverX + coverSize && my >= coverY + coverSize - changeH && my <= coverY + coverSize) {
            openCoverChoice();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (!saved && onCancel != null) onCancel.run();
        super.onClose();
    }
}
