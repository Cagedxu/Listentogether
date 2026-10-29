package com.musicmod.client.gui;

import com.musicmod.i18n.Lang;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SMusicActionPacket;
import com.musicmod.server.MusicServerManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.File;

/**
 * 一起听配置界面（0.25.2：同步播放器风格设置页所有配置选项）。
 * <p>
 * 通过 Forge {@code ConfigScreenHandler.ConfigScreenFactory} 注册，
 * 可从模组列表的Lang.tr("配置")按钮打开，也可从精简模式播放器界面进入。
 * <p>
 * 客户端配置随时可改；单人游戏配置（歌曲目录、播放模式等）在本地修改，
 * 多人游戏中服务器配置由服主在服务端文件中编辑（上传/无人行为除外）。
 */
@OnlyIn(Dist.CLIENT)
public class MusicModConfigScreen extends Screen {

    private final Screen parentScreen;
    private boolean singleplayer;
    private MusicModConfig.UiStyle initialStyle; // 打开时的界面风格，用于关闭时判断是否需要切换

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int TITLE_Y = 16;
    private static final int CONTENT_TOP = 38;
    private static final int ITEM_SPACING = 26;
    private static final int LABEL_SPACE = 14; // 分区标签行高（含与控件的间距）

    // 客户端配置控件
    private Button styleBtn;
    private VolumeSlider volumeSlider;
    private Button hudBtn, translationBtn, noteIconBtn, lyricsAlignBtn;
    private Button hudTransPosBtn, detailTransPosBtn, barTransPosBtn;
    private Button queueShowPlayedBtn, hudPosBtn, lyricsClickModeBtn, detailDblClickPlayBtn, themeBtn, pauseSingleplayerBtn;
    

    // 服务器配置控件（仅单人游戏）
    private EditBox songsDirBox;
    private Button applyDirBtn, openSongsDirBtn, playModeBtn;

    // 多人游戏控件
    private Button openCacheDirBtn, uploadBtn, emptyServerBtn;

    private int scrollOffset = 0;

    public MusicModConfigScreen(Screen parentScreen) {
        super(Component.literal(Lang.tr("一起听 · 配置")));
        this.parentScreen = parentScreen;
    }

    private boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    /** 内容总高度（像素，不含 CONTENT_TOP 偏移） */
    private int contentHeight() {
        // 客户端区：标签 + 14 控件
        int h = LABEL_SPACE + 15 * ITEM_SPACING;
        // 服务器标签
        h += LABEL_SPACE;
        if (singleplayer) {
            h += 3 * ITEM_SPACING; // songsDirBox, applyDir+openDir, playMode
        } else {
            h += 2 * ITEM_SPACING; // openCacheDir, upload
            if (isOp()) h += ITEM_SPACING; // emptyServer
            else h += 12; // 非 OP 提示文字空间
        }
        return h + 4;
    }

    /** 可见区域高度 */
    private int visibleHeight() {
        return this.height - CONTENT_TOP - 36;
    }

    @Override
    protected void init() {
        super.init();
        this.font = ModFonts.get();
        singleplayer = Minecraft.getInstance().getSingleplayerServer() != null;
        initialStyle = MusicModConfig.getUiStyle();
        scrollOffset = 0;

        int leftX = this.width / 2 - BTN_W / 2;

        // ---- 客户端配置 ----
        styleBtn = Button.builder(styleBtnText(), btn -> {
            MusicModConfig.UiStyle next = MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER
                    ? MusicModConfig.UiStyle.CLASSIC : MusicModConfig.UiStyle.PLAYER;
            MusicModConfig.setUiStyle(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(styleBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(styleBtn);

        volumeSlider = new VolumeSlider(leftX, 0, BTN_W, BTN_H,
                MusicModConfig.getClientVolume());
        this.addRenderableWidget(volumeSlider);

        hudBtn = Button.builder(hudBtnText(), btn -> {
            MusicModConfig.cycleHudMode();
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(hudBtn);

        translationBtn = Button.builder(translationBtnText(), btn -> {
            MusicModConfig.setShowTranslation(!MusicModConfig.getShowTranslation());
            MusicModConfig.saveClientConfig();
            btn.setMessage(translationBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(translationBtn);

        noteIconBtn = Button.builder(noteIconBtnText(), btn -> {
            MusicModConfig.setShowHudNoteIcon(!MusicModConfig.getShowHudNoteIcon());
            MusicModConfig.saveClientConfig();
            btn.setMessage(noteIconBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(noteIconBtn);

        lyricsAlignBtn = Button.builder(lyricsAlignBtnText(), btn -> {
            MusicModConfig.HudLyricsAlign next = MusicModConfig.getHudLyricsAlign()
                    == MusicModConfig.HudLyricsAlign.CENTER
                    ? MusicModConfig.HudLyricsAlign.LEFT
                    : MusicModConfig.HudLyricsAlign.CENTER;
            MusicModConfig.setHudLyricsAlign(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(lyricsAlignBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(lyricsAlignBtn);

        hudTransPosBtn = Button.builder(hudTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getHudLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setHudLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudTransPosBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(hudTransPosBtn);

        detailTransPosBtn = Button.builder(detailTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getDetailLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setDetailLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(detailTransPosBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(detailTransPosBtn);

        barTransPosBtn = Button.builder(barTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getBarLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setBarLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(barTransPosBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(barTransPosBtn);

        queueShowPlayedBtn = Button.builder(queueShowPlayedBtnText(), btn -> {
            MusicModConfig.setQueueShowPlayed(!MusicModConfig.getQueueShowPlayed());
            MusicModConfig.saveClientConfig();
            btn.setMessage(queueShowPlayedBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(queueShowPlayedBtn);

        hudPosBtn = Button.builder(hudPosBtnText(), btn -> {
            MusicModConfig.cycleHudPosition();
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudPosBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(hudPosBtn);

        lyricsClickModeBtn = Button.builder(lyricsClickModeBtnText(), btn -> {
            MusicModConfig.LyricsClickMode next = MusicModConfig.getLyricsClickMode()
                    == MusicModConfig.LyricsClickMode.SINGLE
                    ? MusicModConfig.LyricsClickMode.DOUBLE : MusicModConfig.LyricsClickMode.SINGLE;
            MusicModConfig.setLyricsClickMode(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(lyricsClickModeBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(lyricsClickModeBtn);

        detailDblClickPlayBtn = Button.builder(detailDblClickPlayBtnText(), btn -> {
            MusicModConfig.setDetailDblClickPlay(!MusicModConfig.getDetailDblClickPlay());
            MusicModConfig.saveClientConfig();
            btn.setMessage(detailDblClickPlayBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(detailDblClickPlayBtn);

        themeBtn = Button.builder(themeBtnText(), btn -> {
            MusicModConfig.UiTheme next = MusicModConfig.getUiTheme() == MusicModConfig.UiTheme.DARK
                    ? MusicModConfig.UiTheme.LIGHT : MusicModConfig.UiTheme.DARK;
            MusicModConfig.setUiTheme(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(themeBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(themeBtn);

        pauseSingleplayerBtn = Button.builder(pauseBtnText(), btn -> {
            MusicModConfig.setPauseSingleplayer(!MusicModConfig.isPauseSingleplayer());
            MusicModConfig.saveClientConfig();
            btn.setMessage(pauseBtnText());
        }).bounds(leftX, 0, BTN_W, BTN_H).build();
        this.addRenderableWidget(pauseSingleplayerBtn);

        // ---- 服务器配置 ----
        if (singleplayer) {
            songsDirBox = new BorderedEditBox(this.font, leftX, 0, BTN_W, BTN_H,
                    Component.literal(Lang.tr("歌曲目录")));
            songsDirBox.setMaxLength(256);
            songsDirBox.setBordered(false);
            songsDirBox.setValue(MusicModConfig.getServerSongsDir());
            this.addRenderableWidget(songsDirBox);

            applyDirBtn = Button.builder(
                    Component.literal(Lang.tr("应用目录并重新扫描")), btn -> {
                        String dir = songsDirBox.getValue().trim();
                        if (!dir.isEmpty()) {
                            MusicModConfig.setServerSongsDir(dir);
                            MusicModConfig.saveServerConfig();
                            MusicServerManager.getInstance().reloadLibrary(dir);
                        }
                    }).bounds(leftX, 0, BTN_W - 84, BTN_H).build();
            this.addRenderableWidget(applyDirBtn);

            openSongsDirBtn = Button.builder(Component.literal(Lang.tr("§b📂 打开")), btn -> {
                openFolder(resolveSongsDir());
            }).bounds(leftX + BTN_W - 80, 0, 80, BTN_H).build();
            this.addRenderableWidget(openSongsDirBtn);

            playModeBtn = Button.builder(playModeBtnText(), btn -> {
                com.musicmod.PlayMode next = MusicModConfig.getServerDefaultPlayMode().next();
                MusicModConfig.setServerDefaultPlayMode(next);
                MusicModConfig.saveServerConfig();
                MusicServerManager.getInstance().setPlayMode(next);
                btn.setMessage(playModeBtnText());
            }).bounds(leftX, 0, BTN_W, BTN_H).build();
            this.addRenderableWidget(playModeBtn);
        } else {
            openCacheDirBtn = Button.builder(Component.literal(Lang.tr("§b📂 打开缓存歌曲文件夹")), btn -> {
                openFolder(new File(Minecraft.getInstance().gameDirectory, "musicmod_cache"));
            }).bounds(leftX, 0, BTN_W, BTN_H).build();
            this.addRenderableWidget(openCacheDirBtn);

            uploadBtn = Button.builder(Component.literal(Lang.tr("§a⬆ 上传音乐到服务器")), btn -> {
                com.musicmod.client.ClientFileUploader.openUploadDialog();
            }).bounds(leftX, 0, BTN_W, BTN_H).build();
            this.addRenderableWidget(uploadBtn);
            if (!isOp()) uploadBtn.active = false;

            if (isOp()) {
                emptyServerBtn = Button.builder(emptyServerBtnText(), btn -> {
                    com.musicmod.EmptyServerBehavior next =
                            ClientMusicManager.getInstance().getLastEmptyBehavior().next();
                    ClientMusicManager.getInstance().onEmptyBehaviorReceived(next);
                    btn.setMessage(emptyServerBtnText());
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_SET_EMPTY_BEHAVIOR, next.name()));
                }).bounds(leftX, 0, BTN_W, BTN_H).build();
                this.addRenderableWidget(emptyServerBtn);
            }
        }

        // ---- 完成按钮（固定底部，不随滚动移动） ----
        this.addRenderableWidget(Button.builder(Component.literal(Lang.tr("完成")), btn -> onClose())
                .bounds(this.width / 2 - 75, this.height - 28, 150, BTN_H).build());
    }

    /** 将控件定位到滚动后的位置，超出可见区域的隐藏 */
    private void positionWidget(AbstractWidget w, int baseY) {
        if (w == null) return;
        int y = CONTENT_TOP + baseY - scrollOffset;
        w.setY(y);
        int visH = visibleHeight();
        w.visible = y + w.getHeight() > CONTENT_TOP && y < CONTENT_TOP + visH;
    }

    @Override
    public void tick() {
        super.tick();
        if (songsDirBox != null) songsDirBox.tick();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 限制滚动范围
        int maxScroll = Math.max(0, contentHeight() - visibleHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

        renderBackground(guiGraphics);

        // 定位所有控件（客户端标签占 LABEL_SPACE，控件从 LABEL_SPACE 开始）
        int y = LABEL_SPACE;
        positionWidget(styleBtn, y); y += ITEM_SPACING;
        positionWidget(volumeSlider, y); y += ITEM_SPACING;
        positionWidget(hudBtn, y); y += ITEM_SPACING;
        positionWidget(translationBtn, y); y += ITEM_SPACING;
        positionWidget(noteIconBtn, y); y += ITEM_SPACING;
        positionWidget(lyricsAlignBtn, y); y += ITEM_SPACING;
        positionWidget(hudTransPosBtn, y); y += ITEM_SPACING;
        positionWidget(detailTransPosBtn, y); y += ITEM_SPACING;
        positionWidget(barTransPosBtn, y); y += ITEM_SPACING;
        positionWidget(queueShowPlayedBtn, y); y += ITEM_SPACING;
        positionWidget(hudPosBtn, y); y += ITEM_SPACING;
        positionWidget(lyricsClickModeBtn, y); y += ITEM_SPACING;
        positionWidget(detailDblClickPlayBtn, y); y += ITEM_SPACING;
        positionWidget(themeBtn, y); y += ITEM_SPACING;
        positionWidget(pauseSingleplayerBtn, y); y += ITEM_SPACING;
        if (pauseSingleplayerBtn != null) pauseSingleplayerBtn.visible &= singleplayer;
        // 精简模式不提供主题功能
        if (themeBtn != null) themeBtn.visible &= MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER;
        // 服务器配置区（先留出标签空间）
        y += LABEL_SPACE;
        if (singleplayer) {
            positionWidget(songsDirBox, y); y += ITEM_SPACING;
            positionWidget(applyDirBtn, y);
            positionWidget(openSongsDirBtn, y); y += ITEM_SPACING;
            positionWidget(playModeBtn, y);
        } else {
            positionWidget(openCacheDirBtn, y); y += ITEM_SPACING;
            positionWidget(uploadBtn, y); y += ITEM_SPACING;
            if (emptyServerBtn != null) {
                positionWidget(emptyServerBtn, y);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 绘制标题和分区标签
        int cx = this.width / 2;
        UiText.centered(guiGraphics, this.font, this.title, cx, TITLE_Y, 0xFFD700);

        // 客户端配置标签（baseY=0，控件从 LABEL_SPACE 开始，间距充足不重叠）
        int clientLabelY = CONTENT_TOP - scrollOffset;
        if (clientLabelY >= TITLE_Y + 10) {
            UiText.centered(guiGraphics, this.font, Lang.tr("§e—— 客户端配置 ——"), cx, clientLabelY, 0xFFFFFF);
        }

        // 服务器配置标签（在服务器区控件上方 LABEL_SPACE 处）
        int serverLabelBaseY = LABEL_SPACE + 15 * ITEM_SPACING; // 客户端标签+15 个控件之后
        int serverLabelY = CONTENT_TOP + serverLabelBaseY - scrollOffset;
        if (serverLabelY >= TITLE_Y + 10 && serverLabelY < CONTENT_TOP + visibleHeight()) {
            String text = singleplayer ? Lang.tr("§e—— 单人游戏配置 ——")
                    : Lang.tr("§e—— 服务器配置 ——");
            UiText.centered(guiGraphics, this.font, text, cx, serverLabelY, 0xFFFFFF);
        }

        // 多人非 OP 提示
        if (!singleplayer && !isOp()) {
            int hintY = CONTENT_TOP + serverLabelBaseY + LABEL_SPACE + 2 * ITEM_SPACING - scrollOffset;
            if (hintY >= TITLE_Y + 10 && hintY < CONTENT_TOP + visibleHeight()) {
                UiText.centered(guiGraphics, this.font,
                        Lang.tr("§7服务器配置请在服务端 config/musicmod-server.toml 中编辑"),
                        cx, hintY, 0xAAAAAA);
            }
        }

        // 滚动条
        if (maxScroll > 0) {
            int visH = visibleHeight();
            int barX = this.width - 8;
            float ratio = (float) scrollOffset / maxScroll;
            int barH = Math.max(12, visH * visH / contentHeight());
            int barY = CONTENT_TOP + (int) ((visH - barH) * ratio);
            guiGraphics.fill(barX, CONTENT_TOP, barX + 4, CONTENT_TOP + visH, 0x40000000);
            guiGraphics.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (songsDirBox != null && songsDirBox.visible
                && !(mouseX >= songsDirBox.getX() && mouseX < songsDirBox.getX() + songsDirBox.getWidth()
                && mouseY >= songsDirBox.getY() && mouseY < songsDirBox.getY() + songsDirBox.getHeight())) {
            songsDirBox.setFocused(false);
            if (getFocused() instanceof EditBox) setFocused(null);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, contentHeight() - visibleHeight());
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll,
                    scrollOffset - (int) Math.signum(delta) * 26));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        // 界面关闭时统一落盘
        MusicModConfig.saveClientConfig();
        MusicModConfig.saveServerConfig();
    }

    @Override
    public boolean isPauseScreen() {
        return com.musicmod.config.MusicModConfig.isPauseSingleplayer();
    }

    @Override
    public void onClose() {
        // 界面风格被切换时，返回直接打开新风格的界面
        MusicModConfig.UiStyle cur = MusicModConfig.getUiStyle();
        if (cur != initialStyle) {
            Minecraft.getInstance().setScreen(
                    cur == MusicModConfig.UiStyle.PLAYER
                            ? new MusicAppScreen()
                            : new MusicPlayerScreen());
        } else {
            Minecraft.getInstance().setScreen(parentScreen);
        }
    }

    // ==================== 工具方法 ====================

    private File resolveSongsDir() {
        String dir = MusicModConfig.getServerSongsDir();
        if (dir != null && !dir.isEmpty()) return new File(dir);
        return new File(Minecraft.getInstance().gameDirectory, "musicmod_songs");
    }

    private void openFolder(File folder) {
        if (!folder.exists()) folder.mkdirs();
        Util.getPlatform().openFile(folder);
    }

    // ==================== 按钮文案 ====================

    private Component styleBtnText() {
        return Component.literal(Lang.tr("界面风格: ") + (MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER
                ? Lang.tr("播放器风格") : Lang.tr("精简模式")));
    }

    private Component hudBtnText() {
        MusicModConfig.HudMode mode = MusicModConfig.getHudMode();
        String modeText = mode == MusicModConfig.HudMode.LYRICS ? Lang.tr("§b歌词")
                : mode == MusicModConfig.HudMode.SONG_NAME ? Lang.tr("§a歌名")
                : mode == MusicModConfig.HudMode.CARD ? Lang.tr("§d卡片") : Lang.tr("§c关闭");
        return Component.literal(Lang.tr("HUD 显示: ") + modeText);
    }

    private Component translationBtnText() {
        return Component.literal(Lang.tr("翻译歌词: ") + (MusicModConfig.getShowTranslation() ? Lang.tr("§a显示") : Lang.tr("§7仅原文")));
    }

    private Component noteIconBtnText() {
        return Component.literal(Lang.tr("音符图标: ") + (MusicModConfig.getShowHudNoteIcon() ? Lang.tr("§a显示") : Lang.tr("§7隐藏")));
    }

    private Component lyricsAlignBtnText() {
        return Component.literal(Lang.tr("歌词对齐: ") + (MusicModConfig.getHudLyricsAlign() == MusicModConfig.HudLyricsAlign.CENTER
                ? Lang.tr("居中") : Lang.tr("左对齐")));
    }

    private Component hudTransPosBtnText() {
        return Component.literal(Lang.tr("HUD 翻译位置: ") + (MusicModConfig.getHudLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("下方") : Lang.tr("后方")));
    }

    private Component detailTransPosBtnText() {
        return Component.literal(Lang.tr("详情页翻译位置: ") + (MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("下方") : Lang.tr("后方")));
    }

    private Component barTransPosBtnText() {
        return Component.literal(Lang.tr("进度条翻译位置: ") + (MusicModConfig.getBarLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("下方") : Lang.tr("后方")));
    }

    private Component hudPosBtnText() {
        MusicModConfig.HudPosition pos = MusicModConfig.getHudPosition();
        String label = pos == MusicModConfig.HudPosition.TOP_LEFT ? Lang.tr("左上")
                : pos == MusicModConfig.HudPosition.TOP_RIGHT ? Lang.tr("右上")
                : pos == MusicModConfig.HudPosition.BOTTOM_LEFT ? Lang.tr("左下")
                : pos == MusicModConfig.HudPosition.BOTTOM_RIGHT ? Lang.tr("右下")
                : Lang.tr("顶部中央");
        return Component.literal(Lang.tr("HUD 位置: ") + label);
    }

    private Component lyricsClickModeBtnText() {
        return Component.literal(Lang.tr("歌词跳转: ") + (MusicModConfig.getLyricsClickMode()
                == MusicModConfig.LyricsClickMode.SINGLE ? Lang.tr("单击") : Lang.tr("双击")));
    }

    private Component detailDblClickPlayBtnText() {
        return Component.literal(Lang.tr("详情双击播放: ") + (MusicModConfig.getDetailDblClickPlay()
                ? Lang.tr("开启") : Lang.tr("关闭")));
    }

    private Component pauseBtnText() {
        return Component.literal(Lang.tr("§7打开界面暂停: ")
                + (MusicModConfig.isPauseSingleplayer()
                        ? "§a" + Lang.tr("开启") : "§8" + Lang.tr("关闭")));
    }

    private Component themeBtnText() {
        return Component.literal(Lang.tr("界面主题: ") + (MusicModConfig.getUiTheme()
                == MusicModConfig.UiTheme.LIGHT ? Lang.tr("浅色") : Lang.tr("深色")));
    }

    private Component queueShowPlayedBtnText() {
        return Component.literal(Lang.tr("队列显示已播放: ") + (MusicModConfig.getQueueShowPlayed()
                ? Lang.tr("§a显示") : Lang.tr("§7隐藏")));
    }

    private Component playModeBtnText() {
        com.musicmod.PlayMode pm = MusicModConfig.getServerDefaultPlayMode();
        return Component.literal(Lang.tr("播放模式: ") + pm.getDisplayName());
    }

    private Component emptyServerBtnText() {
        return Component.literal(Lang.tr("无人时: ") + ClientMusicManager.getInstance().getLastEmptyBehavior().getDisplayName());
    }
}