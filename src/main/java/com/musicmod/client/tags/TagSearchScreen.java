package com.musicmod.client.tags;

import com.mojang.blaze3d.platform.NativeImage;
import com.musicmod.client.gui.CardButton;
import com.musicmod.client.gui.Theme;
import com.musicmod.i18n.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 标签搜索结果页：封面/歌词/歌曲信息共用。 */
public class TagSearchScreen extends Screen {
    public enum Mode { META, COVER, LYRICS }

    private static final int W = 600;
    private static final int H = 390;

    private final Screen parent;
    private final Mode mode;
    private final String initialQuery;
    private final Consumer<TagCandidate> onPick;
    private final List<TagCandidate> results = new ArrayList<>();
    private final List<ResourceLocation> thumbs = new ArrayList<>();
    private final List<int[]> thumbSizes = new ArrayList<>();
    private EditBox queryBox;
    private Button searchBtn, applyBtn, cancelBtn;
    private String status = "";
    private boolean busy = false;
    private int selected = -1;
    private int scrollOffset;

    public TagSearchScreen(Screen parent, Mode mode, String query, Consumer<TagCandidate> onPick) {
        super(Component.literal(Lang.tr("搜索歌曲标签")));
        this.parent = parent;
        this.mode = mode;
        this.initialQuery = query == null ? "" : query;
        this.onPick = onPick;
    }

    private int left() { return (this.width - W) / 2; }
    private int top() { return (this.height - H) / 2; }

    @Override
    protected void init() {
        int x = left();
        int y = top();
        queryBox = new EditBox(this.font, x + 20, y + 40, W - 140, 18, Component.literal(Lang.tr("搜索")));
        queryBox.setMaxLength(Integer.MAX_VALUE);
        queryBox.setValue(initialQuery);
        queryBox.setHint(Component.literal(Lang.tr("曲名 作者")));
        addRenderableWidget(queryBox);
        searchBtn = addRenderableWidget(new CardButton(x + W - 112, y + 39, 92, 20,
                Component.literal(Lang.tr("搜索")), b -> search()));
        applyBtn = addRenderableWidget(new CardButton(x + W - 230, y + H - 28, 100, 20,
                Component.literal(Lang.tr("应用选中")), b -> applySelected()));
        applyBtn.active = false;
        cancelBtn = addRenderableWidget(new CardButton(x + W - 120, y + H - 28, 100, 20,
                Component.literal(Lang.tr("返回")), b -> onClose()));
        if (!initialQuery.isBlank()) search();
    }

    private void search() {
        if (busy) return;
        String q = queryBox.getValue().trim();
        if (q.isEmpty()) {
            status = Lang.tr("请输入搜索内容");
            return;
        }
        busy = true;
        status = Lang.tr("搜索中...");
        clearThumbs();
        results.clear();
        selected = -1;
        scrollOffset = 0;
        applyBtn.active = false;
        OnlineTagService.search(q).thenCompose(list -> {
            if (list.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(list);
            java.util.concurrent.CompletableFuture<?>[] futures = list.stream().limit(24)
                    .map(OnlineTagService::enrich)
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            return java.util.concurrent.CompletableFuture.allOf(futures).thenApply(v -> list);
        }).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            busy = false;
            results.addAll(list);
            status = list.isEmpty() ? Lang.tr("没有找到结果") : Lang.tr("找到 ") + list.size() + Lang.tr(" 个结果");
            if (!list.isEmpty()) select(0);
            loadThumbs();
        })).exceptionally(t -> {
            Minecraft.getInstance().execute(() -> {
                busy = false;
                status = Lang.tr("搜索失败");
            });
            return null;
        });
    }

    private void loadThumbs() {
        clearThumbs();
        for (int i = 0; i < results.size(); i++) {
            TagCandidate c = results.get(i);
            ResourceLocation id = null;
            int tw = 64, th = 64;
            if (c.cover != null && c.cover.length > 0) {
                try {
                    NativeImage img = NativeImage.read(new ByteArrayInputStream(c.cover));
                    tw = img.getWidth();
                    th = img.getHeight();
                    DynamicTexture tex = new DynamicTexture(img);
                    tex.setFilter(true, false);
                    id = new ResourceLocation("musicmod", "tag_search_" + i);
                    Minecraft.getInstance().getTextureManager().register(id, tex);
                } catch (Throwable ignored) {
                    id = null;
                }
            }
            thumbs.add(id);
            thumbSizes.add(new int[]{tw, th});
        }
    }

    private void clearThumbs() {
        for (ResourceLocation id : thumbs) {
            if (id != null) Minecraft.getInstance().getTextureManager().release(id);
        }
        thumbs.clear();
        thumbSizes.clear();
    }

    private void select(int idx) {
        if (idx < 0 || idx >= results.size()) return;
        selected = idx;
        applyBtn.active = true;
    }

    private void applySelected() {
        if (selected < 0 || selected >= results.size()) return;
        TagCandidate c = results.get(selected);
        if (onPick != null) onPick.accept(c);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int x = left();
        int y = top();
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + W, y + H, Theme.listBg());
        g.drawCenteredString(font, this.title, this.width / 2, y + 12, Theme.c(0xFFFFFF));
        super.render(g, mouseX, mouseY, partial);

        int listTop = y + 74;
        int listRight = x + W - 206;
        int shown = 0;
        for (int i = scrollOffset; i < results.size() && shown < 7; i++, shown++) {
            int ry = listTop + shown * 34;
            if (i == selected) g.fill(x + 16, ry - 2, listRight, ry + 30, Theme.selectionBg());
            ResourceLocation thumb = i < thumbs.size() ? thumbs.get(i) : null;
            int[] ts = i < thumbSizes.size() ? thumbSizes.get(i) : new int[]{64, 64};
            if (thumb != null) {
                drawFit(g, thumb, x + 18, ry, 30, ts[0], ts[1]);
            } else {
                g.fill(x + 20, ry, x + 48, ry + 28, Theme.c(0xFF202030));
            }
            TagCandidate c = results.get(i);
            g.drawString(font, font.plainSubstrByWidth(c.title, 190), x + 56, ry + 2, Theme.c(0xFFFFFF), false);
            String sub = c.artist + (c.album.isEmpty() ? "" : "  ·  " + c.album);
            g.drawString(font, font.plainSubstrByWidth(sub, 200), x + 56, ry + 15, Theme.c(0xAAAAAA), false);
            g.drawString(font, c.source, listRight - 62, ry + 8, Theme.c(0x88AACC), false);
        }
        // 选中项大封面，完整等比显示
        if (selected >= 0 && selected < results.size()) {
            int px = listRight + 8, py = listTop - 2, box = 178;
            g.fill(px - 1, py - 1, px + box + 1, py + box + 1, Theme.c(0xFF4A4A60));
            g.fill(px, py, px + box, py + box, Theme.c(0xFF202030));
            ResourceLocation tex = selected < thumbs.size() ? thumbs.get(selected) : null;
            int[] ts = selected < thumbSizes.size() ? thumbSizes.get(selected) : new int[]{0, 0};
            if (tex != null && ts[0] > 0 && ts[1] > 0) {
                drawFit(g, tex, px + 1, py + 1, box - 2, ts[0], ts[1]);
            } else {
                g.drawCenteredString(font, Lang.tr("无封面"), px + box / 2, py + box / 2 - 4, Theme.c(0x888888));
            }
        }
        if (!status.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(status, W - 40), x + 20, y + H - 46, Theme.c(0x88DD88), false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = left();
        int y = top();
        int listTop = y + 74;
        if (mx >= x + 16 && mx <= x + W - 206) {
            for (int shown = 0; shown < Math.min(7, results.size() - scrollOffset); shown++) {
                int i = scrollOffset + shown;
                int ry = listTop + shown * 34;
                if (my >= ry - 2 && my <= ry + 30) {
                    select(i);
                    if (button == 0 && my >= ry + 24) {
                        // 行底部点击不直接应用，保持与列表选择一致
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int max = Math.max(0, results.size() - 7);
        int next = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(delta)));
        if (next != scrollOffset) {
            scrollOffset = next;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void drawFit(GuiGraphics g, ResourceLocation tex, int x, int y, int box, int tw, int th) {
        if (tw <= 0 || th <= 0) return;
        float k = Math.min((float) box / tw, (float) box / th);
        int dw = Math.max(1, Math.round(tw * k));
        int dh = Math.max(1, Math.round(th * k));
        int dx = x + (box - dw) / 2;
        int dy = y + (box - dh) / 2;
        g.blit(tex, dx, dy, dw, dh, 0, 0, tw, th, tw, th);
    }

    @Override
    public void onClose() {
        clearThumbs();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
