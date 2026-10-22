package com.musicmod.client.gui;

import com.musicmod.i18n.Lang;

import com.musicmod.client.ClientFavorites;
import com.musicmod.client.ClientMusicManager;
import com.musicmod.client.ClientPlaylists;
import com.musicmod.client.ClientTagStore;
import com.musicmod.client.CoverTextureManager;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SMusicActionPacket;
import com.musicmod.network.packet.C2SPlaylistActionPacket;
import com.musicmod.network.packet.C2SRequestListPacket;
import com.musicmod.playlist.Playlist;
import com.musicmod.playlist.PlaylistLibrary;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.*;

/**
 * 播放器风格界面（1.3.0 新增，默认风格）。
 * <p>
 * 布局：左侧功能导航（全部歌曲/歌手/专辑/设置）+ 右侧内容区 + 底部播放栏。
 * 支持歌曲标签展示（标题/艺术家/专辑/时长）、封面、歌词、本地收藏。
 * 经典风格见 {@link MusicPlayerScreen}，可在设置中切换。
 */
@OnlyIn(Dist.CLIENT)
public class MusicAppScreen extends Screen {

    private static final int SIDEBAR_W = 92;
    private static final int PLAYER_BAR_H = 42;
    private static final int ROW_H = 13;
    private static final int LIST_TOP = 26;

    // ==================== 窗口化布局 ====================
    /** 标题栏高度（窗口样式） */
    private static final int WINDOW_TITLE_H = 22;
    /** 窗口最大尺寸，避免全屏铺满 */
    private static final int WINDOW_MAX_W = 600;
    private static final int WINDOW_MAX_H = 380;

    /** 真实屏幕尺寸（用于外部遮罩、窗口边框与鼠标命中） */
    private int realWidth;
    private int realHeight;
    /** 窗口在真实屏幕中的位置与尺寸 */
    private int windowX;
    private int windowY;
    private int windowW;
    private int windowH;
    /** UI 内部使用的虚拟尺寸（窗口宽 × (窗口高 - 标题栏)） */
    private int uiW;
    private int uiH;

    /** 导航页 */
    private enum Nav { ALL, PLAYLIST, ARTIST, ALBUM, DETAIL, SETTINGS }

    /** 子页面（歌手/专辑点入后的歌曲列表） */
    private static class SubPage {
        final String title;
        final List<String> songs;
        SubPage(String title, List<String> songs) {
            this.title = title;
            this.songs = songs;
        }
    }

    private Nav nav = Nav.ALL;
    private SubPage subPage = null;

    private String selectedSong = null;   // 选中的歌曲名
    private String detailSong = null;     // 详情面板显示的歌曲名（null=面板关闭）
    private int scrollOffset = 0;
    private long lastClickTime = 0;
    private String lastClickedSong = null;

    // 播放栏控件
    private Button prevBtn, playPauseBtn, stopBtn, nextBtn, playModeBtn, refreshBtn;
    private VolumeSlider volumeSlider;

    // 设置页控件
    private Button styleBtn, hudBtn, settingsPlayModeBtn, applyDirBtn, translationBtn;
    private Button noteIconBtn, openSongsDirBtn, openCacheDirBtn, uploadBtn;
    private Button lyricsAlignBtn, emptyServerBtn;
    private Button hudTransPosBtn, detailTransPosBtn, barTransPosBtn;
    // 0.27.0：歌词点击模式 & 详情双击播放
    private Button lyricsClickModeBtn, detailDblClickPlayBtn;
    // 0.29.0：HUD 显示位置
    private Button hudPosBtn;
    private EditBox songsDirBox;
    private VolumeSlider settingsVolumeSlider;
    private boolean singleplayer;

    // 进度条拖动状态（1.4.0）
    private boolean progressDragging = false;
    private long dragPreviewMs = -1;   // 拖动中的预览位置，释放时发送 seek

    // 详情面板 - 歌词滚动（1.6.0：像素级滚动 + 滚动条 + 自动跟随）
    private int lyricsScrollPx = 0;
    private boolean lyricsAutoFollow = true;
    private boolean lyricsBarDragging = false;
    private boolean lyricsContentDragging = false;   // 0.26.0：歌词内容区拖拽（触屏式滚动 + 延迟点击）
    private double lyricsDragStartY = 0;
    private int lyricsDragStartScroll = 0;
    private boolean lyricsDragMoved = false;

    // 0.14.0：所有滚动区域支持鼠标拖拽（滚动条拖拽 + 内容区拖拽）
    private enum DragTarget { NONE, LIST_BAR, LIST_CONTENT, SETTINGS_BAR }
    private DragTarget dragTarget = DragTarget.NONE;
    private double dragStartY = 0;
    private int dragStartScroll = 0;
    private boolean dragMoved = false;
    // 当前主列表滚动参数（render 时更新，供鼠标交互使用）
    private int curScrollTotal = 0;
    private int curScrollVisible = 0;
    private int curScrollRowH = ROW_H;
    private int curScrollYOffset = 0;

    // 0.23.0：设置页滚动（窗口过矮时选项可滚动）
    private int settingsScroll = 0;

    // 0.27.0：搜索功能（右上角放大镜图标，点击展开搜索框）
    private boolean searchOpen = false;
    private EditBox searchBox = null;

    // 0.28.0：排序标签命中区（render 时更新，mouseClicked 时判定）
    private int sortLabelX = -1;
    private int sortLabelW = 0;

    // 0.27.0：歌词选取行（DOUBLE 点击模式 / 详情双击播放时，第一次点击选中的行索引，-1=无）
    private int selectedLyricLine = -1;
    // 0.27.0：上次歌词点击时间戳（用于双击判定）
    private long lastLyricClickTime = 0;

    // ==================== 歌单 ====================
    private static final int PLAYLIST_TOOLBAR_H = 26;
    private static final int PLAYLIST_ROW_H = 18;
    /** 歌单列表页右侧「新建 / 编辑」面板宽度上限 */
    private static final int PLAYLIST_PANEL_W = 190;
    private static final int PLAYLIST_POPUP_W = 260;
    private static final int PLAYLIST_POPUP_ROW_H = 16;
    private static final int PLAYLIST_POPUP_MAX_ROWS = 8;

    /** 歌单列表页当前选中的歌单 ID */
    private String selectedPlaylistId = null;
    /** 正在查看详情的歌单 ID（null=歌单列表页） */
    private String openedPlaylistId = null;
    /** 待确认删除的歌单 ID（二次点击删除按钮才真正删除） */
    private String playlistDeleteConfirmId = null;
    /** 歌单列表双击判定 */
    private long lastPlaylistClickTime = 0;
    private String lastClickedPlaylistId = null;

    private EditBox playlistNameBox;
    private Button playlistCreateBtn, playlistRenameBtn, playlistDeleteBtn;

    /** 添加到歌单弹窗对应的歌曲名（null=弹窗关闭） */
    private String addToPlaylistSong = null;
    private int playlistPopupScroll = 0;

    /** 歌曲列表底部「添加到歌单」点击区域（render 时更新，mouseClicked 时判定） */
    private int addSongHintX = -1;
    private int addSongHintW = 0;
    /** 详情面板「添加到歌单」点击区域 */
    private int detailAddSongX = -1;
    private int detailAddSongW = 0;
    /** 歌单详情页工具栏点击区域 */
    private int playlistPlayX = -1;
    private int playlistPlayW = 0;
    private int playlistAddX = -1;
    private int playlistAddY = -1;
    private int playlistAddW = 0;
    private int playlistAddH = 0;
    private int playlistDeleteX = -1;
    private int playlistDeleteW = 0;

    public MusicAppScreen() {
        this(false);
    }

    /**
     * @param openPlaylists true 时直接打开歌单页（经典风格界面「歌单」按钮使用）
     */
    public MusicAppScreen(boolean openPlaylists) {
        super(Component.literal(Lang.tr("一起听 · 音乐播放器")));
        if (openPlaylists) {
            this.nav = Nav.PLAYLIST;
        }
    }

    private boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    private int playerBarY() {
        return this.height - PLAYER_BAR_H;
    }

    /** 播放模式按钮宽度（0.25.2：改为文字显示，需更宽） */
    private static final int PLAY_MODE_BTN_W = 48;

    /** 0.28.1：右侧控件区总宽（右边距4 + 4按钮5*22 + 播放模式48 + 间距2 + 刷新24 + 音量60 + 间距4 + 进度条间隔24） */
    private static final int RIGHT_CONTROLS_W = 4 + 5 * 22 + PLAY_MODE_BTN_W + 2 + 60 + 4 + 24; // 252
    /** 0.28.1：播放栏左侧固定区（左边距6 + 封面30 + 间距8） */
    private static final int LEFT_FIXED_W = 6 + 30 + 8; // 44

    /** 播放栏文本区宽度：宽屏最多170；空间不足时优先保证进度条，文本区向左收缩 */
    private int textAreaW() {
        int avail = this.width - RIGHT_CONTROLS_W - LEFT_FIXED_W;
        return Math.max(70, Math.min(170, avail - 200));
    }

    /** 进度条几何（0.28.1：自适应窗口宽度，窄窗口时收缩文本区保证进度条可见；与 renderPlayerBar 保持一致，供鼠标交互使用） */
    private int progressBarX() { return LEFT_FIXED_W + textAreaW(); }
    private int progressBarW() { return this.width - RIGHT_CONTROLS_W - progressBarX(); }
    private int progressBarY() { return playerBarY() + 24; } // 0.28.3：上移6px

    private int listX() {
        return SIDEBAR_W + 4;
    }

    private int listW() {
        return this.width - SIDEBAR_W - 8;
    }

    /** 歌单列表页右侧「新建 / 编辑」面板宽度（窗口过窄时收缩，保证左侧列表可见） */
    private int playlistPanelW() {
        return Math.max(120, Math.min(PLAYLIST_PANEL_W, listW() - 160));
    }

    private int playlistPanelX() {
        return listX() + listW() - playlistPanelW();
    }

    /** 歌单列表区域右边界：列表页让出右侧编辑面板，详情页占满内容区 */
    private int playlistListRight() {
        Playlist opened = openedPlaylist();
        return opened == null ? playlistPanelX() - 6 : listX() + listW();
    }

    private int listH() {
        // 底部留 16px：12px 提示行 + 4px 边距，避免提示文字覆盖列表最后一行
        return playerBarY() - LIST_TOP - 16;
    }

    // ---- 0.27.4：搜索框 / 搜索图标几何（渲染与鼠标命中共用，避免位置不一致） ----
    private static final int SEARCH_BOX_W = 100;
    private static final int SEARCH_BOX_H = 14;
    private static final int SEARCH_BOX_Y = 5;
    /** 搜索图标左侧预留区宽度（图标本身 + 间距） */
    private static final int SEARCH_ICON_ZONE = 24;
    /** 0.27.5：图标放大倍率（原字符过小） */
    private static final float SEARCH_ICON_SCALE = 1.5f;
    /** 0.27.5：图标顶部 y，使放大后的图标与「共 N 首」文字（y=8）垂直居中对齐 */
    private static final int SEARCH_ICON_Y = 6;

    private int searchBoxX() {
        return listX() + listW() - SEARCH_ICON_ZONE - SEARCH_BOX_W;
    }

    private int searchIconX() {
        return listX() + listW() - 18;
    }

    private boolean inSearchBox(int mx, int my) {
        int bx = searchBoxX();
        return mx >= bx - 1 && mx <= bx + SEARCH_BOX_W + 1
                && my >= SEARCH_BOX_Y - 2 && my <= SEARCH_BOX_Y + SEARCH_BOX_H + 2;
    }

    private boolean inSearchIcon(int mx, int my) {
        // 0.27.5：命中区与放大后的图标几何一致（searchIconX + SEARCH_ICON_Y + 1.5 倍字宽高，外加 2px 余量）
        int ix = searchIconX();
        int iw = (int) (9 * SEARCH_ICON_SCALE) + 4;
        int ih = (int) (9 * SEARCH_ICON_SCALE) + 4;
        return mx >= ix - 2 && mx <= ix + iw && my >= SEARCH_ICON_Y - 2 && my <= SEARCH_ICON_Y + ih;
    }

    /** 收起搜索：清空过滤、隐藏输入框并交还焦点 */
    private void closeSearch() {
        searchOpen = false;
        scrollOffset = 0;
        if (searchBox != null) {
            searchBox.setValue("");
            searchBox.setVisible(false);
            searchBox.setFocused(false);
        }
        if (getFocused() == searchBox) setFocused(null);
    }

    /** 计算窗口尺寸：居中、最大 640×400，四周保留边距，形成“窗口”而不是全屏 */
    private void computeWindowBounds() {
        int w = Math.min(WINDOW_MAX_W, realWidth - 80);
        int h = Math.min(WINDOW_MAX_H, realHeight - 60);
        // 极小窗口时至少保留可用空间，但不能超过真实屏幕
        w = Math.max(Math.min(320, realWidth), Math.min(w, realWidth));
        h = Math.max(Math.min(240, realHeight), Math.min(h, realHeight));
        windowW = w;
        windowH = h;
        windowX = (realWidth - windowW) / 2;
        windowY = (realHeight - windowH) / 2;
        uiW = windowW;
        uiH = Math.max(120, windowH - WINDOW_TITLE_H);
    }

    @Override
    protected void init() {
        // Minecraft 传入的是真实屏幕尺寸；先计算窗口，再把控件布局切换到窗口内部坐标
        realWidth = Math.max(1, this.width);
        realHeight = Math.max(1, this.height);
        computeWindowBounds();
        this.width = uiW;
        this.height = uiH;

        super.init();
        singleplayer = Minecraft.getInstance().getSingleplayerServer() != null;

        // ---- 左侧导航 ----
        int nx = 4;
        int nw = SIDEBAR_W - 8;
        addNavButton(Lang.tr("♪ 全部歌曲"), nx, 8, nw, Nav.ALL);
        addNavButton(Lang.tr("≡ 歌单"), nx, 32, nw, Nav.PLAYLIST);
        addNavButton(Lang.tr("🎤 歌手"), nx, 56, nw, Nav.ARTIST);
        addNavButton(Lang.tr("💿 专辑"), nx, 80, nw, Nav.ALBUM);
        addNavButton(Lang.tr("📄 歌曲详情"), nx, 104, nw, Nav.DETAIL);
        addNavButton(Lang.tr("⚙ 设置"), nx, 128, nw, Nav.SETTINGS);

        // ---- 底部播放栏 ----
        int by = playerBarY() + 9;
        int bx = this.width - 4 - 5 * 22 - PLAY_MODE_BTN_W - 2 - 60 - 4;
        prevBtn = addBtn("⏮", bx, by, 20, () -> sendAction(C2SMusicActionPacket.ACTION_PREV));
        bx += 22;
        playPauseBtn = addBtn("▶", bx, by, 20, this::onPlayPauseClicked);
        bx += 22;
        stopBtn = addBtn("⏹", bx, by, 20, () -> sendAction(C2SMusicActionPacket.ACTION_STOP));
        bx += 22;
        nextBtn = addBtn("⏭", bx, by, 20, () -> sendAction(C2SMusicActionPacket.ACTION_NEXT));
        bx += 22;
        playModeBtn = addBtn("", bx, by, PLAY_MODE_BTN_W,
                () -> sendAction(C2SMusicActionPacket.ACTION_PLAY_MODE));
        bx += PLAY_MODE_BTN_W + 2;
        // 0.14.0：刷新歌曲列表按钮（所有玩家可用，非 OP 限制）
        refreshBtn = addBtn("⟳", bx, by, 20, this::requestSongList);
        volumeSlider = new VolumeSlider(bx + 24, by, 60, 20,
                MusicModConfig.getClientVolume());
        this.addRenderableWidget(volumeSlider);

        boolean op = isOp();
        if (!op) {
            prevBtn.active = false;
            playPauseBtn.active = false;
            stopBtn.active = false;
            nextBtn.active = false;
            playModeBtn.active = false;
        }

        // ---- 设置页控件（初始隐藏，render 中按 nav 控制） ----
        int sx = listX() + 8;
        int sw = Math.min(220, listW() - 16);
        int btnX = sx + 60;  // 按钮右移，左侧留出标签空间
        int btnW = sw - 60;
        styleBtn = Button.builder(styleBtnText(), btn -> {
            MusicModConfig.UiStyle next = MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER
                    ? MusicModConfig.UiStyle.CLASSIC : MusicModConfig.UiStyle.PLAYER;
            MusicModConfig.setUiStyle(next);
            MusicModConfig.saveClientConfig();
            // 立即切换到新风格的界面
            Minecraft.getInstance().setScreen(
                    next == MusicModConfig.UiStyle.CLASSIC
                            ? new MusicPlayerScreen()
                            : new MusicAppScreen());
        }).bounds(btnX, LIST_TOP + 4, btnW, 20).build();
        this.addRenderableWidget(styleBtn);

        settingsVolumeSlider = new VolumeSlider(btnX, LIST_TOP + 30, btnW, 20,
                MusicModConfig.getClientVolume());
        this.addRenderableWidget(settingsVolumeSlider);

        hudBtn = Button.builder(hudBtnText(), btn -> {
            MusicModConfig.cycleHudMode();
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudBtnText());
        }).bounds(btnX, LIST_TOP + 56, btnW, 20).build();
        this.addRenderableWidget(hudBtn);

        // 0.29.0：HUD 显示位置（左上/右上/左下/右下/物品栏上方/顶部中央）
        hudPosBtn = Button.builder(hudPosBtnText(), btn -> {
            MusicModConfig.cycleHudPosition();
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudPosBtnText());
        }).bounds(btnX, LIST_TOP + 82, btnW, 20).build();
        this.addRenderableWidget(hudPosBtn);

        translationBtn = Button.builder(translationBtnText(), btn -> {
            boolean v = !MusicModConfig.getShowTranslation();
            MusicModConfig.setShowTranslation(v);
            MusicModConfig.saveClientConfig();
            btn.setMessage(translationBtnText());
        }).bounds(btnX, LIST_TOP + 108, btnW, 20).build();
        this.addRenderableWidget(translationBtn);

        // 音符图标开关（HUD 歌词左侧音符图标显示/隐藏）
        noteIconBtn = Button.builder(noteIconBtnText(), btn -> {
            boolean v = !MusicModConfig.getShowHudNoteIcon();
            MusicModConfig.setShowHudNoteIcon(v);
            MusicModConfig.saveClientConfig();
            btn.setMessage(noteIconBtnText());
        }).bounds(btnX, LIST_TOP + 134, btnW, 20).build();
        this.addRenderableWidget(noteIconBtn);

        // 歌词对齐方式（HUD 原文/译文：从左往右 or 中心对称）
        lyricsAlignBtn = Button.builder(lyricsAlignBtnText(), btn -> {
            MusicModConfig.HudLyricsAlign next = MusicModConfig.getHudLyricsAlign()
                    == MusicModConfig.HudLyricsAlign.CENTER
                    ? MusicModConfig.HudLyricsAlign.LEFT
                    : MusicModConfig.HudLyricsAlign.CENTER;
            MusicModConfig.setHudLyricsAlign(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(lyricsAlignBtnText());
        }).bounds(btnX, LIST_TOP + 160, btnW, 20).build();
        this.addRenderableWidget(lyricsAlignBtn);

        // 0.25.0：三处歌词翻译位置（HUD / 详情 / 进度条：下方 or 后方）
        hudTransPosBtn = Button.builder(hudTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getHudLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setHudLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(hudTransPosBtnText());
        }).bounds(btnX, LIST_TOP + 186, btnW, 20).build();
        this.addRenderableWidget(hudTransPosBtn);

        detailTransPosBtn = Button.builder(detailTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getDetailLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setDetailLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(detailTransPosBtnText());
        }).bounds(btnX, LIST_TOP + 212, btnW, 20).build();
        this.addRenderableWidget(detailTransPosBtn);

        barTransPosBtn = Button.builder(barTransPosBtnText(), btn -> {
            MusicModConfig.LyricsTransPos next = MusicModConfig.getBarLyricsTransPos()
                    == MusicModConfig.LyricsTransPos.BELOW
                    ? MusicModConfig.LyricsTransPos.AFTER
                    : MusicModConfig.LyricsTransPos.BELOW;
            MusicModConfig.setBarLyricsTransPos(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(barTransPosBtnText());
        }).bounds(btnX, LIST_TOP + 238, btnW, 20).build();
        this.addRenderableWidget(barTransPosBtn);

        // 0.27.0：歌词点击跳转模式（单击直接跳转 / 双击跳转）
        lyricsClickModeBtn = Button.builder(lyricsClickModeBtnText(), btn -> {
            MusicModConfig.LyricsClickMode next = MusicModConfig.getLyricsClickMode()
                    == MusicModConfig.LyricsClickMode.SINGLE
                    ? MusicModConfig.LyricsClickMode.DOUBLE
                    : MusicModConfig.LyricsClickMode.SINGLE;
            MusicModConfig.setLyricsClickMode(next);
            MusicModConfig.saveClientConfig();
            btn.setMessage(lyricsClickModeBtnText());
        }).bounds(btnX, LIST_TOP + 264, btnW, 20).build();
        this.addRenderableWidget(lyricsClickModeBtn);

        // 0.27.0：查看非播放歌曲详情时双击歌词跳转播放
        detailDblClickPlayBtn = Button.builder(detailDblClickPlayBtnText(), btn -> {
            boolean v = !MusicModConfig.getDetailDblClickPlay();
            MusicModConfig.setDetailDblClickPlay(v);
            MusicModConfig.saveClientConfig();
            btn.setMessage(detailDblClickPlayBtnText());
        }).bounds(btnX, LIST_TOP + 290, btnW, 20).build();
        this.addRenderableWidget(detailDblClickPlayBtn);

        if (singleplayer) {
            songsDirBox = new EditBox(this.font, sx, LIST_TOP + 328, sw, 20,
                    Component.literal(Lang.tr("歌曲目录")));
            songsDirBox.setMaxLength(256);
            songsDirBox.setValue(MusicModConfig.getServerSongsDir());
            this.addRenderableWidget(songsDirBox);

            applyDirBtn = Button.builder(Component.literal(Lang.tr("应用目录并重新扫描")), btn -> {
                String dir = songsDirBox.getValue().trim();
                if (!dir.isEmpty()) {
                    MusicModConfig.setServerSongsDir(dir);
                    MusicModConfig.saveServerConfig();
                    com.musicmod.server.MusicServerManager.getInstance().reloadLibrary(dir);
                    requestSongList();
                }
            }).bounds(sx, LIST_TOP + 352, sw - 84, 20).build();
            this.addRenderableWidget(applyDirBtn);

            // 打开歌曲文件夹（本地游戏歌曲目录）
            openSongsDirBtn = Button.builder(Component.literal(Lang.tr("§b📂 打开文件夹")), btn -> {
                openFolder(resolveSongsDir());
            }).bounds(sx + sw - 80, LIST_TOP + 352, 80, 20).build();
            this.addRenderableWidget(openSongsDirBtn);

            settingsPlayModeBtn = Button.builder(playModeBtnText(), btn -> {
                com.musicmod.PlayMode next = MusicModConfig.getServerDefaultPlayMode().next();
                MusicModConfig.setServerDefaultPlayMode(next);
                MusicModConfig.saveServerConfig();
                com.musicmod.server.MusicServerManager.getInstance().setPlayMode(next);
                btn.setMessage(playModeBtnText());
            }).bounds(btnX, LIST_TOP + 394, btnW, 20).build();
            this.addRenderableWidget(settingsPlayModeBtn);
        } else {
            // 多人游戏：打开客户端缓存歌曲文件夹
            openCacheDirBtn = Button.builder(Component.literal(Lang.tr("§b📂 打开缓存歌曲文件夹")), btn -> {
                openFolder(new File(Minecraft.getInstance().gameDirectory, "musicmod_cache"));
            }).bounds(sx, LIST_TOP + 316, sw, 20).build();
            this.addRenderableWidget(openCacheDirBtn);

            // 多人游戏：OP 上传音乐文件到服务器（固定在设置页右上角，不随滚动隐藏）
            uploadBtn = Button.builder(Component.literal(Lang.tr("§a⬆ 上传音乐")), btn -> {
                com.musicmod.client.ClientFileUploader.openUploadDialog();
            }).bounds(0, 0, 90, 20).build();
            this.addRenderableWidget(uploadBtn);
            uploadBtn.active = isOp();
            uploadBtn.visible = false;

            // 服务器无人时的音乐行为（0.25.0：仅 OP 可见，点击发 C2S 包改服务器配置）
            if (isOp()) {
                emptyServerBtn = Button.builder(emptyServerBtnText(), btn -> {
                    com.musicmod.EmptyServerBehavior next =
                            ClientMusicManager.getInstance().getLastEmptyBehavior().next();
                    // 乐观更新本地显示，服务端广播后校正
                    ClientMusicManager.getInstance().onEmptyBehaviorReceived(next);
                    btn.setMessage(emptyServerBtnText());
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_SET_EMPTY_BEHAVIOR, next.name()));
                }).bounds(sx, LIST_TOP + 368, sw, 20).build();
                this.addRenderableWidget(emptyServerBtn);
            }
        }

        // ---- 歌单页右侧「新建 / 编辑」面板控件（位置在 render 中按窗口宽度计算） ----
        playlistNameBox = new EditBox(this.font, 0, LIST_TOP + 22, 160, 18,
                Component.literal(Lang.tr("歌单名称")));
        playlistNameBox.setMaxLength(PlaylistLibrary.MAX_NAME_LENGTH);
        playlistNameBox.setHint(Component.literal(Lang.tr("§7输入歌单名称")));
        playlistNameBox.setVisible(false);
        this.addRenderableWidget(playlistNameBox);

        playlistCreateBtn = addBtn(Lang.tr("新建歌单"), 0, LIST_TOP + 44, 160,
                this::createPlaylistFromInput);
        playlistRenameBtn = addBtn(Lang.tr("重命名"), 0, LIST_TOP + 68, 80,
                this::renameSelectedPlaylist);
        playlistDeleteBtn = addBtn(Lang.tr("删除"), 0, LIST_TOP + 68, 80,
                this::deleteSelectedPlaylist);
        playlistCreateBtn.visible = false;
        playlistRenameBtn.visible = false;
        playlistDeleteBtn.visible = false;

        // 0.27.0：搜索框（右上角放大镜图标点击后展开，初始隐藏）
        searchBox = new EditBox(this.font, 0, 0, 100, 14, Component.literal(Lang.tr("搜索"))) {
            @Override
            public boolean mouseClicked(double mx, double my, int btn) {
                // 搜索框自身可点击聚焦
                return super.mouseClicked(mx, my, btn);
            }
        };
        searchBox.setMaxLength(64);
        searchBox.setVisible(false);
        this.addRenderableWidget(searchBox);

        // 打开时请求最新列表
        requestSongList();
    }

    private Button addNavButton(String text, int x, int y, int w, Nav target) {
        Button b = Button.builder(Component.literal(text), btn -> {
            closeSearch(); // 0.28.2：界面跳转时收起搜索框
            // 切换导航时退出歌单详情/弹窗，避免残留状态
            openedPlaylistId = null;
            selectedPlaylistId = null;
            playlistDeleteConfirmId = null;
            addToPlaylistSong = null;
            addSongHintX = -1;
            addSongHintW = 0;
            if (playlistNameBox != null) playlistNameBox.setValue("");
            if (target == Nav.DETAIL) {
                // 歌曲详情：优先打开列表中选中的歌曲，无选中则回退当前播放歌曲
                String target2 = selectedSong != null ? selectedSong
                        : ClientMusicManager.getInstance().getCurrentSong();
                if (!target2.isEmpty()) {
                    detailSong = target2;
                    resetDetailStates();
                }
                return;
            }
            nav = target;
            subPage = null;
            selectedSong = null;
            scrollOffset = 0;
            settingsScroll = 0;
            detailSong = null;  // 切换导航时关闭详情面板，允许直接跳转歌手/专辑
            resetDetailStates();
        }).bounds(x, y, w, 20).build();
        this.addRenderableWidget(b);
        return b;
    }

    /** 重置详情面板相关状态（打开新详情/关闭面板时调用） */
    private void resetDetailStates() {
        lyricsScrollPx = 0;
        lyricsAutoFollow = true;
        lyricsContentDragging = false;
        lyricsDragMoved = false;
        selectedLyricLine = -1;
        lastLyricClickTime = 0;
    }

    private Button addBtn(String text, int x, int y, int w, Runnable action) {
        Button b = Button.builder(Component.literal(text), btn -> action.run())
                .bounds(x, y, w, 20).build();
        this.addRenderableWidget(b);
        return b;
    }

    @Override
    public void tick() {
        super.tick();
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        if (mgr.isPlaying() && !mgr.isPaused()) {
            playPauseBtn.setMessage(Component.literal("⏸"));
        } else {
            playPauseBtn.setMessage(Component.literal("▶"));
        }
        // 播放栏模式按钮：0.25.2 改为直接显示播放顺序文字（带颜色区分）
        com.musicmod.PlayMode pm = mgr.getLastPlayMode();
        playModeBtn.setMessage(Component.literal(playModeChatColor(pm) + pm.getDisplayName()));
        if (songsDirBox != null) songsDirBox.tick();
        // 0.27.0：搜索框光标闪烁
        if (searchBox != null) searchBox.tick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC：优先关闭「添加到歌单」弹窗，其次返回歌单列表，最后收起搜索框
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (addToPlaylistSong != null) {
                addToPlaylistSong = null;
                return true;
            }
            if (nav == Nav.PLAYLIST && openedPlaylistId != null) {
                Playlist opened = openedPlaylist();
                openedPlaylistId = null;
                selectedPlaylistId = opened != null ? opened.getId() : selectedPlaylistId;
                playlistDeleteConfirmId = null;
                scrollOffset = 0;
                selectedSong = null;
                if (playlistNameBox != null && opened != null) {
                    playlistNameBox.setValue(ClientPlaylists.isBuiltIn(opened.getId())
                            ? "" : Lang.tr(opened.getName()));
                }
                return true;
            }
            // 0.27.0：搜索框展开时 ESC 收起搜索而非退出界面
            if (searchOpen) {
                closeSearch();
                return true;
            }
        }
        // 空格暂停/继续（EditBox 聚焦时不拦截，保证正常输入空格）
        if (keyCode == GLFW.GLFW_KEY_SPACE
                && (songsDirBox == null || !songsDirBox.isFocused())
                && (searchBox == null || !searchBox.isFocused())
                && (playlistNameBox == null || !playlistNameBox.isFocused())) {
            onPlayPauseClicked();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 数据获取 ====================

    private List<String> allSongs() {
        return ClientMusicManager.getInstance().getLastSongList();
    }

    /** 当前页显示的歌曲列表（0.27.0：应用搜索过滤；0.28.0：应用排序） */
    private List<String> currentSongs() {
        if (nav == Nav.PLAYLIST) {
            // 歌单内保持用户添加顺序，不参与全局排序
            Playlist opened = openedPlaylist();
            return opened == null ? Collections.emptyList() : opened.getSongs();
        }
        List<String> songs;
        if (subPage != null) songs = subPage.songs;
        else if (nav == Nav.ALL) songs = allSongs();
        else songs = Collections.emptyList();
        return sortedSongs(filteredSongs(songs));
    }

    /** 当前打开详情的歌单（歌单页使用） */
    private Playlist openedPlaylist() {
        return ClientPlaylists.getInstance().getById(openedPlaylistId);
    }

    /** 歌单列表页当前选中的歌单 */
    private Playlist selectedPlaylist() {
        return ClientPlaylists.getInstance().getById(selectedPlaylistId);
    }

    /** 歌单标题前缀（内置歌单使用专属图标） */
    private String playlistTitle(Playlist playlist) {
        String icon = ClientPlaylists.isFavorites(playlist.getId()) ? "❤ "
                : ClientPlaylists.isRecent(playlist.getId()) ? "🕘 " : "≡ ";
        return icon + Lang.tr(playlist.getName());
    }

    /** 0.28.0：按配置的排序模式排序歌曲列表（0.28.1：默认 TITLE，始终排序） */
    private List<String> sortedSongs(List<String> songs) {
        MusicModConfig.SortMode mode = MusicModConfig.getSortMode();
        if (songs.isEmpty()) return songs;
        List<String> result = new ArrayList<>(songs);
        ClientTagStore tags = ClientTagStore.getInstance();
        Comparator<String> cmp;
        switch (mode) {
            case TITLE:
                cmp = Comparator.comparing(tags::displayTitle, String.CASE_INSENSITIVE_ORDER);
                break;
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
        result.sort(cmp);
        return result;
    }

    /** 0.28.0：排序模式显示名 */
    private static String sortModeName() {
        switch (MusicModConfig.getSortMode()) {
            case TITLE: return Lang.tr("标题");
            case ARTIST: return Lang.tr("歌手");
            case ALBUM: return Lang.tr("专辑");
            case DURATION: return Lang.tr("时长");
            default: return Lang.tr("标题");
        }
    }

    /** 0.27.0：当前搜索关键词（小写，未开启搜索时返回空串） */
    private String searchFilter() {
        if (!searchOpen || searchBox == null) return "";
        return searchBox.getValue().trim().toLowerCase();
    }

    /** 0.27.0：按标题/艺术家过滤歌曲列表 */
    private List<String> filteredSongs(List<String> songs) {
        String filter = searchFilter();
        if (filter.isEmpty()) return songs;
        ClientTagStore tags = ClientTagStore.getInstance();
        List<String> result = new ArrayList<>();
        for (String s : songs) {
            String title = tags.displayTitle(s).toLowerCase();
            String artist = tags.artist(s).toLowerCase();
            if (title.contains(filter) || artist.contains(filter)) {
                result.add(s);
            }
        }
        return result;
    }

    /** 0.27.0：按组名（歌手/专辑名）过滤分组 */
    private Map<String, List<String>> filteredGroups(Map<String, List<String>> groups) {
        String filter = searchFilter();
        if (filter.isEmpty()) return groups;
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (e.getKey().toLowerCase().contains(filter)) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /** 按艺术家分组（"/"分隔的多位歌手会拆分，歌曲同时归属所有歌手） */
    private Map<String, List<String>> groupByArtist() {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : allSongs()) {
            String artist = ClientTagStore.getInstance().artist(s);
            if (artist.isEmpty()) {
                map.computeIfAbsent(Lang.tr("未知艺术家"), k -> new ArrayList<>()).add(s);
                continue;
            }
            // 按 "/" 或全角 "／" 拆分多位歌手，歌曲同时归属每个人
            String[] artists = artist.split("[/／]");
            boolean added = false;
            for (String a : artists) {
                String key = a.trim();
                if (!key.isEmpty()) {
                    map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                    added = true;
                }
            }
            if (!added) {
                map.computeIfAbsent(Lang.tr("未知艺术家"), k -> new ArrayList<>()).add(s);
            }
        }
        return map;
    }

    /** 按专辑分组 */
    private Map<String, List<String>> groupByAlbum() {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : allSongs()) {
            String album = ClientTagStore.getInstance().album(s);
            String key = album.isEmpty() ? Lang.tr("未知专辑") : album;
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 窗口外遮罩 + 标题栏/边框（真实屏幕坐标）
        renderWindowChrome(g, mouseX, mouseY);

        // 以下全部使用窗口内部虚拟坐标
        mouseX -= windowX;
        mouseY -= windowY + WINDOW_TITLE_H;
        g.pose().pushPose();
        g.pose().translate(windowX, windowY + WINDOW_TITLE_H, 0);

        renderBackground(g);

        // 左侧边栏背景
        g.fill(0, 0, SIDEBAR_W, playerBarY(), 0xC0161626);
        g.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, playerBarY(), 0xFF3A3A50);

        // 播放栏背景
        g.fill(0, playerBarY(), this.width, this.height, 0xE01E1E2E);
        g.fill(0, playerBarY(), this.width, playerBarY() + 1, 0xFF3A3A50);

        // 统一控制设置页控件可见性（仅设置页且详情面板关闭时显示）
        boolean showSettings = (nav == Nav.SETTINGS && detailSong == null);
        styleBtn.visible = showSettings;
        settingsVolumeSlider.visible = showSettings;
        hudBtn.visible = showSettings;
        if (hudPosBtn != null) hudPosBtn.visible = showSettings;
        if (translationBtn != null) translationBtn.visible = showSettings;
        if (noteIconBtn != null) noteIconBtn.visible = showSettings;
        if (lyricsAlignBtn != null) lyricsAlignBtn.visible = showSettings;
        if (hudTransPosBtn != null) hudTransPosBtn.visible = showSettings;
        if (detailTransPosBtn != null) detailTransPosBtn.visible = showSettings;
        if (barTransPosBtn != null) barTransPosBtn.visible = showSettings;
        if (lyricsClickModeBtn != null) lyricsClickModeBtn.visible = showSettings;
        if (detailDblClickPlayBtn != null) detailDblClickPlayBtn.visible = showSettings;
        if (songsDirBox != null) songsDirBox.visible = showSettings;
        if (applyDirBtn != null) applyDirBtn.visible = showSettings;
        if (openSongsDirBtn != null) openSongsDirBtn.visible = showSettings;
        if (openCacheDirBtn != null) openCacheDirBtn.visible = showSettings;
        if (uploadBtn != null) uploadBtn.visible = showSettings;
        if (settingsPlayModeBtn != null) settingsPlayModeBtn.visible = showSettings;
        if (emptyServerBtn != null) emptyServerBtn.visible = showSettings;

        // 歌单页控件：右侧「新建 / 编辑」面板（仅列表页且为 OP 时显示）
        boolean showPlaylist = (nav == Nav.PLAYLIST && detailSong == null);
        Playlist openedPlaylist = showPlaylist ? openedPlaylist() : null;
        boolean playlistOverview = showPlaylist && openedPlaylist == null;
        boolean canEditPlaylist = isOp();
        Playlist selected = selectedPlaylist();
        if (playlistNameBox != null) {
            int panelX = playlistPanelX();
            int panelW = playlistPanelW();
            boolean show = playlistOverview && canEditPlaylist;
            playlistNameBox.setX(panelX + 8);
            playlistNameBox.setY(LIST_TOP + 22);
            playlistNameBox.setWidth(panelW - 16);
            playlistNameBox.visible = show;
            if (!show) playlistNameBox.setFocused(false);

            playlistCreateBtn.setX(panelX + 8);
            playlistCreateBtn.setY(LIST_TOP + 46);
            playlistCreateBtn.setWidth(panelW - 16);
            playlistCreateBtn.visible = show;

            int half = Math.max(40, (panelW - 20) / 2);
            playlistRenameBtn.setX(panelX + 8);
            playlistRenameBtn.setY(LIST_TOP + 70);
            playlistRenameBtn.setWidth(half);
            playlistRenameBtn.visible = show;
            playlistDeleteBtn.setX(panelX + 12 + half);
            playlistDeleteBtn.setY(LIST_TOP + 70);
            playlistDeleteBtn.setWidth(half);
            playlistDeleteBtn.visible = show;

            boolean builtIn = selected != null && ClientPlaylists.isBuiltIn(selected.getId());
            playlistRenameBtn.active = canEditPlaylist && selected != null && !builtIn;
            playlistDeleteBtn.active = canEditPlaylist && selected != null && !builtIn;
            boolean confirming = selected != null && playlistDeleteConfirmId != null
                    && playlistDeleteConfirmId.equals(selected.getId());
            playlistDeleteBtn.setMessage(Component.literal(confirming ? Lang.tr("确认删除") : Lang.tr("删除")));
        }

        // 0.27.0：搜索框定位与可见性（非设置/详情/歌单页时可用）
        // 0.27.4：搜索框右端让出图标区，避免展开后覆盖右上角搜索图标
        boolean showSearch = detailSong == null && nav != Nav.SETTINGS && nav != Nav.PLAYLIST;
        if (searchBox != null) {
            searchBox.setX(searchBoxX());
            searchBox.setY(SEARCH_BOX_Y);
            searchBox.setWidth(SEARCH_BOX_W);
            searchBox.setVisible(searchOpen && showSearch);
            if (!searchBox.isVisible()) searchBox.setFocused(false);
        }

        // 内容区
        if (detailSong != null) {
            renderDetailPanel(g, mouseX, mouseY);
        } else {
            switch (nav) {
                case ALL: renderSongList(g, mouseX, mouseY, subPage != null ? subPage.title : Lang.tr("全部歌曲"), currentSongs()); break;
                case ARTIST:
                    if (subPage != null) renderSongList(g, mouseX, mouseY, subPage.title, currentSongs());
                    else renderGroupList(g, mouseX, mouseY, filteredGroups(groupByArtist()), Lang.tr("🎤 歌手"));
                    break;
                case ALBUM:
                    if (subPage != null) renderSongList(g, mouseX, mouseY, subPage.title, currentSongs());
                    else renderGroupList(g, mouseX, mouseY, filteredGroups(groupByAlbum()), Lang.tr("💿 专辑"));
                    break;
                case PLAYLIST: renderPlaylistPage(g, mouseX, mouseY); break;
                case SETTINGS: renderSettings(g); break;
            }
        }

        // 0.27.4：搜索图标恢复为原符号（放大镜字符），展开金色 / 收起灰色
        // 0.27.5：图标放大 1.5 倍，且与「共 N 首」计数文字（y=8）水平对齐
        if (detailSong == null && nav != Nav.SETTINGS && nav != Nav.PLAYLIST) {
            int ic = searchOpen ? 0xFFE0A040 : 0xFF999999;
            g.pose().pushPose();
            g.pose().translate(searchIconX(), SEARCH_ICON_Y, 0);
            g.pose().scale(SEARCH_ICON_SCALE, SEARCH_ICON_SCALE, 1.0f);
            g.drawString(font, "🔍", 0, 0, ic);
            g.pose().popPose();
        }

        super.render(g, mouseX, mouseY, partialTick);

        renderPlayerBar(g);
        renderAddToPlaylistPopup(g, mouseX, mouseY);
        g.pose().popPose();
    }

    /** 绘制窗口外部遮罩、边框、标题栏与关闭按钮 */
    private void renderWindowChrome(GuiGraphics g, int mouseX, int mouseY) {
        // 外部半透明遮罩，突出窗口；窗口内部由 renderBackground 自行绘制
        g.fill(0, 0, realWidth, realHeight, 0x80000000);
        // 外边框 + 内边框
        g.fill(windowX - 2, windowY - 2, windowX + windowW + 2, windowY + windowH + 2, 0xFF15151F);
        g.fill(windowX - 1, windowY - 1, windowX + windowW + 1, windowY + windowH + 1, 0xFF4A4A60);
        // 标题栏
        g.fill(windowX, windowY, windowX + windowW, windowY + WINDOW_TITLE_H, 0xFF1E1E2E);
        g.drawString(this.font, Lang.tr("§e一起听 §7· 音乐播放器"), windowX + 8, windowY + 7, 0xFFFFFF);

        boolean closeHover = isOverClose(mouseX, mouseY);
        g.drawString(this.font, closeHover ? "§c✕" : "§7✕",
                windowX + windowW - 18, windowY + 7, 0xFFFFFF);
    }

    /** 关闭按钮命中区（真实屏幕坐标） */
    private boolean isOverClose(double mouseX, double mouseY) {
        return mouseX >= windowX + windowW - 24 && mouseX <= windowX + windowW - 4
                && mouseY >= windowY + 3 && mouseY <= windowY + 19;
    }
    /** 通用歌曲列表渲染（全部歌曲 / 歌手歌曲 / 专辑歌曲） */
    private void renderSongList(GuiGraphics g, int mouseX, int mouseY, String pageTitle, List<String> songs) {
        Font font = this.font;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        String currentSong = mgr.getCurrentSong();
        g.drawString(font, "§e§l" + pageTitle, x, 8, 0xFFFFFF);
        if (subPage != null) {
            g.drawString(font, Lang.tr("§7« 返回"), x + font.width("§e§l" + pageTitle) + 8, 8, 0xAAAAAA);
        }
        // 0.27.5：计数文字避让右上角搜索图标/搜索框（图标放大 1.5 倍后收起态避让 24px）
        String countText = Lang.tr("§7共 ") + songs.size() + Lang.tr(" 首");
        int countRight = x + w - (searchOpen ? SEARCH_ICON_ZONE + SEARCH_BOX_W + 2 : 24);
        g.drawString(font, countText, countRight - font.width(countText), 8, 0xAAAAAA);
        // 0.28.0：排序标签（点击循环切换），置于计数文字左侧
        String sortText = Lang.tr("§7[排序: ") + sortModeName() + "]";
        int sortW = font.width(sortText);
        int sortX = countRight - font.width(countText) - sortW - 6;
        g.drawString(font, sortText, sortX, 8, 0xAAAAAA);
        sortLabelX = sortX;
        sortLabelW = sortW;
        g.fill(x - 1, y0 - 1, x + w + 1, y0 + h + 1, 0xFF4A4A60);
        g.fill(x, y0, x + w, y0 + h, 0x90000010);
        int rows = h / ROW_H;
        int maxScroll = Math.max(0, songs.size() - rows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        curScrollTotal = songs.size();
        curScrollVisible = rows;
        curScrollRowH = ROW_H;
        curScrollYOffset = 0;
        int colNum = x + 4;
        int colHeart = x + w - 20;
        int colDur = colHeart - 36;
        int colAlbum = colDur - (int) (w * 0.25);
        int colArtist = colAlbum - (int) (w * 0.25);
        int titleMax = colArtist - colNum - 22 - 6;
        for (int i = 0; i < rows; i++) {
            int idx = scrollOffset + i;
            if (idx >= songs.size()) break;
            int rowY = y0 + i * ROW_H;
            String song = songs.get(idx);
            boolean isSel = song.equals(selectedSong);
            boolean isCur = song.equals(currentSong);
            if (isSel) g.fill(x, rowY, x + w - 7, rowY + ROW_H, 0x80555577);
            else if (isCur) g.fill(x, rowY, x + w - 7, rowY + ROW_H, 0x50226633);
            String numStr = isCur ? (mgr.isPaused() ? "§e▶" : "§a⏸") : String.valueOf(idx + 1);
            g.drawString(font, numStr, colNum, rowY + 2, isCur ? 0xFFFFFF : 0x888888);
            String title = tags.displayTitle(song);
            // 当前播放：播放中绿色、暂停黄色；选中不高亮歌曲名（仅行背景区分）
            int titleColor = isCur ? (mgr.isPaused() ? 0xFFFF55 : 0x55FF55) : 0xF0F0F0;
            g.drawString(font, truncate(font, title, titleMax), colNum + 22, rowY + 2, titleColor);
            String artist = tags.artist(song);
            g.drawString(font, truncate(font, artist.isEmpty() ? Lang.tr("§7未知") : artist, colAlbum - colArtist - 6),
                    colArtist, rowY + 2, 0xBBBBBB);
            String album = tags.album(song);
            g.drawString(font, truncate(font, album.isEmpty() ? Lang.tr("§7未知") : album, colDur - colAlbum - 6),
                    colAlbum, rowY + 2, 0x999999);
            int durSec = tags.durationSec(song);
            if (durSec >= 0) {
                g.drawString(font, formatDuration(durSec), colDur, rowY + 2, 0x888888);
            }
            boolean fav = ClientFavorites.isFavorite(song);
            g.drawString(font, fav ? "§c♥" : "§7♡", colHeart, rowY + 2, 0xFFFFFF);
        }
        if (songs.isEmpty()) {
            String emptyMsg = searchFilter().isEmpty()
                    ? Lang.tr("§7（列表为空，放入歌曲后点 ⟳ 刷新）") : Lang.tr("§7（无匹配结果）");
            g.drawCenteredString(font, emptyMsg, x + w / 2, y0 + h / 2 - 4, 0xAAAAAA);
        }
        drawScrollbar(g, songs.size(), rows, x, y0, w, h);
        addSongHintX = -1;
        addSongHintW = 0;
        if (selectedSong != null && !selectedSong.isEmpty()) {
            int hintY = playerBarY() - 12;
            String prefix = Lang.tr("§7[ 点击行中 ♥ 收藏 ]  [ 双击播放 ]");
            String add = isOp() ? Lang.tr("  §e[ + 歌单 ]") : "";
            g.drawString(font, prefix, x, hintY, 0xAAAAAA);
            if (!add.isEmpty()) {
                int cursorX = x + font.width(prefix);
                g.drawString(font, add, cursorX, hintY, 0xFFD700);
                addSongHintX = cursorX;
                addSongHintW = font.width(add);
            }
        }
    }

    /** 歌手/专辑分组列表 */
    private void renderGroupList(GuiGraphics g, int mouseX, int mouseY,
                                 Map<String, List<String>> groups, String pageTitle) {
        Font font = this.font;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
        addSongHintX = -1;
        addSongHintW = 0;
        g.drawString(font, "§e§l" + pageTitle, x, 8, 0xFFFFFF);
        // 0.27.5：计数文字避让右上角搜索图标/搜索框（图标放大 1.5 倍后收起态避让 24px）
        String countText = Lang.tr("§7共 ") + groups.size() + Lang.tr(" 组");
        int countRight = x + w - (searchOpen ? SEARCH_ICON_ZONE + SEARCH_BOX_W + 2 : 24);
        g.drawString(font, countText, countRight - font.width(countText), 8, 0xAAAAAA);
        g.fill(x - 1, y0 - 1, x + w + 1, y0 + h + 1, 0xFF4A4A60);
        g.fill(x, y0, x + w, y0 + h, 0x90000010);
        List<String> keys = new ArrayList<>(groups.keySet());
        int rows = h / (ROW_H + 4);
        int maxScroll = Math.max(0, keys.size() - rows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        curScrollTotal = keys.size();
        curScrollVisible = rows;
        curScrollRowH = ROW_H + 4;
        curScrollYOffset = 0;
        for (int i = 0; i < rows; i++) {
            int idx = scrollOffset + i;
            if (idx >= keys.size()) break;
            int rowY = y0 + i * (ROW_H + 4);
            String key = keys.get(idx);
            int cnt = groups.get(key).size();
            g.fill(x, rowY, x + w - 7, rowY + ROW_H + 2, 0x60333350);
            g.drawString(font, "§f" + truncate(font, key, w - 80), x + 6, rowY + 3, 0xF0F0F0);
            String cntStr = "§7" + cnt + Lang.tr(" 首 »");
            g.drawString(font, cntStr, x + w - font.width(cntStr) - 6, rowY + 3, 0xAAAAAA);
        }
        drawScrollbar(g, keys.size(), rows, x, y0, w, h);
    }

    // ==================== 歌单页渲染 ====================

    /** 歌单页入口：根据是否打开详情分发 */
    private void renderPlaylistPage(GuiGraphics g, int mouseX, int mouseY) {
        if (openedPlaylistId != null && openedPlaylist() == null) {
            // 歌单被删除/服务器重载后自动退回列表页
            openedPlaylistId = null;
        }
        Playlist opened = openedPlaylist();
        if (opened != null) {
            renderPlaylistDetail(g, mouseX, mouseY, opened);
        } else {
            renderPlaylistOverview(g, mouseX, mouseY);
        }
    }

    /** 歌单列表页：左侧歌单列表 + 右侧新建/编辑面板 */
    private void renderPlaylistOverview(GuiGraphics g, int mouseX, int mouseY) {
        Font font = this.font;
        int x = listX(), w = listW(), h = listH();
        int listRight = playlistPanelX() - 6;
        int listAreaW = Math.max(60, listRight - x);
        List<Playlist> playlists = ClientPlaylists.getInstance().getDisplayPlaylists();
        if (selectedPlaylistId != null && selectedPlaylist() == null) {
            selectedPlaylistId = null;
            playlistDeleteConfirmId = null;
        }

        g.drawString(font, Lang.tr("§e§l≡ 歌单"), x, 8, 0xFFFFFF);
        String countText = Lang.tr("§7共 ") + playlists.size() + Lang.tr(" 个歌单");
        g.drawString(font, countText, x + w - font.width(countText) - 4, 8, 0xAAAAAA);

        int rows = Math.max(1, h / PLAYLIST_ROW_H);
        int maxScroll = Math.max(0, playlists.size() - rows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        curScrollTotal = playlists.size();
        curScrollVisible = rows;
        curScrollRowH = PLAYLIST_ROW_H;
        curScrollYOffset = 0;

        // 左侧歌单列表
        g.fill(x - 1, LIST_TOP - 1, listRight + 1, LIST_TOP + h + 1, 0xFF4A4A60);
        g.fill(x, LIST_TOP, x + listAreaW, LIST_TOP + h, 0x90000010);

        Set<String> librarySongs = new HashSet<>(allSongs());
        String currentSong = ClientMusicManager.getInstance().getCurrentSong();
        for (int i = 0; i < rows; i++) {
            int idx = scrollOffset + i;
            if (idx >= playlists.size()) break;
            Playlist playlist = playlists.get(idx);
            int rowY = LIST_TOP + i * PLAYLIST_ROW_H;
            boolean selected = playlist.getId().equals(selectedPlaylistId);
            boolean builtInRow = ClientPlaylists.isBuiltIn(playlist.getId());
            // 收藏/最近播放不显示“包含当前歌曲”的绿色高亮
            boolean containsCurrent = !builtInRow && !currentSong.isEmpty()
                    && playlist.contains(currentSong);
            if (selected) g.fill(x, rowY, x + listAreaW - 6, rowY + PLAYLIST_ROW_H - 1, 0x80555577);
            else if (containsCurrent) g.fill(x, rowY, x + listAreaW - 6, rowY + PLAYLIST_ROW_H - 1, 0x30226633);
            String icon = ClientPlaylists.isFavorites(playlist.getId()) ? "§c❤"
                    : ClientPlaylists.isRecent(playlist.getId()) ? "§9🕘" : "§7≡";
            g.drawString(font, icon, x + 6, rowY + 4, 0xFFFFFF);
            g.drawString(font, "§f" + truncate(font, Lang.tr(playlist.getName()), Math.max(40, listAreaW - 100)),
                    x + 24, rowY + 4, 0xF0F0F0);
            int available = 0;
            for (String song : playlist.getSongs()) {
                if (librarySongs.contains(song)) available++;
            }
            String count = "§7" + available + "/" + playlist.size() + Lang.tr(" 首");
            g.drawString(font, count, x + listAreaW - font.width(count) - 12, rowY + 4, 0xAAAAAA);
        }
        drawScrollbar(g, playlists.size(), rows, x, LIST_TOP, listAreaW, h);

        // 右侧「新建 / 编辑」面板
        int panelX = playlistPanelX();
        int panelW = playlistPanelW();
        g.fill(panelX - 1, LIST_TOP - 1, panelX + panelW + 1, LIST_TOP + h + 1, 0xFF4A4A60);
        g.fill(panelX, LIST_TOP, panelX + panelW, LIST_TOP + h, 0x90000010);
        g.drawString(font, Lang.tr("§e§l新建 / 编辑"), panelX + 8, LIST_TOP + 6, 0xFFFFFF);

        if (!isOp()) {
            g.drawString(font, Lang.tr("§7仅管理员可新建"), panelX + 8, LIST_TOP + 28, 0xAAAAAA);
            g.drawString(font, Lang.tr("§7或编辑服务器歌单"), panelX + 8, LIST_TOP + 40, 0xAAAAAA);
        } else {
            Playlist selected = selectedPlaylist();
            int infoY = LIST_TOP + 98;
            if (selected == null) {
                g.drawString(font, Lang.tr("§7先选择歌单，或输入"), panelX + 8, infoY, 0xAAAAAA);
                g.drawString(font, Lang.tr("§7名称新建歌单"), panelX + 8, infoY + 11, 0xAAAAAA);
            } else if (ClientPlaylists.isBuiltIn(selected.getId())) {
                g.drawString(font, "§e" + truncate(font, Lang.tr(selected.getName()), panelW - 16),
                        panelX + 8, infoY, 0xFFFF55);
                g.drawString(font, Lang.tr("§7内置歌单（自动维护）"), panelX + 8, infoY + 11, 0xAAAAAA);
                g.drawString(font, Lang.tr("§8不可重命名/删除"), panelX + 8, infoY + 22, 0x888888);
            } else {
                g.drawString(font, "§e" + truncate(font, Lang.tr(selected.getName()), panelW - 16),
                        panelX + 8, infoY, 0xFFFF55);
                g.drawString(font, "§7" + selected.size() + Lang.tr(" 首 · 可重命名/删除"),
                        panelX + 8, infoY + 11, 0xAAAAAA);
            }
        }

        String hint = isOp()
                ? Lang.tr("§7[ 单击选择 ]  [ 双击打开 ]  [ 右侧可新建/重命名 ]")
                : Lang.tr("§7[ 双击打开歌单 ]  §8（仅管理员可编辑）");
        g.drawString(font, hint, x, playerBarY() - 12, 0xAAAAAA);
    }

    /** 歌单详情页：播放 / 添加歌曲 / 删除 + 歌曲列表（内置歌单只读部分操作） */
    private void renderPlaylistDetail(GuiGraphics g, int mouseX, int mouseY, Playlist playlist) {
        Font font = this.font;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
        boolean builtIn = ClientPlaylists.isBuiltIn(playlist.getId());
        boolean favorites = ClientPlaylists.isFavorites(playlist.getId());
        boolean recent = ClientPlaylists.isRecent(playlist.getId());

        String title = playlistTitle(playlist);
        g.drawString(font, "§e§l" + title, x, 8, 0xFFFFFF);
        int titleW = font.width("§e§l" + title);
        String backText = Lang.tr("§7« 返回歌单");
        g.drawString(font, backText, x + titleW + 10, 8, 0xAAAAAA);

        // 计数固定在顶部右侧
        String countText = Lang.tr("§7共 ") + playlist.size() + Lang.tr(" 首");
        g.drawString(font, countText, x + w - font.width(countText) - 4, 8, 0xAAAAAA);

        // 工具栏（文字按钮，点击区域在 mouseClicked 中判定）
        int toolbarY = y0 + 7;
        String playText = Lang.tr("§a▶ 播放歌单");
        g.drawString(font, playText, x + 8, toolbarY, 0x55FF55);
        playlistPlayX = x + 8;
        playlistPlayW = font.width(playText);
        int cursor = playlistPlayX + playlistPlayW + 18;

        if (!builtIn && isOp()) {
            boolean confirming = playlistDeleteConfirmId != null
                    && playlistDeleteConfirmId.equals(playlist.getId());
            String deleteText = confirming ? Lang.tr("§c确认删除?") : Lang.tr("§c🗑 删除歌单");
            g.drawString(font, deleteText, cursor, toolbarY, 0xFF5555);
            playlistDeleteX = cursor;
            playlistDeleteW = font.width(deleteText);
            cursor += playlistDeleteW + 12;
        } else {
            playlistDeleteX = -1;
            playlistDeleteW = 0;
            if (builtIn) {
                String tag = favorites ? Lang.tr("§8内置收藏 · 与 ♥ 联动")
                        : recent ? Lang.tr("§8内置历史 · 播放自动记录") : Lang.tr("§8内置歌单");
                g.drawString(font, tag, cursor, toolbarY, 0x888888);
                cursor += font.width(tag) + 12;
            } else {
                g.drawString(font, Lang.tr("§8仅管理员可删除"), cursor, toolbarY, 0x777777);
                cursor += font.width(Lang.tr("§8仅管理员可删除")) + 12;
            }
        }
        if (!isOp()) {
            g.drawString(font, Lang.tr("§8（播放需要管理员权限）"), cursor, toolbarY, 0x777777);
        }

        // 「添加歌曲」与播放歌单、删除歌单保持同一 Y 坐标，靠右对齐
        boolean canAdd = favorites || (!recent && isOp());
        if (canAdd) {
            String addText = Lang.tr("§b[ + 添加歌曲 ]");
            int addW = font.width(addText);
            int addX = x + w - addW - 6;
            int addBgY = y0 + 2;
            g.fill(addX - 4, addBgY, addX + addW + 4, addBgY + 16, 0x60333350);
            g.drawString(font, addText, addX, toolbarY, 0x55FFFF);
            playlistAddX = addX - 4;
            playlistAddY = addBgY;
            playlistAddW = addW + 8;
            playlistAddH = 16;
        } else {
            playlistAddX = -1;
            playlistAddY = -1;
            playlistAddW = 0;
            playlistAddH = 0;
        }

        int rowsTop = y0 + PLAYLIST_TOOLBAR_H;
        int rowsAreaH = Math.max(PLAYLIST_ROW_H, h - PLAYLIST_TOOLBAR_H);
        int rows = Math.max(1, rowsAreaH / PLAYLIST_ROW_H);
        List<String> songs = playlist.getSongs();
        int maxScroll = Math.max(0, songs.size() - rows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        curScrollTotal = songs.size();
        curScrollVisible = rows;
        curScrollRowH = PLAYLIST_ROW_H;
        curScrollYOffset = PLAYLIST_TOOLBAR_H;

        g.fill(x - 1, rowsTop - 1, x + w + 1, rowsTop + rowsAreaH + 1, 0xFF4A4A60);
        g.fill(x, rowsTop, x + w, rowsTop + rowsAreaH, 0x90000010);

        Set<String> librarySongs = new HashSet<>(allSongs());
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        String currentSong = mgr.getCurrentSong();
        boolean showRemove = isOp() || builtIn;
        int colNum = x + 4;
        int colTitle = x + 22;
        int colArtist = x + (int) (w * 0.55);
        int colDur = x + w - 58;
        int colRemove = x + w - 16;
        for (int i = 0; i < rows; i++) {
            int idx = scrollOffset + i;
            if (idx >= songs.size()) break;
            int rowY = rowsTop + i * PLAYLIST_ROW_H;
            String song = songs.get(idx);
            boolean selected = song.equals(selectedSong);
            boolean isCurrent = song.equals(currentSong);
            boolean missing = !librarySongs.contains(song);
            if (selected) g.fill(x, rowY, x + w - 6, rowY + PLAYLIST_ROW_H - 1, 0x80555577);
            else if (isCurrent) g.fill(x, rowY, x + w - 6, rowY + PLAYLIST_ROW_H - 1, 0x50226633);
            String numStr = isCurrent ? (mgr.isPaused() ? "§e▶" : "§a⏸") : String.valueOf(idx + 1);
            g.drawString(font, numStr, colNum, rowY + 4, isCurrent ? 0xFFFFFF : 0x888888);
            String displayTitle = missing ? song : tags.displayTitle(song);
            int titleColor = missing ? 0xFF7777
                    : (isCurrent ? (mgr.isPaused() ? 0xFFFF55 : 0x55FF55) : 0xF0F0F0);
            g.drawString(font, truncate(font, displayTitle, Math.max(30, colArtist - colTitle - 8)),
                    colTitle, rowY + 4, titleColor);
            String artist = missing ? Lang.tr("文件缺失") : tags.artist(song);
            g.drawString(font, truncate(font, artist.isEmpty() ? Lang.tr("§7未知") : artist,
                            Math.max(20, colDur - colArtist - 8)),
                    colArtist, rowY + 4, missing ? 0xCC6666 : 0xBBBBBB);
            int durSec = tags.durationSec(song);
            if (!missing && durSec >= 0) {
                g.drawString(font, formatDuration(durSec), colDur, rowY + 4, 0x888888);
            }
            if (showRemove) {
                g.drawString(font, "§c✕", colRemove, rowY + 4, 0xFF5555);
            }
        }
        if (songs.isEmpty()) {
            String empty = favorites ? Lang.tr("§7还没有收藏歌曲，在全部歌曲中点击 ♥ 即可加入")
                    : recent ? Lang.tr("§7还没有播放记录，播放歌曲后会自动记录")
                    : isOp() ? Lang.tr("§7歌单还是空的，点上方「+ 添加歌曲」加入")
                    : Lang.tr("§7歌单还是空的（仅管理员可添加歌曲）");
            g.drawCenteredString(font, empty, x + w / 2, rowsTop + rowsAreaH / 2 - 4, 0xAAAAAA);
        }
        drawScrollbar(g, songs.size(), rows, x, rowsTop, w, rowsAreaH);

        String hint;
        if (!isOp()) {
            hint = Lang.tr("§7[ 双击歌曲播放（OP）]  [ « 返回歌单列表 ]");
        } else if (recent) {
            hint = Lang.tr("§7[ 双击歌曲播放 ]  [ 点击 ✕ 从历史移除 ]  [ « 返回歌单列表 ]");
        } else if (favorites) {
            hint = Lang.tr("§7[ 双击歌曲播放 ]  [ 点击 ✕ 取消收藏 ]  [ « 返回歌单列表 ]");
        } else {
            hint = Lang.tr("§7[ 双击歌曲播放 ]  [ 点击 ✕ 移除 ]  [ « 返回歌单列表 ]");
        }
        g.drawString(font, hint, x, playerBarY() - 12, 0xAAAAAA);
    }

    // ==================== 添加到歌单弹窗 ====================

    private int playlistPopupX() {
        return (this.width - PLAYLIST_POPUP_W) / 2;
    }

    private int playlistPopupRows() {
        int size = ClientPlaylists.getInstance().size();
        return Math.min(PLAYLIST_POPUP_MAX_ROWS, Math.max(1, size));
    }

    private int playlistPopupH() {
        int size = ClientPlaylists.getInstance().size();
        if (size == 0) return 78;
        return 40 + playlistPopupRows() * PLAYLIST_POPUP_ROW_H + 22;
    }

    private int playlistPopupY() {
        return (this.height - playlistPopupH()) / 2;
    }

    private int playlistPopupListY() {
        return playlistPopupY() + 38;
    }

    /** 添加到歌单弹窗（覆盖在播放器界面之上） */
    private void renderAddToPlaylistPopup(GuiGraphics g, int mouseX, int mouseY) {
        if (addToPlaylistSong == null) return;
        Font font = this.font;
        g.fill(0, 0, this.width, this.height, 0xA0000000);
        int px = playlistPopupX(), py = playlistPopupY();
        int pw = PLAYLIST_POPUP_W, ph = playlistPopupH();
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF6A6A8A);
        g.fill(px, py, px + pw, py + ph, 0xFF202030);

        g.drawString(font, Lang.tr("§e§l添加到歌单"), px + 10, py + 8, 0xFFFFFF);
        String songTitle = ClientTagStore.getInstance().displayTitle(addToPlaylistSong);
        g.drawString(font, "§7" + truncate(font, songTitle, pw - 20), px + 10, py + 22, 0xAAAAAA);

        List<Playlist> playlists = ClientPlaylists.getInstance().getAll();
        int listY = playlistPopupListY();
        if (playlists.isEmpty()) {
            g.drawCenteredString(font, Lang.tr("§7还没有歌单，请先在「≡ 歌单」页创建"),
                    px + pw / 2, listY + 8, 0xAAAAAA);
        } else {
            int rows = playlistPopupRows();
            int maxScroll = Math.max(0, playlists.size() - rows);
            playlistPopupScroll = Math.max(0, Math.min(maxScroll, playlistPopupScroll));
            for (int i = 0; i < rows; i++) {
                int idx = playlistPopupScroll + i;
                if (idx >= playlists.size()) break;
                int rowY = listY + i * PLAYLIST_POPUP_ROW_H;
                boolean hover = mouseX >= px && mouseX <= px + pw
                        && mouseY >= rowY && mouseY < rowY + PLAYLIST_POPUP_ROW_H;
                if (hover) g.fill(px + 4, rowY, px + pw - 4, rowY + PLAYLIST_POPUP_ROW_H - 1, 0x80555577);
                Playlist playlist = playlists.get(idx);
                g.drawString(font, "§f" + truncate(font, Lang.tr(playlist.getName()), pw - 90),
                        px + 12, rowY + 4, 0xF0F0F0);
                String cnt = "§7" + playlist.size() + Lang.tr(" 首");
                g.drawString(font, cnt, px + pw - font.width(cnt) - 14, rowY + 4, 0xAAAAAA);
            }
            if (maxScroll > 0) {
                int barX = px + pw - 6;
                float ratio = (float) playlistPopupScroll / maxScroll;
                int barH = Math.max(10, (rows * PLAYLIST_POPUP_ROW_H) * rows / playlists.size());
                int barY = listY + (int) ((rows * PLAYLIST_POPUP_ROW_H - barH) * ratio);
                g.fill(barX, listY, barX + 3, listY + rows * PLAYLIST_POPUP_ROW_H, 0x40000000);
                g.fill(barX, barY, barX + 3, barY + barH, 0xFFAAAAAA);
            }
        }
        g.drawString(font, Lang.tr("§8点击歌单添加 · 点击空白处取消"),
                px + 10, py + ph - 12, 0x888888);
    }

    /** 打开「添加到歌单」弹窗并收起输入焦点，避免继续向搜索框输入 */
    private void openAddToPlaylistPopup(String song) {
        if (song == null || song.isEmpty()) return;
        addToPlaylistSong = song;
        playlistPopupScroll = 0;
        if (searchBox != null) searchBox.setFocused(false);
        setFocused(null);
    }

    /** 处理添加到歌单弹窗内的点击（弹窗打开时消费所有点击） */
    private boolean handleAddToPlaylistPopupClick(int mx, int my) {
        int px = playlistPopupX(), py = playlistPopupY();
        int pw = PLAYLIST_POPUP_W, ph = playlistPopupH();
        if (mx < px || mx > px + pw || my < py || my > py + ph) {
            addToPlaylistSong = null;
            return true;
        }
        List<Playlist> playlists = ClientPlaylists.getInstance().getAll();
        if (!playlists.isEmpty()) {
            int rows = playlistPopupRows();
            int listY = playlistPopupListY();
            if (my >= listY && my < listY + rows * PLAYLIST_POPUP_ROW_H) {
                int row = (my - listY) / PLAYLIST_POPUP_ROW_H;
                int idx = playlistPopupScroll + row;
                if (idx >= 0 && idx < playlists.size()) {
                    Playlist playlist = playlists.get(idx);
                    NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                            C2SPlaylistActionPacket.ACTION_ADD_SONG, playlist.getId(), addToPlaylistSong));
                    addToPlaylistSong = null;
                    return true;
                }
            }
        }
        return true;
    }

    // ==================== 歌单内添加歌曲（独立界面） ====================

    /** 在歌单详情内打开「添加歌曲」界面：切换为全新的 Screen，后方歌单信息完全不渲染 */
    private void openAddMusicPicker() {
        Playlist playlist = openedPlaylist();
        if (playlist == null || ClientPlaylists.isRecent(playlist.getId())) return;
        Minecraft.getInstance().setScreen(new AddSongsToPlaylistScreen(this, playlist.getId()));
    }

    // ==================== 歌单操作 ====================

    private void createPlaylistFromInput() {
        if (playlistNameBox == null) return;
        String name = playlistNameBox.getValue().trim();
        if (name.isEmpty()) return;
        NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                C2SPlaylistActionPacket.ACTION_CREATE, "", name));
        playlistNameBox.setValue("");
    }

    private void renameSelectedPlaylist() {
        Playlist playlist = selectedPlaylist();
        if (playlist == null || ClientPlaylists.isBuiltIn(playlist.getId()) || playlistNameBox == null) return;
        String name = playlistNameBox.getValue().trim();
        if (name.isEmpty() || name.equals(playlist.getName())) return;
        NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                C2SPlaylistActionPacket.ACTION_RENAME, playlist.getId(), name));
    }

    private void deleteSelectedPlaylist() {
        Playlist playlist = selectedPlaylist();
        if (playlist == null || ClientPlaylists.isBuiltIn(playlist.getId())) return;
        if (playlistDeleteConfirmId != null && playlistDeleteConfirmId.equals(playlist.getId())) {
            NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                    C2SPlaylistActionPacket.ACTION_DELETE, playlist.getId(), ""));
            playlistDeleteConfirmId = null;
            selectedPlaylistId = null;
            if (playlistNameBox != null) playlistNameBox.setValue("");
        } else {
            playlistDeleteConfirmId = playlist.getId();
        }
    }

    /** 设置页内容总高度（像素，不含 LIST_TOP 偏移）
     *  0.27.0：新增歌词跳转/详情双击播放按钮，后续控件下移 52px
     *  0.29.0：新增 HUD 位置按钮，后续控件下移 26px */
    private int settingsContentH() {
        if (singleplayer) return 418;
        return emptyServerBtn != null ? 388 : 362;
    }

    /** 将设置控件定位到滚动后的位置，并按可见性设置 visible */
    private void positionSetting(AbstractWidget w, int baseY, boolean show, int visibleH) {
        if (w == null) return;
        int y = LIST_TOP + baseY - settingsScroll;
        w.setY(y);
        // 只要有部分在可见区域内就显示
        w.visible = show && y + w.getHeight() > LIST_TOP && y < LIST_TOP + visibleH;
    }

    /** 设置页（控件随滚动偏移，窗口过矮时显示滚动条） */
    private void renderSettings(GuiGraphics g) {
        Font font = this.font;
        int x = listX(), w = listW();
        addSongHintX = -1;
        addSongHintW = 0;
        int visibleH = listH();
        int contentH = settingsContentH();
        int maxScroll = Math.max(0, contentH - visibleH);
        settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll));

        g.drawString(font, Lang.tr("§e§l⚙ 设置"), x, 8, 0xFFFFFF);
        boolean show = (nav == Nav.SETTINGS && detailSong == null);

        // 定位控件（随滚动偏移 Y，超出可见区域的隐藏）
        // 0.27.0：新增歌词跳转/详情双击播放按钮，后续控件整体下移 52px
        // 0.29.0：新增 HUD 位置按钮，后续控件整体下移 26px
        positionSetting(styleBtn, 4, show, visibleH);
        positionSetting(settingsVolumeSlider, 30, show, visibleH);
        positionSetting(hudBtn, 56, show, visibleH);
        positionSetting(hudPosBtn, 82, show, visibleH);
        positionSetting(translationBtn, 108, show, visibleH);
        positionSetting(noteIconBtn, 134, show, visibleH);
        positionSetting(lyricsAlignBtn, 160, show, visibleH);
        positionSetting(hudTransPosBtn, 186, show, visibleH);
        positionSetting(detailTransPosBtn, 212, show, visibleH);
        positionSetting(barTransPosBtn, 238, show, visibleH);
        positionSetting(lyricsClickModeBtn, 264, show, visibleH);
        positionSetting(detailDblClickPlayBtn, 290, show, visibleH);
        if (singleplayer) {
            positionSetting(songsDirBox, 328, show, visibleH);
            positionSetting(applyDirBtn, 352, show, visibleH);
            positionSetting(openSongsDirBtn, 352, show, visibleH);
            positionSetting(settingsPlayModeBtn, 394, show, visibleH);
        } else {
            positionSetting(openCacheDirBtn, 316, show, visibleH);
            positionSetting(emptyServerBtn, 368, show, visibleH);
            // 上传按钮固定右上角，避免窗口变矮后滚出可视区域
            if (uploadBtn != null) {
                int upW = Math.min(100, Math.max(72, w / 5));
                uploadBtn.setX(x + w - upW - 4);
                uploadBtn.setY(4);
                uploadBtn.setWidth(upW);
                uploadBtn.visible = show;
                uploadBtn.active = isOp();
            }
        }

        if (show) {
            // 标签文字（随滚动偏移）
            drawSettingLabel(g, font, Lang.tr("§7界面风格"), x, 10);
            drawSettingLabel(g, font, Lang.tr("§7个人音量"), x, 36);
            drawSettingLabel(g, font, Lang.tr("§7HUD 显示"), x, 62);
            drawSettingLabel(g, font, Lang.tr("§7HUD 位置"), x, 88);
            drawSettingLabel(g, font, Lang.tr("§7翻译歌词"), x, 114);
            drawSettingLabel(g, font, Lang.tr("§7音符图标"), x, 140);
            drawSettingLabel(g, font, Lang.tr("§7歌词对齐"), x, 166);
            drawSettingLabel(g, font, Lang.tr("§7HUD 翻译位置"), x, 192);
            drawSettingLabel(g, font, Lang.tr("§7详情页翻译位置"), x, 218);
            drawSettingLabel(g, font, Lang.tr("§7进度条翻译位置"), x, 244);
            drawSettingLabel(g, font, Lang.tr("§7歌词跳转"), x, 270);
            drawSettingLabel(g, font, Lang.tr("§7详情双击播放"), x, 296);
            if (singleplayer) {
                drawSettingLabel(g, font, Lang.tr("§7歌曲目录（单人游戏）"), x, 314);
                drawSettingLabel(g, font, Lang.tr("§7播放模式"), x, 400);
            }
            // 多人模式：服务器配置提示固定在右下角（不随滚动）
            if (!singleplayer) {
                String hint = Lang.tr("§8服务器配置: config/musicmod-server.toml");
                int hintW = font.width(hint);
                g.drawString(font, hint, x + w - hintW, playerBarY() - 12, 0x888888);
            }
            // 滚动条
            if (maxScroll > 0) {
                int barX = x + w - 5;
                int y0 = LIST_TOP;
                float ratio = (float) settingsScroll / maxScroll;
                int barH = Math.max(12, visibleH * visibleH / contentH);
                int barY = y0 + (int) ((visibleH - barH) * ratio);
                g.fill(barX, y0, barX + 4, y0 + visibleH, 0x40000000);
                g.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
            }
        }
    }

    /** 绘制设置页标签（应用滚动偏移，超出可见区域的不绘制） */
    private void drawSettingLabel(GuiGraphics g, Font font, String text, int x, int baseY) {
        int y = LIST_TOP + baseY - settingsScroll;
        if (y >= LIST_TOP - 12 && y <= LIST_TOP + listH()) {
            g.drawString(font, text, x + 8, y, 0xAAAAAA);
        }
    }

    private void drawScrollbar(GuiGraphics g, int total, int visibleRows, int x, int y0, int w, int h) {
        if (total <= visibleRows) return;
        int barX = x + w - 5;
        float ratio = (float) scrollOffset / Math.max(1, total - visibleRows);
        int barH = Math.max(12, (int) ((float) h * visibleRows / total));
        int barY = y0 + (int) ((h - barH) * ratio);
        g.fill(barX, y0, barX + 4, y0 + h, 0x40000000);
        g.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
    }

    /** 底部播放栏 */
    private void renderPlayerBar(GuiGraphics g) {
        Font font = this.font;
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        int by = playerBarY();
        String song = mgr.getCurrentSong();
        int coverX = 6, coverY = by + 4, coverSize = 30;
        ResourceLocation cover = song.isEmpty() ? null : CoverTextureManager.get(song);
        if (cover != null) {
            int[] size = CoverTextureManager.getSize(song);
            if (size != null) {
                g.fill(coverX - 1, coverY - 1, coverX + coverSize + 1, coverY + coverSize + 1, 0xFF555555);
                drawCoverScaled(g, cover, size, coverX, coverY, coverSize);
            }
        } else {
            g.fill(coverX, coverY, coverX + coverSize, coverY + coverSize, 0xFF2A2A3A);
            g.drawCenteredString(font, "♪", coverX + coverSize / 2 - 2, coverY + 10, 0x666666);
        }
        int textX = coverX + coverSize + 8;
        if (!song.isEmpty()) {
            String title = ClientTagStore.getInstance().displayTitle(song);
            String artist = ClientTagStore.getInstance().artist(song);
            // 0.28.1：文本区宽度自适应窗口，截断宽度随之收缩
            int textMaxW = textAreaW() - 10;
            g.drawString(font, "§f" + truncate(font, title, textMaxW), textX, by + 6, 0xFFFFFF);
            String sub = artist.isEmpty() ? "" : "§7" + truncate(font, artist, textMaxW);
            if (!sub.isEmpty()) g.drawString(font, sub, textX, by + 20, 0xAAAAAA);
        } else {
            g.drawString(font, Lang.tr("§7未在播放"), textX, by + 13, 0xAAAAAA);
        }
        int barX = progressBarX();
        int barW = progressBarW();
        if (barW > 60) {
            int barY = progressBarY();
            long dur = mgr.getAudioEngine().getSongDurationMs();
            long pos = (progressDragging && dragPreviewMs >= 0)
                    ? dragPreviewMs : mgr.getAudioEngine().getCurrentPositionMs();
            g.fill(barX, barY, barX + barW, barY + 4, 0xFF333344);
            if (dur > 0 && pos >= 0) {
                int filled = (int) ((float) barW * Math.min(1f, (float) pos / dur));
                g.fill(barX, barY, barX + filled, barY + 4,
                        progressDragging ? 0xFF88CCFF : 0xFF55FF55);
            }
            String timeStr = formatDuration((int) (pos / 1000)) + " / " +
                    (dur > 0 ? formatDuration((int) (dur / 1000)) : "--:--");
            int timeW = font.width(timeStr);
            // 0.25.2：时长始终贴近进度条上方
            int timeY = barY - 11;
            g.drawString(font, progressDragging ? "§b" + timeStr : "§7" + timeStr,
                    barX + barW - timeW, timeY, 0xAAAAAA);
            // 进度条上方显示当前歌词行（翻译原大小，颜色区分：原文白色，翻译灰色）
            String[] lrc = getCurrentLyricsLine();
            if (lrc != null && lrc[0] != null && !lrc[0].isEmpty()) {
                int maxLrcW = barW - timeW - 12;
                if (maxLrcW > 20) {
                    boolean hasTrans = lrc[1] != null;
                    boolean transAfter = hasTrans
                            && MusicModConfig.getBarLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
                    // 有翻译且下方模式需两行空间 → barY-20；其余贴近进度条 → barY-11
                    int lrcY = (hasTrans && !transAfter) ? barY - 20 : barY - 11;
                    if (transAfter) {
                        // AFTER：翻译在原文后方同一行（原大小，灰色区分）
                        String orig = truncate(font, lrc[0], maxLrcW / 2);
                        g.drawString(font, "§f" + orig, barX, lrcY, 0xFFFFFF);
                        int origW = font.width(orig);
                        String trans = truncate(font, lrc[1], Math.max(20, maxLrcW - origW - 4));
                        g.drawString(font, "§7" + trans, barX + origW + 4, lrcY, 0xAAAAAA);
                    } else {
                        // BELOW：翻译在原歌词下方（原大小，灰色区分，紧贴进度条上方）
                        String display = truncate(font, lrc[0], maxLrcW);
                        g.drawString(font, "§f" + display, barX, lrcY, 0xFFFFFF);
                        if (hasTrans) {
                            String transDisplay = truncate(font, lrc[1], maxLrcW);
                            g.drawString(font, "§7" + transDisplay, barX, barY - 11, 0xAAAAAA);
                        }
                    }
                }
            }
        }

    }

    /** 播放模式对应的聊天颜色代码（0.25.2：按钮文字直接显示播放顺序） */
    private String playModeChatColor(com.musicmod.PlayMode pm) {
        switch (pm) {
            case SEQUENTIAL: return "§a";
            case LOOP_ONE:  return "§6";
            case SHUFFLE:   return "§b";
            default:        return "§7";
        }
    }

    /** 获取当前播放歌曲的当前歌词行及翻译（用于播放栏显示）
     *  @return [0]=原文，[1]=翻译（无翻译或未开启时为 null）；无歌词返回 null */
    private String[] getCurrentLyricsLine() {
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        String song = mgr.getCurrentSong();
        if (song.isEmpty()) return null;
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientTagStore.SongInfo info = tags.getSongInfo(song);
        if (info == null || info.lyrics == null || info.lyrics.isEmpty()) return null;
        List<LyricsParser.Line> lines = LyricsParser.parse(info.lyrics);
        if (lines.isEmpty() || !LyricsParser.isTimed(lines)) return null;
        long pos = (progressDragging && dragPreviewMs >= 0)
                ? dragPreviewMs : mgr.getAudioEngine().getCurrentPositionMs();
        int active = LyricsParser.findActiveLine(lines, pos);
        if (active < 0) return null;
        LyricsParser.Line line = lines.get(active);
        String trans = null;
        if (MusicModConfig.getShowTranslation()
                && line.translation != null && !line.translation.isEmpty()) {
            trans = line.translation;
        }
        return new String[]{line.text, trans};
    }
    /** 歌曲详情面板（封面 + 标签 + 歌词滚动） */
    private void renderDetailPanel(GuiGraphics g, int mouseX, int mouseY) {
        Font font = this.font;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
        ClientTagStore tags = ClientTagStore.getInstance();
        if (!tags.hasSongInfo(detailSong)) {
            tags.requestSongInfo(detailSong);
        }
        ClientTagStore.SongInfo info = tags.getSongInfo(detailSong);
        g.drawString(font, Lang.tr("§e§l歌曲详情"), x, 8, 0xFFFFFF);
        String closeText = Lang.tr("§c✕ 关闭");
        int closeX = x + w - font.width(closeText) - 6;
        int closeY = y0 + 4;
        g.fill(closeX - 3, closeY - 2, x + w - 2, closeY + 11, 0x60333333);
        g.drawString(font, closeText, closeX, closeY, 0xFF5555);
        g.fill(x - 1, y0 - 1, x + w + 1, y0 + h + 1, 0xFF4A4A60);
        g.fill(x, y0, x + w, y0 + h, 0x90000010);
        int coverX = x + 10, coverY = y0 + 10, cs = 64;
        ResourceLocation cover = CoverTextureManager.get(detailSong);
        if (cover != null) {
            int[] size = CoverTextureManager.getSize(detailSong);
            if (size != null) {
                g.fill(coverX - 1, coverY - 1, coverX + cs + 1, coverY + cs + 1, 0xFF555555);
                drawCoverScaled(g, cover, size, coverX, coverY, cs);
            }
        } else {
            g.fill(coverX, coverY, coverX + cs, coverY + cs, 0xFF2A2A3A);
            g.drawCenteredString(font, "♪", coverX + cs / 2 - 4, coverY + cs / 2 - 4, 0x555555);
        }
        int infoX = coverX + cs + 12;
        String title = (info != null && !info.title.isEmpty()) ? info.title : tags.displayTitle(detailSong);
        g.drawString(font, "§f§l" + truncate(font, title, w - (infoX - x) - 10), infoX, coverY + 4, 0xFFFFFF);
        String artist = info != null ? info.artist : tags.artist(detailSong);
        g.drawString(font, Lang.tr("§7艺术家: §f") + (artist.isEmpty() ? Lang.tr("未知") : truncate(font, artist, 150)),
                infoX, coverY + 22, 0xCCCCCC);
        String album = info != null ? info.album : tags.album(detailSong);
        g.drawString(font, Lang.tr("§7专辑: §f") + (album.isEmpty() ? Lang.tr("未知") : truncate(font, album, 150)),
                infoX, coverY + 36, 0xCCCCCC);
        long dur = info != null ? info.durationMs : tags.durationSec(detailSong) * 1000L;
        g.drawString(font, Lang.tr("§7时长: §f") + (dur > 0 ? formatDuration((int) (dur / 1000)) : Lang.tr("未知")),
                infoX, coverY + 50, 0xCCCCCC);
        boolean fav = ClientFavorites.isFavorite(detailSong);
        String favText = fav ? Lang.tr("§c♥ 已收藏") : Lang.tr("§7♡ 收藏");
        int favY = coverY + 66;
        g.drawString(font, favText, infoX, favY, 0xFFFFFF);
        detailAddSongX = -1;
        detailAddSongW = 0;
        if (isOp()) {
            String addText = Lang.tr("§e[ + 添加到歌单 ]");
            int addX = infoX + font.width(favText) + 12;
            g.drawString(font, addText, addX, favY, 0xFFD700);
            detailAddSongX = addX;
            detailAddSongW = font.width(addText);
        }
        int lrcY = coverY + cs + 16;
        int lrcH = y0 + h - lrcY - 6;
        if (lrcH < 24) return;
        g.drawString(font, Lang.tr("§e§l歌词"), x + 10, lrcY - 11, 0xFFD700);
        boolean hasTrans = false;
        String lyrics = info != null ? info.lyrics : "";
        if (lyrics != null && !lyrics.isEmpty()) {
            hasTrans = LyricsParser.hasTranslation(LyricsParser.parse(lyrics));
        }
        if (hasTrans) {
            String transBtn = MusicModConfig.getShowTranslation() ? Lang.tr("§a[译: 开]") : Lang.tr("§7[译: 关]");
            g.drawString(font, transBtn, x + w - font.width(transBtn) - 8, lrcY - 11, 0xFFFFFF);
        }
        if (!lyricsAutoFollow) {
            String followBtn = Lang.tr("§e♪ 跟随 »");
            int fX = x + w - (hasTrans ? font.width(Lang.tr("§a[译: 开]")) + 20 : 8) - font.width(followBtn);
            g.drawString(font, followBtn, fX, lrcY - 11, 0xFFD700);
        }
        g.fill(x + 4, lrcY, x + w - 4, lrcY + lrcH, 0x60000000);
        if (lyrics == null || lyrics.isEmpty()) {
            g.drawCenteredString(font, info == null ? Lang.tr("§7加载中...") : Lang.tr("§7暂无歌词"),
                    x + w / 2, lrcY + lrcH / 2 - 4, 0xAAAAAA);
            return;
        }
        List<LyricsParser.Line> lines = LyricsParser.parse(lyrics);
        if (lines.isEmpty()) {
            g.drawCenteredString(font, Lang.tr("§7暂无歌词"), x + w / 2, lrcY + lrcH / 2 - 4, 0xAAAAAA);
            return;
        }
        boolean showTrans = MusicModConfig.getShowTranslation() && hasTrans;
        boolean transAfter = showTrans
                && MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
        int lineH = 12, transH = 10;
        int[] lineYs = new int[lines.size() + 1];
        int totalH = 0;
        for (int i = 0; i < lines.size(); i++) {
            lineYs[i] = totalH;
            totalH += lineH + (!transAfter && showTrans && lines.get(i).translation != null ? transH : 0);
        }
        lineYs[lines.size()] = totalH;
        int viewH = lrcH - 8;
        int maxScroll = Math.max(0, totalH - viewH);
        long pos = ClientMusicManager.getInstance().getAudioEngine().getCurrentPositionMs();
        int active = -1;
        if (detailSong.equals(ClientMusicManager.getInstance().getCurrentSong())) {
            active = LyricsParser.findActiveLine(lines, pos);
        }
        if (lyricsAutoFollow && active >= 0 && LyricsParser.isTimed(lines)) {
            lyricsScrollPx = Math.max(0, lineYs[active] - viewH / 3);
        }
        lyricsScrollPx = Math.max(0, Math.min(maxScroll, lyricsScrollPx));
        // 剪刀区域必须使用真实屏幕坐标（GuiGraphics 的 scissor 不受 pose 位移影响）
        g.enableScissor(windowX + x + 4,
                windowY + WINDOW_TITLE_H + lrcY,
                windowX + x + w - 4,
                windowY + WINDOW_TITLE_H + lrcY + lrcH);
        int textMaxW = w - 28;
        for (int i = 0; i < lines.size(); i++) {
            int ly = lrcY + 4 + lineYs[i] - lyricsScrollPx;
            if (ly + lineH + transH < lrcY) continue;
            if (ly > lrcY + lrcH) break;
            LyricsParser.Line line = lines.get(i);
            // 0.27.1：选取框（DOUBLE 模式/详情双击播放第一次点击选中的行）——
            // 与歌曲列表同款选取框样式：行背景色区分，文字不高亮
            if (i == selectedLyricLine) {
                int selH = lineH + (!transAfter && showTrans && line.translation != null ? transH : 0);
                g.fill(x + 6, ly - 1, x + w - 10, ly + selH, 0x80555577);
            }
            String text = line.text;
            if (transAfter && line.translation != null && !line.translation.isEmpty()) {
                text = text + " §8" + line.translation;
            }
            text = truncate(font, text, textMaxW);
            if (i == active) {
                g.drawCenteredString(font, "§a§l" + text, x + w / 2, ly, 0x55FF55);
            } else {
                // 0.27.1：选取行文字不高亮，仅靠行背景色区分（与歌曲列表选取框一致）
                g.drawCenteredString(font, text, x + w / 2, ly, 0xBBBBBB);
            }
            if (!transAfter && showTrans && line.translation != null && !line.translation.isEmpty()) {
                String trans = truncate(font, line.translation, textMaxW - 8);
                g.drawCenteredString(font, "§8" + trans, x + w / 2, ly + lineH, 0x888899);
            }
        }
        g.flush();
        g.disableScissor();
        if (totalH > viewH) {
            int barX = x + w - 10;
            int trackY = lrcY + 2, trackH = lrcH - 4;
            float ratio = maxScroll > 0 ? (float) lyricsScrollPx / maxScroll : 0;
            int barH = Math.max(12, trackH * viewH / totalH);
            int barY = trackY + (int) ((trackH - barH) * ratio);
            g.fill(barX, trackY, barX + 4, trackY + trackH, 0x40000000);
            g.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
        }
    }
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // 让窗口内控件按虚拟坐标获得 hover 状态
        super.mouseMoved(mouseX - windowX, mouseY - windowY - WINDOW_TITLE_H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 窗口关闭按钮与窗口外点击（真实屏幕坐标）
        if (button == 0 && isOverClose(mouseX, mouseY)) {
            onClose();
            return true;
        }
        if (mouseX < windowX || mouseX >= windowX + windowW
                || mouseY < windowY || mouseY >= windowY + windowH) {
            return true;
        }
        if (mouseY < windowY + WINDOW_TITLE_H) {
            return true; // 标题栏
        }
        // 转换到窗口内部虚拟坐标
        mouseX -= windowX;
        mouseY -= windowY + WINDOW_TITLE_H;

        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX, my = (int) mouseY;
        Font font = this.font;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();

        if (addToPlaylistSong != null) {
            return handleAddToPlaylistPopupClick(mx, my);
        }

        // 0.27.4：点击搜索图标展开/收起搜索框
        if (detailSong == null && nav != Nav.SETTINGS && nav != Nav.PLAYLIST && inSearchIcon(mx, my)) {
            if (searchOpen) {
                closeSearch();
            } else {
                searchOpen = true;
                scrollOffset = 0;
                searchBox.setValue("");
                searchBox.setVisible(true);
                searchBox.setFocused(true); // EditBox 内部焦点（光标闪烁/输入消费）
                setFocused(searchBox);      // Screen 焦点（键盘事件委托）
            }
            return true;
        }

        // 0.28.4：搜索框展开且未输入任何文字时，点击框外收起；框内点击保持展开
        // （空框收起不改变列表内容，保留滚动位置避免点击错位；图标自身已有开关逻辑，不在此处理）
        if (searchOpen && detailSong == null && nav != Nav.SETTINGS && nav != Nav.PLAYLIST
                && !inSearchIcon(mx, my)
                && !inSearchBox(mx, my)
                && (searchBox == null || searchBox.getValue().trim().isEmpty())) {
            int keepScroll = scrollOffset;
            closeSearch();
            scrollOffset = keepScroll; // 空框收起列表内容不变，恢复滚动位置
        }

        // 0.28.0：点击排序标签循环切换排序模式
        if (detailSong == null && (nav == Nav.ALL || subPage != null)
                && mx >= sortLabelX - 2 && mx <= sortLabelX + sortLabelW + 2
                && my >= 4 && my <= 18) {
            MusicModConfig.cycleSortMode();
            scrollOffset = 0;
            return true;
        }

        // 0.28.4：点击搜索框内部交由 EditBox 定位光标/选中，不收起搜索框
        if (searchOpen && detailSong == null && nav != Nav.SETTINGS && nav != Nav.PLAYLIST
                && inSearchBox(mx, my)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // ---- 详情面板 ----
        if (detailSong != null) {
            if (mx >= x - 2 && mx <= x + w + 2 && my >= 4 && my <= y0 + h + 2) {
                // 关闭按钮
                String closeText = Lang.tr("§c✕ 关闭");
                int closeX = x + w - font.width(closeText) - 6;
                int closeY = y0 + 4;
                if (mx >= closeX - 3 && mx <= x + w - 2 && my >= closeY - 2 && my <= closeY + 11) {
                    detailSong = null;
                    resetDetailStates();
                    return true;
                }
                // 收藏切换 / 添加到歌单
                int coverY = y0 + 10;
                int infoX = x + 10 + 64 + 12;
                if (my >= coverY + 64 && my <= coverY + 78) {
                    if (isOp() && detailAddSongX >= 0
                            && mx >= detailAddSongX - 2
                            && mx <= detailAddSongX + detailAddSongW + 2) {
                        openAddToPlaylistPopup(detailSong);
                        return true;
                    }
                    if (mx >= infoX && mx <= infoX + 80) {
                        ClientFavorites.toggle(detailSong);
                        return true;
                    }
                }
                // 歌词区
                int lrcY = coverY + 64 + 16;
                int lrcH = y0 + h - lrcY - 6;
                if (lrcH >= 24) {
                    // 翻译按钮
                    ClientTagStore tags = ClientTagStore.getInstance();
                    ClientTagStore.SongInfo info = tags.getSongInfo(detailSong);
                    String lyrics = info != null ? info.lyrics : "";
                    boolean hasTrans = lyrics != null && !lyrics.isEmpty()
                            && LyricsParser.hasTranslation(LyricsParser.parse(lyrics));
                    if (hasTrans) {
                        String transBtn = MusicModConfig.getShowTranslation() ? Lang.tr("§a[译: 开]") : Lang.tr("§7[译: 关]");
                        int tX = x + w - font.width(transBtn) - 8;
                        if (mx >= tX && mx <= tX + font.width(transBtn)
                                && my >= lrcY - 11 && my <= lrcY + 1) {
                            MusicModConfig.setShowTranslation(!MusicModConfig.getShowTranslation());
                            MusicModConfig.saveClientConfig();
                            return true;
                        }
                    }
                    // 跟随按钮
                    if (!lyricsAutoFollow) {
                        String followBtn = Lang.tr("§e♪ 跟随 »");
                        int fX = x + w - (hasTrans ? font.width(Lang.tr("§a[译: 开]")) + 20 : 8)
                                - font.width(followBtn);
                        if (mx >= fX && mx <= fX + font.width(followBtn)
                                && my >= lrcY - 11 && my <= lrcY + 1) {
                            lyricsAutoFollow = true;
                            return true;
                        }
                    }
                    // 歌词滚动条
                    int barX = x + w - 10;
                    if (mx >= barX && mx <= barX + 4 && my >= lrcY && my <= lrcY + lrcH) {
                        lyricsBarDragging = true;
                        updateLyricsScrollFromMouse(my, lrcY, lrcH);
                        return true;
                    }
                    // 0.26.0：歌词内容按下仅记录拖拽状态，点击 seek 延迟到 mouseReleased，
                    // 拖动超过阈值后取消点击，同时支持触屏式拖拽滚动歌词
                    if (mx >= x + 4 && mx <= x + w - 4 && my >= lrcY && my <= lrcY + lrcH) {
                        lyricsContentDragging = true;
                        lyricsDragStartY = my;
                        lyricsDragStartScroll = lyricsScrollPx;
                        lyricsDragMoved = false;
                        return true;
                    }
                }
                return true; // 面板内其他区域不穿透
            }
            // 面板外点击不拦截，让侧栏/播放栏控件正常响应
        }

        // ---- 歌单页 ----
        if (nav == Nav.PLAYLIST) {
            Playlist opened = openedPlaylist();

            if (opened != null) {
                // 详情页顶部：点标题或「« 返回歌单」都返回歌单主菜单
                if (my >= 4 && my <= 20) {
                    String title = playlistTitle(opened);
                    int titleW = font.width("§e§l" + title);
                    int backX = x + titleW + 10;
                    int backW = font.width(Lang.tr("§7« 返回歌单"));
                    if (mx >= x && mx <= backX + backW) {
                        openedPlaylistId = null;
                        selectedPlaylistId = opened.getId();
                        playlistDeleteConfirmId = null;
                        scrollOffset = 0;
                        selectedSong = null;
                        if (playlistNameBox != null) {
                            playlistNameBox.setValue(ClientPlaylists.isBuiltIn(opened.getId())
                                    ? "" : Lang.tr(opened.getName()));
                        }
                        return true;
                    }
                }

                // 右上角「+ 添加歌曲」
                if (playlistAddX >= 0 && my >= playlistAddY
                        && my <= playlistAddY + playlistAddH
                        && mx >= playlistAddX && mx <= playlistAddX + playlistAddW) {
                    openAddMusicPicker();
                    return true;
                }

                // 详情页工具栏：播放 / 删除（二次确认）
                if (my >= y0 && my <= y0 + PLAYLIST_TOOLBAR_H) {
                    if (playlistPlayX >= 0 && mx >= playlistPlayX - 2
                            && mx <= playlistPlayX + playlistPlayW + 2
                            && my >= y0 + 3 && my <= y0 + 18) {
                        if (isOp()) {
                            if (ClientPlaylists.isBuiltIn(opened.getId())) {
                                // 内置歌单由客户端保存，播放时把歌曲列表作为队列发给服务器
                                String first = firstAvailableSong(opened);
                                if (!first.isEmpty()) {
                                    playSongWithQueue(first, opened.getSongs());
                                }
                            } else {
                                NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                                        C2SPlaylistActionPacket.ACTION_PLAY, opened.getId(), ""));
                            }
                        }
                        return true;
                    }
                    if (playlistDeleteX >= 0 && mx >= playlistDeleteX - 2
                            && mx <= playlistDeleteX + playlistDeleteW + 2
                            && my >= y0 + 3 && my <= y0 + 18) {
                        if (isOp() && !ClientPlaylists.isBuiltIn(opened.getId())) {
                            if (playlistDeleteConfirmId != null
                                    && playlistDeleteConfirmId.equals(opened.getId())) {
                                NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                                        C2SPlaylistActionPacket.ACTION_DELETE, opened.getId(), ""));
                                playlistDeleteConfirmId = null;
                                openedPlaylistId = null;
                                selectedPlaylistId = null;
                                scrollOffset = 0;
                            } else {
                                playlistDeleteConfirmId = opened.getId();
                            }
                        }
                        return true;
                    }
                    if (isOp()) return true; // 工具栏空白区域不穿透，避免误触下方列表
                }

                // 详情页歌曲列表（占满内容区宽度）
                int rowsTop = y0 + PLAYLIST_TOOLBAR_H;
                if (my >= rowsTop && my < y0 + h && mx >= x && mx <= x + w) {
                    int barX = x + w - 5;
                    if (mx >= barX && mx <= barX + 4 && curScrollTotal > curScrollVisible) {
                        dragTarget = DragTarget.LIST_BAR;
                        dragStartY = my;
                        dragStartScroll = scrollOffset;
                        dragMoved = false;
                        updateListScrollFromMouse(my);
                        return true;
                    }
                    dragTarget = DragTarget.LIST_CONTENT;
                    dragStartY = my;
                    dragStartScroll = scrollOffset;
                    dragMoved = false;
                    return true;
                }
                if (my < playerBarY()) {
                    return super.mouseClicked(mouseX, mouseY, button);
                }
            } else {
                // 列表页：左侧歌单列表 + 右侧编辑面板
                int listRight = playlistPanelX() - 6;
                if (my >= y0 && my < y0 + h && mx >= x && mx <= listRight) {
                    int barX = listRight - 5;
                    if (mx >= barX && mx <= barX + 4 && curScrollTotal > curScrollVisible) {
                        dragTarget = DragTarget.LIST_BAR;
                        dragStartY = my;
                        dragStartScroll = scrollOffset;
                        dragMoved = false;
                        updateListScrollFromMouse(my);
                        return true;
                    }
                    dragTarget = DragTarget.LIST_CONTENT;
                    dragStartY = my;
                    dragStartScroll = scrollOffset;
                    dragMoved = false;
                    return true;
                }
                if (my < playerBarY()) {
                    return super.mouseClicked(mouseX, mouseY, button);
                }
            }
            // 播放栏区域继续走下方通用处理（进度条拖动、封面跳详情等）
        }

        // ---- Lang.tr("« 返回")（子页面时） ----
        if (subPage != null && my >= 4 && my <= 20 && mx >= x && mx <= x + w) {
            int backX = x + font.width("§e§l" + subPage.title) + 8;
            int backW = font.width(Lang.tr("§7« 返回"));
            if (mx >= backX && mx <= backX + backW) {
                closeSearch(); // 0.28.2：界面跳转时收起搜索框
                subPage = null;
                scrollOffset = 0;
                selectedSong = null;
                return true;
            }
        }

        // ---- 播放栏封面 → 跳转歌曲详情 ----
        int coverY2 = playerBarY() + 4;
        if (!ClientMusicManager.getInstance().getCurrentSong().isEmpty()
                && mx >= 6 && mx <= 36 && my >= coverY2 && my <= coverY2 + 30) {
            closeSearch(); // 0.28.2：界面跳转时收起搜索框
            detailSong = ClientMusicManager.getInstance().getCurrentSong();
            resetDetailStates();
            return true;
        }

        // ---- 播放栏进度条 ----
        int pbX = progressBarX(), pbW = progressBarW(), pbY = progressBarY();
        if (pbW > 60 && my >= pbY - 2 && my <= pbY + 6 && mx >= pbX && mx <= pbX + pbW) {
            progressDragging = true;
            dragPreviewMs = clampSeekMs(mx);
            return true;
        }

        // ---- 提示行「+ 歌单」（歌曲详情入口已移除） ----
        if (selectedSong != null && !selectedSong.isEmpty() && nav != Nav.PLAYLIST
                && my >= playerBarY() - 14 && my <= playerBarY() - 1
                && mx >= x && mx <= x + w) {
            if (isOp() && addSongHintX >= 0
                    && mx >= addSongHintX - 2
                    && mx <= addSongHintX + addSongHintW + 2) {
                openAddToPlaylistPopup(selectedSong);
                return true;
            }
        }

        // ---- 列表区 ----
        if (my >= y0 && my < y0 + h && mx >= x && mx <= x + w) {
            // 设置页：仅处理滚动条拖拽，其余交给控件
            if (nav == Nav.SETTINGS) {
                int contentH = settingsContentH();
                int maxScroll = Math.max(0, contentH - h);
                if (maxScroll > 0) {
                    int sbX = x + w - 5;
                    if (mx >= sbX && mx <= sbX + 4) {
                        dragTarget = DragTarget.SETTINGS_BAR;
                        dragStartY = my;
                        dragMoved = false;
                        updateSettingsScrollFromMouse(my);
                        return true;
                    }
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
            // 0.26.0：滚动条拖拽优先于行点击，避免重叠区域无法交互
            int barX = x + w - 5;
            if (mx >= barX && mx <= barX + 4 && curScrollTotal > curScrollVisible) {
                dragTarget = DragTarget.LIST_BAR;
                dragStartY = my;
                dragStartScroll = scrollOffset;
                dragMoved = false;
                updateListScrollFromMouse(my);
                return true;
            }
            // 0.26.0：行按下时仅记录拖拽状态，点击逻辑延迟到 mouseReleased，
            // 拖动超过阈值（dragMoved）后取消点击，避免拖动滚动误触选择/播放
            dragTarget = DragTarget.LIST_CONTENT;
            dragStartY = my;
            dragStartScroll = scrollOffset;
            dragMoved = false;
            return true;
        }

        // 点击列表外空白区域（主面板范围内）仅取消选择；搜索框的收起到此之前已统一处理
        if (detailSong == null && nav != Nav.PLAYLIST
                && mx >= x && mx <= x + w && my >= 4 && my < y0 + h + 2) {
            selectedSong = null;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < windowX || mouseX >= windowX + windowW
                || mouseY < windowY || mouseY >= windowY + windowH) {
            return true;
        }
        mouseX -= windowX;
        mouseY -= windowY + WINDOW_TITLE_H;
        int mx = (int) mouseX, my = (int) mouseY;
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();

        // 添加到歌单弹窗滚动
        if (addToPlaylistSong != null) {
            int maxScroll = Math.max(0,
                    ClientPlaylists.getInstance().size() - playlistPopupRows());
            playlistPopupScroll = Math.max(0, Math.min(maxScroll,
                    playlistPopupScroll - (int) Math.signum(delta)));
            return true;
        }

        // 详情面板歌词滚动
        if (detailSong != null) {
            int lrcY = y0 + 10 + 64 + 16;
            int lrcH = y0 + h - lrcY - 6;
            if (my >= lrcY && my <= lrcY + lrcH && mx >= x && mx <= x + w && lrcH >= 24) {
                lyricsAutoFollow = false;
                lyricsScrollPx -= (int) delta * 14;
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        // 歌单页滚动
        if (nav == Nav.PLAYLIST) {
            Playlist opened = openedPlaylist();
            int listRight = opened == null ? playlistPanelX() - 6 : x + w;
            int rowsTop = opened == null ? y0 : y0 + PLAYLIST_TOOLBAR_H;
            if (my >= rowsTop && my < y0 + h && mx >= x && mx <= listRight) {
                int maxScroll = Math.max(0, curScrollTotal - curScrollVisible);
                scrollOffset = Math.max(0, Math.min(maxScroll,
                        scrollOffset - (int) Math.signum(delta)));
            }
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        // 设置页滚动
        if (nav == Nav.SETTINGS) {
            if (my >= y0 && my < y0 + h && mx >= x && mx <= x + w) {
                int contentH = settingsContentH();
                int maxScroll = Math.max(0, contentH - h);
                if (maxScroll > 0) {
                    settingsScroll = Math.max(0, Math.min(maxScroll,
                            settingsScroll - (int) Math.signum(delta) * 26));
                }
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        // 列表滚动
        if (my >= y0 && my < y0 + h && mx >= x && mx <= x + w) {
            if (curScrollTotal > curScrollVisible) {
                int maxScroll = curScrollTotal - curScrollVisible;
                scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
            }
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mouseX -= windowX;
        mouseY -= windowY + WINDOW_TITLE_H;
        int mx = (int) mouseX, my = (int) mouseY;

        // 进度条拖动
        if (progressDragging) {
            dragPreviewMs = clampSeekMs(mx);
            return true;
        }

        // 歌词滚动条拖动
        if (lyricsBarDragging) {
            int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
            int lrcY = y0 + 10 + 64 + 16;
            int lrcH = y0 + h - lrcY - 6;
            if (lrcH >= 24) updateLyricsScrollFromMouse(my, lrcY, lrcH);
            return true;
        }

        // 0.26.0：歌词内容区拖拽 → 触屏式滚动，超过阈值取消点击 seek
        if (lyricsContentDragging) {
            int dy = my - (int) lyricsDragStartY;
            int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
            int lrcY = y0 + 10 + 64 + 16;
            int lrcH = y0 + h - lrcY - 6;
            int maxScroll = getLyricsMaxScroll(lrcH);
            lyricsScrollPx = Math.max(0, Math.min(maxScroll, lyricsDragStartScroll - dy));
            if (Math.abs(dy) > 3) {
                lyricsDragMoved = true;
                lyricsAutoFollow = false;
            }
            return true;
        }

        // 列表滚动条拖动
        if (dragTarget == DragTarget.LIST_BAR) {
            updateListScrollFromMouse(my);
            dragMoved = true;
            return true;
        }

        // 设置页滚动条拖动
        if (dragTarget == DragTarget.SETTINGS_BAR) {
            updateSettingsScrollFromMouse(my);
            dragMoved = true;
            return true;
        }

        // 列表内容区拖动（触屏式滚动）
        if (dragTarget == DragTarget.LIST_CONTENT) {
            int dy = my - (int) dragStartY;
            int dScroll = -dy / Math.max(1, curScrollRowH);
            int maxScroll = Math.max(0, curScrollTotal - curScrollVisible);
            scrollOffset = Math.max(0, Math.min(maxScroll, dragStartScroll + dScroll));
            if (Math.abs(dy) > 3) dragMoved = true;
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseX -= windowX;
        mouseY -= windowY + WINDOW_TITLE_H;
        int mx = (int) mouseX, my = (int) mouseY;

        // 进度条释放 → 发送 seek
        if (progressDragging && button == 0) {
            progressDragging = false;
            long seekMs = dragPreviewMs >= 0 ? dragPreviewMs : clampSeekMs(mx);
            dragPreviewMs = -1;
            NetworkHandler.sendToServer(new C2SMusicActionPacket(
                    C2SMusicActionPacket.ACTION_SEEK, "", seekMs));
            return true;
        }

        // 歌词滚动条释放
        if (lyricsBarDragging) {
            lyricsBarDragging = false;
            return true;
        }

        // 0.26.0：歌词内容区释放 → 未拖动时执行延迟点击
        // 0.27.0：支持单击/双击跳转模式 + 详情双击播放
        if (lyricsContentDragging && button == 0) {
            boolean wasDrag = lyricsDragMoved;
            lyricsContentDragging = false;
            lyricsDragMoved = false;
            if (!wasDrag && detailSong != null) {
                int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
                int lrcY = y0 + 10 + 64 + 16;
                int lrcH = y0 + h - lrcY - 6;
                if (lrcH >= 24 && my >= lrcY && my <= lrcY + lrcH
                        && mx >= x + 4 && mx <= x + w - 4) {
                    handleLyricsClick(my, lrcY, lrcH);
                }
            }
            return true;
        }

        // 0.26.0：列表内容区释放 → 未拖动时执行延迟点击（选择/双击播放/收藏/进入分组）
        if (dragTarget == DragTarget.LIST_CONTENT && button == 0) {
            boolean wasDrag = dragMoved;
            dragTarget = DragTarget.NONE;
            dragMoved = false;
            if (!wasDrag) {
                // 0.27.0：点击列表内空白行（未命中有效行）取消选择
                if (!handleListClick(mx, my)) {
                    selectedSong = null;
                }
            }
            return true;
        }
        // 其他拖动释放（滚动条等）
        if (dragTarget != DragTarget.NONE) {
            dragTarget = DragTarget.NONE;
            dragMoved = false;
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 0.26.0：mouseReleased 中执行延迟的列表点击（仅未拖动时调用） */
    private boolean handleListClick(int mx, int my) {
        if (nav == Nav.PLAYLIST) {
            return handlePlaylistListClick(mx, my);
        }
        int x = listX(), w = listW(), y0 = LIST_TOP, h = listH();
        if (my < y0 || my >= y0 + h || mx < x || mx > x + w) return false;
        // 歌曲列表（全部歌曲 / 歌手子页 / 专辑子页）
        if (nav == Nav.ALL || ((nav == Nav.ARTIST || nav == Nav.ALBUM) && subPage != null)) {
            List<String> songs = currentSongs();
            int rows = h / ROW_H;
            int rowIdx = (my - y0) / ROW_H;
            int idx = scrollOffset + rowIdx;
            if (rowIdx < rows && idx >= 0 && idx < songs.size()) {
                String song = songs.get(idx);
                // ♥ 收藏
                int colHeart = x + w - 20;
                if (mx >= colHeart - 2 && mx <= colHeart + 14) {
                    ClientFavorites.toggle(song);
                    return true;
                }
                // 双击播放（0.25.1：正在播放/暂停的歌曲双击则切换暂停/恢复）
                long now = System.currentTimeMillis();
                if (song.equals(lastClickedSong) && now - lastClickTime < 450) {
                    selectedSong = song;
                    ClientMusicManager mgr = ClientMusicManager.getInstance();
                    if (song.equals(mgr.getCurrentSong()) && (mgr.isPlaying() || mgr.isPaused())) {
                        sendAction(C2SMusicActionPacket.ACTION_PAUSE);
                    } else {
                        playSongWithQueue(song, songs);
                    }
                    return true;
                }
                lastClickTime = now;
                lastClickedSong = song;
                selectedSong = song;
                return true;
            }
        } else if (nav == Nav.ARTIST || nav == Nav.ALBUM) {
            // 分组列表（0.27.0：应用搜索过滤）
            Map<String, List<String>> groups = filteredGroups(
                    nav == Nav.ARTIST ? groupByArtist() : groupByAlbum());
            List<String> keys = new ArrayList<>(groups.keySet());
            int rows = h / (ROW_H + 4);
            int rowIdx = (my - y0) / (ROW_H + 4);
            int idx = scrollOffset + rowIdx;
            if (rowIdx < rows && idx >= 0 && idx < keys.size()) {
                String key = keys.get(idx);
                closeSearch(); // 0.28.2：界面跳转时收起搜索框
                subPage = new SubPage(key, groups.get(key));
                scrollOffset = 0;
                selectedSong = null;
                return true;
            }
        }
        return false;
    }

    /** 歌单列表/歌单详情内的点击（选择、双击打开/播放、移除歌曲） */
    private boolean handlePlaylistListClick(int mx, int my) {
        int x = listX(), w = listW();
        Playlist opened = openedPlaylist();
        int rowsTop;
        int rowsAreaH;
        int listRight;
        if (opened == null) {
            rowsTop = LIST_TOP;
            rowsAreaH = listH();
            listRight = playlistPanelX() - 6;
        } else {
            rowsTop = LIST_TOP + PLAYLIST_TOOLBAR_H;
            rowsAreaH = Math.max(PLAYLIST_ROW_H, listH() - PLAYLIST_TOOLBAR_H);
            listRight = x + w;
        }
        if (my < rowsTop || my >= rowsTop + rowsAreaH || mx < x || mx > listRight) return false;
        int row = (my - rowsTop) / PLAYLIST_ROW_H;
        if (row < 0 || row >= curScrollVisible) return false;
        int idx = scrollOffset + row;

        if (opened == null) {
            List<Playlist> playlists = ClientPlaylists.getInstance().getDisplayPlaylists();
            if (idx < 0 || idx >= playlists.size()) return false;
            Playlist playlist = playlists.get(idx);
            selectedPlaylistId = playlist.getId();
            playlistDeleteConfirmId = null;
            if (playlistNameBox != null) {
                playlistNameBox.setValue(ClientPlaylists.isBuiltIn(playlist.getId())
                        ? "" : playlist.getName());
            }
            long now = System.currentTimeMillis();
            if (playlist.getId().equals(lastClickedPlaylistId) && now - lastPlaylistClickTime < 450) {
                openedPlaylistId = playlist.getId();
                scrollOffset = 0;
                selectedSong = null;
                if (playlistNameBox != null) playlistNameBox.setValue("");
            }
            lastClickedPlaylistId = playlist.getId();
            lastPlaylistClickTime = now;
            return true;
        }

        List<String> songs = opened.getSongs();
        if (idx < 0 || idx >= songs.size()) return false;
        String song = songs.get(idx);
        boolean builtIn = ClientPlaylists.isBuiltIn(opened.getId());

        // 行尾 ✕：自定义歌单从服务器移除；内置收藏/最近播放直接修改本地数据
        if ((isOp() || builtIn) && mx >= listRight - 26) {
            if (ClientPlaylists.isFavorites(opened.getId())) {
                ClientFavorites.remove(song);
            } else if (ClientPlaylists.isRecent(opened.getId())) {
                ClientMusicManager.getInstance().removeRecent(song);
            } else {
                NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                        C2SPlaylistActionPacket.ACTION_REMOVE_SONG, opened.getId(), song));
            }
            return true;
        }

        // 双击播放：自定义歌单走服务端歌单播放，内置歌单把本地列表作为队列
        long now = System.currentTimeMillis();
        if (song.equals(lastClickedSong) && now - lastClickTime < 450) {
            selectedSong = song;
            if (isOp()) {
                if (builtIn) {
                    playSongWithQueue(song, songs);
                } else {
                    NetworkHandler.sendToServer(new C2SPlaylistActionPacket(
                            C2SPlaylistActionPacket.ACTION_PLAY_SONG, opened.getId(), song));
                }
            }
            return true;
        }
        lastClickTime = now;
        lastClickedSong = song;
        selectedSong = song;
        return true;
    }

    private void updateLyricsScrollFromMouse(int my, int lrcY, int lrcH) {
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientTagStore.SongInfo info = tags.getSongInfo(detailSong);
        String lyrics = info != null ? info.lyrics : "";
        if (lyrics == null || lyrics.isEmpty()) return;
        List<LyricsParser.Line> lines = LyricsParser.parse(lyrics);
        if (lines.isEmpty()) return;
        boolean showTrans = MusicModConfig.getShowTranslation() && LyricsParser.hasTranslation(lines);
        boolean transAfter = showTrans
                && MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
        int lineH = 12, transH = 10;
        int totalH = 0;
        for (LyricsParser.Line line : lines) {
            totalH += lineH + (!transAfter && showTrans && line.translation != null ? transH : 0);
        }
        int viewH = lrcH - 8;
        int maxScroll = Math.max(0, totalH - viewH);
        if (maxScroll <= 0) return;
        lyricsAutoFollow = false;
        int trackY = lrcY + 2, trackH = lrcH - 4;
        int barH = Math.max(12, trackH * viewH / totalH);
        float ratio = Math.max(0f, Math.min(1f,
                (float) (my - trackY - barH / 2) / Math.max(1, trackH - barH)));
        lyricsScrollPx = (int) (ratio * maxScroll);
    }

    private void updateListScrollFromMouse(int my) {
        int y0, h;
        if (nav == Nav.PLAYLIST) {
            Playlist opened = openedPlaylist();
            if (opened == null) {
                y0 = LIST_TOP;
                h = listH();
            } else {
                y0 = LIST_TOP + PLAYLIST_TOOLBAR_H;
                h = Math.max(PLAYLIST_ROW_H, listH() - PLAYLIST_TOOLBAR_H);
            }
        } else {
            y0 = LIST_TOP;
            h = listH();
        }
        if (curScrollTotal <= curScrollVisible) return;
        int barH = Math.max(12, (int) ((float) h * curScrollVisible / curScrollTotal));
        float ratio = Math.max(0f, Math.min(1f,
                (float) (my - y0 - barH / 2) / Math.max(1, h - barH)));
        int maxScroll = curScrollTotal - curScrollVisible;
        scrollOffset = (int) (ratio * maxScroll);
    }

    private void updateSettingsScrollFromMouse(int my) {
        int y0 = LIST_TOP, h = listH();
        int contentH = settingsContentH();
        int maxScroll = Math.max(0, contentH - h);
        if (maxScroll <= 0) return;
        int barH = Math.max(12, h * h / contentH);
        float ratio = Math.max(0f, Math.min(1f,
                (float) (my - y0 - barH / 2) / Math.max(1, h - barH)));
        settingsScroll = (int) (ratio * maxScroll);
    }

    private long clampSeekMs(int mx) {
        int barX = progressBarX(), barW = progressBarW();
        long dur = ClientMusicManager.getInstance().getAudioEngine().getSongDurationMs();
        if (dur <= 0) return 0;
        float ratio = Math.max(0f, Math.min(1f, (float) (mx - barX) / Math.max(1, barW)));
        return (long) (ratio * dur);
    }

    /** 0.26.0：计算歌词区最大滚动量（与渲染逻辑一致：lineH=12, transH=10） */
    private int getLyricsMaxScroll(int lrcH) {
        if (detailSong == null) return 0;
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientTagStore.SongInfo info = tags.getSongInfo(detailSong);
        String lyrics = info != null ? info.lyrics : "";
        if (lyrics == null || lyrics.isEmpty()) return 0;
        List<LyricsParser.Line> lines = LyricsParser.parse(lyrics);
        if (lines.isEmpty()) return 0;
        boolean hasTrans = LyricsParser.hasTranslation(lines);
        boolean showTrans = MusicModConfig.getShowTranslation() && hasTrans;
        boolean transAfter = showTrans
                && MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
        int lineH = 12, transH = 10;
        int totalH = 0;
        for (LyricsParser.Line line : lines) {
            totalH += lineH + (!transAfter && showTrans && line.translation != null ? transH : 0);
        }
        int viewH = lrcH - 8;
        return Math.max(0, totalH - viewH);
    }

    /** 点击歌词行跳转播放位置（仅当详情歌曲正在播放时生效） */
    /** 0.27.0：歌词点击处理（支持单击/双击跳转模式 + 详情双击播放） */
    private void handleLyricsClick(int my, int lrcY, int lrcH) {
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        boolean isCurrentSong = detailSong.equals(mgr.getCurrentSong());
        // 查看非播放歌曲详情且未开启Lang.tr("详情双击播放") → 不处理
        if (!isCurrentSong && !MusicModConfig.getDetailDblClickPlay()) return;
        ClientTagStore tags = ClientTagStore.getInstance();
        ClientTagStore.SongInfo info = tags.getSongInfo(detailSong);
        String lyrics = info != null ? info.lyrics : "";
        if (lyrics == null || lyrics.isEmpty()) return;
        List<LyricsParser.Line> lines = LyricsParser.parse(lyrics);
        if (lines.isEmpty() || !LyricsParser.isTimed(lines)) return;
        boolean showTrans = MusicModConfig.getShowTranslation() && LyricsParser.hasTranslation(lines);
        boolean transAfter = showTrans
                && MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
        int lineH = 12, transH = 10;
        int[] lineYs = new int[lines.size() + 1];
        int totalH = 0;
        for (int i = 0; i < lines.size(); i++) {
            lineYs[i] = totalH;
            totalH += lineH + (!transAfter && showTrans && lines.get(i).translation != null ? transH : 0);
        }
        lineYs[lines.size()] = totalH;
        int clickY = my - lrcY - 4 + lyricsScrollPx;
        int clicked = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (clickY >= lineYs[i] && clickY < lineYs[i + 1]) {
                clicked = i;
                break;
            }
        }
        if (clicked < 0 || lines.get(clicked).timeMs < 0) {
            selectedLyricLine = -1; // 点击空白/无时间戳行清除选取
            return;
        }
        long timeMs = lines.get(clicked).timeMs;
        long now = System.currentTimeMillis();
        boolean isDoubleHit = selectedLyricLine == clicked && now - lastLyricClickTime < 450;
        if (isCurrentSong) {
            // 当前播放歌曲：按歌词跳转模式处理
            if (MusicModConfig.getLyricsClickMode() == MusicModConfig.LyricsClickMode.SINGLE) {
                // 单击直接跳转
                NetworkHandler.sendToServer(new C2SMusicActionPacket(
                        C2SMusicActionPacket.ACTION_SEEK, "", timeMs));
                lyricsAutoFollow = true;
            } else {
                // 双击跳转：第一次点击选中显示选取框，第二次点击同一行才 seek
                if (isDoubleHit) {
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_SEEK, "", timeMs));
                    lyricsAutoFollow = true;
                    selectedLyricLine = -1;
                } else {
                    selectedLyricLine = clicked;
                }
            }
        } else {
            // 查看非播放歌曲详情 + Lang.tr("详情双击播放")开启：双击播放该歌曲并跳转对应进度
            if (isDoubleHit) {
                playSongWithQueue(detailSong, currentSongs());
                NetworkHandler.sendToServer(new C2SMusicActionPacket(
                        C2SMusicActionPacket.ACTION_SEEK, "", timeMs));
                selectedLyricLine = -1;
            } else {
                selectedLyricLine = clicked;
            }
        }
        lastLyricClickTime = now;
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

    private Component styleBtnText() {
        return Component.literal(MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER
                ? Lang.tr("播放器风格 (当前)") : Lang.tr("经典风格 (当前)"));
    }

    private Component hudBtnText() {
        MusicModConfig.HudMode m = MusicModConfig.getHudMode();
        String s = m == MusicModConfig.HudMode.LYRICS ? Lang.tr("歌词")
                : m == MusicModConfig.HudMode.SONG_NAME ? Lang.tr("歌名")
                : m == MusicModConfig.HudMode.CARD ? Lang.tr("卡片") : Lang.tr("关闭");
        return Component.literal("HUD: " + s);
    }

    /** 0.29.0：HUD 位置按钮文本 */
    private Component hudPosBtnText() {
        MusicModConfig.HudPosition p = MusicModConfig.getHudPosition();
        String s = p == MusicModConfig.HudPosition.TOP_LEFT ? Lang.tr("左上角")
                : p == MusicModConfig.HudPosition.TOP_RIGHT ? Lang.tr("右上角")
                : p == MusicModConfig.HudPosition.BOTTOM_LEFT ? Lang.tr("左下角")
                : p == MusicModConfig.HudPosition.BOTTOM_RIGHT ? Lang.tr("右下角") : Lang.tr("顶部中央");
        return Component.literal(Lang.tr("HUD 位置: ") + s);
    }

    private Component translationBtnText() {
        return Component.literal(MusicModConfig.getShowTranslation() ? Lang.tr("显示翻译") : Lang.tr("仅原文"));
    }

    private Component noteIconBtnText() {
        return Component.literal(MusicModConfig.getShowHudNoteIcon() ? Lang.tr("显示音符") : Lang.tr("隐藏音符"));
    }

    private Component lyricsAlignBtnText() {
        return Component.literal(MusicModConfig.getHudLyricsAlign() == MusicModConfig.HudLyricsAlign.CENTER
                ? Lang.tr("居中对齐") : Lang.tr("左对齐"));
    }

    private Component hudTransPosBtnText() {
        return Component.literal(MusicModConfig.getHudLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("HUD 翻译: 下方") : Lang.tr("HUD 翻译: 后方"));
    }

    private Component detailTransPosBtnText() {
        return Component.literal(MusicModConfig.getDetailLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("详情翻译: 下方") : Lang.tr("详情翻译: 后方"));
    }

    private Component barTransPosBtnText() {
        return Component.literal(MusicModConfig.getBarLyricsTransPos() == MusicModConfig.LyricsTransPos.BELOW
                ? Lang.tr("进度条翻译: 下方") : Lang.tr("进度条翻译: 后方"));
    }

    /** 0.27.0：歌词点击模式按钮文本 */
    private Component lyricsClickModeBtnText() {
        return Component.literal(MusicModConfig.getLyricsClickMode() == MusicModConfig.LyricsClickMode.SINGLE
                ? Lang.tr("歌词跳转: 单击") : Lang.tr("歌词跳转: 双击"));
    }

    /** 0.27.0：详情双击播放按钮文本 */
    private Component detailDblClickPlayBtnText() {
        return Component.literal(MusicModConfig.getDetailDblClickPlay()
                ? Lang.tr("详情双击播放: 开") : Lang.tr("详情双击播放: 关"));
    }

    private Component playModeBtnText() {
        return Component.literal(Lang.tr("播放模式: ") + MusicModConfig.getServerDefaultPlayMode().getDisplayName());
    }

    private Component emptyServerBtnText() {
        return Component.literal(Lang.tr("无人时: ") + ClientMusicManager.getInstance().getLastEmptyBehavior().getDisplayName());
    }

    private void sendAction(byte action) {
        NetworkHandler.sendToServer(new C2SMusicActionPacket(action, ""));
    }

    private void requestSongList() {
        NetworkHandler.sendToServer(new C2SRequestListPacket());
    }

    /** 播放/暂停按钮：正在播放→暂停，已暂停→继续，未播放→播放选中歌曲 */
    private void onPlayPauseClicked() {
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        if (mgr.isPlaying() || mgr.isPaused()) {
            sendAction(C2SMusicActionPacket.ACTION_PAUSE);
        } else if (selectedSong != null && !selectedSong.isEmpty()) {
            playSongWithQueue(selectedSong, currentSongs());
        }
    }

    /** 带播放队列播放（从全部歌曲/歌手/专辑上下文中双击播放时使用） */
    private void playSongWithQueue(String song, List<String> queue) {
        if (queue != null && queue.size() > 1) {
            NetworkHandler.sendToServer(new C2SMusicActionPacket(
                    C2SMusicActionPacket.ACTION_PLAY_WITH_QUEUE, song, new ArrayList<>(queue)));
        } else {
            NetworkHandler.sendToServer(new C2SMusicActionPacket(
                    C2SMusicActionPacket.ACTION_PLAY, song));
        }
    }

    /** 内置歌单播放时选择第一首当前服务器实际存在的歌曲 */
    private String firstAvailableSong(Playlist playlist) {
        Set<String> library = new HashSet<>(allSongs());
        for (String song : playlist.getSongs()) {
            if (library.contains(song)) return song;
        }
        return "";
    }

    private String formatDuration(int sec) {
        if (sec < 0) return "--:--";
        return String.format("%d:%02d", sec / 60, sec % 60);
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

    /** 按目标尺寸等比缩放绘制封面（居中裁剪） */
    private void drawCoverScaled(GuiGraphics g, ResourceLocation cover, int[] srcSize,
                                  int dx, int dy, int dSize) {
        if (cover == null || srcSize == null || srcSize.length < 2) return;
        int srcW = srcSize[0], srcH = srcSize[1];
        if (srcW <= 0 || srcH <= 0) return;
        // 等比缩放：取较小边比例，居中裁剪
        float scale = (float) dSize / Math.min(srcW, srcH);
        int drawW = (int) (srcW * scale);
        int drawH = (int) (srcH * scale);
        int offsetX = dx - (drawW - dSize) / 2;
        int offsetY = dy - (drawH - dSize) / 2;
        g.blit(cover, offsetX, offsetY, 0, 0, dSize, dSize, drawW, drawH);
    }

    @Override
    public void removed() {
        // 音量可能被滑块修改，界面关闭时落盘
        MusicModConfig.saveClientConfig();
    }
}