package com.musicmod.client.gui;

import com.musicmod.i18n.Lang;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.client.ClientTagStore;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SMusicActionPacket;
import com.musicmod.network.packet.C2SRequestListPacket;
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
import java.util.List;

/**
 * 一起听播放器界面（精简模式）。
 * <p>
 * 功能：
 * - 显示服务器歌曲列表（可滚动、可选中、双击播放）
 * - 播放控制：上一首 / 播放暂停 / 停止 / 下一首 / 队列列表
 * - 个人音量滑块（客户端配置，实时生效）
 * - 随机 / 连播开关（OP 限定，状态同步自服务器）
 * - 进入配置界面（修改模组全部可编辑配置）
 * <p>
 * 默认按 F8 键打开（见 {@code ModKeyBindings}）。
 */
@OnlyIn(Dist.CLIENT)
public class MusicPlayerScreen extends Screen {

    private static final int LIST_W = 240;
    private static final int LIST_ROWS = 7;
    private static final int ROW_H = 14;
    private static final int TITLE_Y = 16;
    private static final int STATUS_Y = 28;
    // 0.27.3：下移 8px，给全宽搜索框腾出空间（搜索框在状态行与列表之间）
    private static final int LIST_Y = 52;

    private int listX, listH;
    private int scrollOffset = 0;
    private int selectedSong = -1;
    private long lastClickTime = 0;
    private int lastClickedRow = -1;

    private List<String> songs = new ArrayList<>();
    // 0.27.0：搜索过滤后的显示列表
    private List<String> displaySongs = new ArrayList<>();
    private EditBox searchBox;
    private String searchFilter = "";
    // 0.28.0：排序标签命中区（render 时更新，mouseClicked 时判定）
    private int sortLabelX = -1;
    private int sortLabelW = 0;
    // 0.27.2：列表拖动滚动（与 MusicAppScreen 一致的触屏式滚动）
    private boolean listDragging = false;
    private boolean barDragging = false;
    private int dragStartY = 0;
    private int dragStartScroll = 0;
    private boolean dragMoved = false;

    private Button prevBtn, playPauseBtn, stopBtn, nextBtn, refreshBtn;
    private Button playModeBtn, configBtn, doneBtn, uploadBtn;
    private VolumeSlider volumeSlider;

    public MusicPlayerScreen() {
        super(Component.literal(Lang.tr("一起听 · 精简模式")));
    }

    private boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    @Override
    protected void init() {
        super.init();
        refreshLocalList();

        int cx = this.width / 2;
        listX = cx - LIST_W / 2;
        listH = LIST_ROWS * ROW_H;

        boolean op = isOp();

        // ---- 控制按钮行 ----
        // 0.27.3：+12 给列表下方的计数/已选文字腾出空间
        int btnY = LIST_Y + listH + 12;
        int btnW = 44;
        int gap = 4;
        int totalW = btnW * 5 + gap * 4;
        int bx = cx - totalW / 2;

        prevBtn = addBtn("⏮", bx, btnY, btnW,
                () -> sendAction(C2SMusicActionPacket.ACTION_PREV));
        bx += btnW + gap;
        playPauseBtn = addBtn("▶", bx, btnY, btnW, this::onPlayPauseClicked);
        bx += btnW + gap;
        stopBtn = addBtn("⏹", bx, btnY, btnW,
                () -> sendAction(C2SMusicActionPacket.ACTION_STOP));
        bx += btnW + gap;
        nextBtn = addBtn("⏭", bx, btnY, btnW,
                () -> sendAction(C2SMusicActionPacket.ACTION_NEXT));
        bx += btnW + gap;
        refreshBtn = addBtn("☰", bx, btnY, btnW, this::openQueueScreen);

        if (!op) {
            prevBtn.active = false;
            playPauseBtn.active = false;
            stopBtn.active = false;
            nextBtn.active = false;
        }

        // ---- 音量滑块 ----
        int volY = btnY + 24;
        volumeSlider = new VolumeSlider(cx - LIST_W / 2, volY, LIST_W, 20,
                MusicModConfig.getClientVolume());
        this.addRenderableWidget(volumeSlider);

        // 0.27.3：搜索框一直显示，宽度与歌曲列表一致，不显示搜索图标
        searchBox = new EditBox(this.font, listX, LIST_Y - 13, LIST_W, 12,
                Component.literal(Lang.tr("搜索")));
        searchBox.setMaxLength(64);
        // 0.28.2：搜索框为空时显示提示文字
        searchBox.setHint(Component.literal(Lang.tr("§7搜索歌曲 / 歌手...")));
        searchBox.setResponder(text -> {
            searchFilter = text.trim().toLowerCase();
            updateFilteredSongs();
        });
        this.addRenderableWidget(searchBox);

        // ---- 功能按钮行 ----
        // 0.27.5：单人模式不显示上传按钮（本机即服务器，无需上传），按钮行按实际数量居中
        int tY = volY + 24;
        int tW = 60;
        boolean singleplayer = Minecraft.getInstance().getSingleplayerServer() != null;
        int tCount = singleplayer ? 4 : 5;
        int tTotal = tW * tCount + gap * (tCount - 1);
        int tx = cx - tTotal / 2;
        playModeBtn = addBtn(Lang.tr("顺序播放"), tx, tY, tW,
                () -> sendAction(C2SMusicActionPacket.ACTION_PLAY_MODE));
        tx += tW + gap;
        Button styleBtn = addBtn(Lang.tr("♪ 播放器"), tx, tY, tW, () -> {
            MusicModConfig.setUiStyle(MusicModConfig.UiStyle.PLAYER);
            MusicModConfig.saveClientConfig();
            Minecraft.getInstance().setScreen(new MusicAppScreen());
        });
        tx += tW + gap;
        configBtn = addBtn(Lang.tr("⚙ 配置"), tx, tY, tW, () ->
                Minecraft.getInstance().setScreen(new MusicModConfigScreen(this)));
        tx += tW + gap;
        if (!singleplayer) {
            uploadBtn = addBtn(Lang.tr("⬆ 上传"), tx, tY, tW,
                    () -> com.musicmod.client.ClientFileUploader.openUploadDialog());
            tx += tW + gap;
        }
        doneBtn = addBtn(Lang.tr("关闭"), tx, tY, tW, this::onClose);

        if (!op) {
            playModeBtn.active = false;
            if (uploadBtn != null) uploadBtn.active = false;
        }

        // 打开时向服务器请求最新列表
        requestSongList();
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
        refreshLocalList();
        if (searchBox != null) searchBox.tick();

        ClientMusicManager mgr = ClientMusicManager.getInstance();
        // 播放/暂停按钮状态
        if (mgr.isPlaying() && !mgr.isPaused()) {
            playPauseBtn.setMessage(Component.literal("⏸"));
        } else {
            playPauseBtn.setMessage(Component.literal("▶"));
        }
        // 播放模式按钮直接显示文字（不用图标）
        com.musicmod.PlayMode pm = mgr.getLastPlayMode();
        playModeBtn.setMessage(Component.literal(pm.getDisplayName()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        Font font = this.font;

        // 标题
        guiGraphics.drawCenteredString(font, this.title, cx, TITLE_Y, 0xFFD700);

        // 当前播放状态（0.27.0：左侧不显示任何图标，只显示歌曲名称）
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        String statusLine;
        String curTitle = ClientTagStore.getInstance().displayTitle(mgr.getCurrentSong());
        if (mgr.isPlaying() || mgr.isPaused()) {
            statusLine = "§f" + curTitle;
        } else {
            statusLine = Lang.tr("§7未在播放");
        }
        guiGraphics.drawCenteredString(font, statusLine, cx, STATUS_Y, 0xFFFFFF);

        // 歌曲列表背景与边框
        guiGraphics.fill(listX - 1, LIST_Y - 1, listX + LIST_W + 1, LIST_Y + listH + 1, 0xFF555555);
        guiGraphics.fill(listX, LIST_Y, listX + LIST_W, LIST_Y + listH, 0xA0000000);

        // 歌曲行（0.27.0：使用搜索过滤后的 displaySongs）
        String currentSong = mgr.getCurrentSong();
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= displaySongs.size()) break;
            int rowY = LIST_Y + i * ROW_H;
            String song = displaySongs.get(idx);
            if (idx == selectedSong) {
                guiGraphics.fill(listX, rowY, listX + LIST_W, rowY + ROW_H, 0x80666666);
            }
            boolean isCurrent = song.equals(currentSong);
            // 0.27.0：与播放器风格一致——双竖线=正在播放，三角=暂停
            String prefix = isCurrent ? (mgr.isPaused() ? "§e▶ " : "§a⏸ ") : "  ";
            int color = isCurrent ? (mgr.isPaused() ? 0xFFFF55 : 0x55FF55) : 0xFFFFFF;
            // 0.28.1：行右侧显示作者（右对齐，避让滚动条），标题相应截断避让
            ClientTagStore tags = ClientTagStore.getInstance();
            String artist = tags.artist(song);
            String artistText = artist.isEmpty() ? "" : truncate(font, artist, 80);
            int artistRight = listX + LIST_W - 8; // 让出滚动条5px + 3px间距
            int artistX = artistRight - font.width(artistText);
            int prefixW = font.width(prefix);
            int titleAvail = artistText.isEmpty()
                    ? (LIST_W - 8 - prefixW)
                    : (artistX - (listX + 4) - 8 - prefixW);
            String display = truncate(font, tags.displayTitle(song), Math.max(20, titleAvail));
            guiGraphics.drawString(font, prefix + display, listX + 4, rowY + 3, color);
            if (!artistText.isEmpty()) {
                guiGraphics.drawString(font, "§7" + artistText, artistX, rowY + 3, 0xAAAAAA);
            }
        }

        // 空列表提示
        if (displaySongs.isEmpty()) {
            String emptyMsg = searchFilter.isEmpty()
                    ? Lang.tr("§7歌曲列表为空，请先放入歌曲并刷新") : Lang.tr("§7（无匹配歌曲）");
            guiGraphics.drawCenteredString(font, emptyMsg, cx, LIST_Y + listH / 2 - 4, 0xAAAAAA);
        }

        // 滚动条
        if (displaySongs.size() > LIST_ROWS) {
            int barX = listX + LIST_W - 5;
            float ratio = (float) scrollOffset / Math.max(1, displaySongs.size() - LIST_ROWS);
            int barH = Math.max(12, (int) ((float) listH * LIST_ROWS / displaySongs.size()));
            int barY = LIST_Y + (int) ((listH - barH) * ratio);
            guiGraphics.fill(barX, LIST_Y, barX + 4, LIST_Y + listH, 0x40000000);
            guiGraphics.fill(barX, barY, barX + 4, barY + barH, 0xFFAAAAAA);
        }

        // 0.27.3：计数与已选提示移至列表下方（搜索框占满列表上方整行）
        String countText = Lang.tr("§7共 ") + displaySongs.size() + Lang.tr(" 首");
        guiGraphics.drawString(font, countText, listX, LIST_Y + listH + 2, 0xAAAAAA);
        // 0.28.0：排序标签（点击循环切换），置于计数行右端
        String sortText = Lang.tr("§7[排序: ") + sortModeName() + "]";
        int sortW = font.width(sortText);
        int sortX = listX + LIST_W - sortW;
        guiGraphics.drawString(font, sortText, sortX, LIST_Y + listH + 2, 0xAAAAAA);
        sortLabelX = sortX;
        sortLabelW = sortW;
        if (selectedSong >= 0 && selectedSong < displaySongs.size()) {
            String sel = Lang.tr("§e已选: §f") + truncate(font,
                    ClientTagStore.getInstance().displayTitle(displaySongs.get(selectedSong)), 90);
            int selX = listX + font.width(countText) + 8;
            int maxSelW = LIST_W - font.width(countText) - 8 - sortW - 6;
            if (font.width(sel) > maxSelW) {
                sel = truncate(font, sel, Math.max(20, maxSelW));
            }
            guiGraphics.drawString(font, sel, selX, LIST_Y + listH + 2, 0xAAAAAA);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 0.25.2：空格键暂停/继续（搜索框聚焦时不拦截）
        if (keyCode == GLFW.GLFW_KEY_SPACE
                && (searchBox == null || !searchBox.isFocused())) {
            onPlayPauseClicked();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int mx = (int) mouseX, my = (int) mouseY;

        // 0.27.2：滚动条点击 → 拖动
        if (displaySongs.size() > LIST_ROWS) {
            int barX = listX + LIST_W - 5;
            if (mx >= barX && mx <= barX + 4 && my >= LIST_Y && my <= LIST_Y + listH) {
                barDragging = true;
                dragMoved = true;
                updateScrollFromBar(my);
                return true;
            }
        }

        // 0.27.2：列表内容区按下 → 记录拖拽状态，点击延迟到 mouseReleased
        if (mx >= listX && mx < listX + LIST_W - 5
                && my >= LIST_Y && my < LIST_Y + listH) {
            listDragging = true;
            dragStartY = my;
            dragStartScroll = scrollOffset;
            dragMoved = false;
            return true;
        }

        // 0.28.0：点击排序标签循环切换排序模式
        if (mx >= sortLabelX - 2 && mx <= sortLabelX + sortLabelW + 2
                && my >= LIST_Y + listH && my <= LIST_Y + listH + 13) {
            MusicModConfig.cycleSortMode();
            scrollOffset = 0;
            updateFilteredSongs();
            return true;
        }

        // 点击空白处取消选择
        selectedSong = -1;
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int my = (int) mouseY;
        // 0.27.2：滚动条拖动
        if (barDragging) {
            updateScrollFromBar(my);
            return true;
        }
        // 0.27.2：列表内容区拖动 → 触屏式滚动，超过阈值取消点击
        if (listDragging) {
            int dy = my - dragStartY;
            int dScroll = -dy / ROW_H;
            int maxScroll = Math.max(0, displaySongs.size() - LIST_ROWS);
            scrollOffset = Math.max(0, Math.min(maxScroll, dragStartScroll + dScroll));
            if (Math.abs(dy) > 3) dragMoved = true;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;
        // 滚动条释放
        if (barDragging && button == 0) {
            barDragging = false;
            return true;
        }
        // 0.27.2：列表内容区释放 → 未拖动时执行延迟点击（选择/双击播放）
        if (listDragging && button == 0) {
            boolean wasDrag = dragMoved;
            listDragging = false;
            dragMoved = false;
            if (!wasDrag) {
                handleListClick(mx, my);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (super.mouseScrolled(mouseX, mouseY, delta)) return true;
        if (mouseX >= listX && mouseX < listX + LIST_W
                && mouseY >= LIST_Y && mouseY < LIST_Y + listH) {
            int maxScroll = Math.max(0, displaySongs.size() - LIST_ROWS);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
            return true;
        }
        return false;
    }

    @Override
    public void removed() {
        // 音量可能被滑块修改，界面关闭时落盘
        MusicModConfig.saveClientConfig();
    }

    // ==================== 内部逻辑 ====================

    /** 0.27.2：根据鼠标 Y 位置更新滚动条滚动 */
    private void updateScrollFromBar(int my) {
        if (displaySongs.size() <= LIST_ROWS) return;
        int barH = Math.max(12, (int) ((float) listH * LIST_ROWS / displaySongs.size()));
        float ratio = Math.max(0f, Math.min(1f,
                (float) (my - LIST_Y - barH / 2) / Math.max(1, listH - barH)));
        int maxScroll = displaySongs.size() - LIST_ROWS;
        scrollOffset = (int) (ratio * maxScroll);
    }

    /** 0.27.2：mouseReleased 中执行延迟的列表点击（仅未拖动时调用） */
    private void handleListClick(int mx, int my) {
        int row = (my - LIST_Y) / ROW_H;
        int idx = scrollOffset + row;
        if (idx >= 0 && idx < displaySongs.size()) {
            long now = System.currentTimeMillis();
            // 双击：当前歌曲则切换暂停/继续，否则播放该曲（仅 OP）
            if (idx == lastClickedRow && now - lastClickTime < 450 && isOp()) {
                String song = displaySongs.get(idx);
                ClientMusicManager mgr = ClientMusicManager.getInstance();
                if (song.equals(mgr.getCurrentSong()) && (mgr.isPlaying() || mgr.isPaused())) {
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_PAUSE, ""));
                } else {
                    NetworkHandler.sendToServer(new C2SMusicActionPacket(
                            C2SMusicActionPacket.ACTION_PLAY, song));
                }
            }
            selectedSong = idx;
            lastClickedRow = idx;
            lastClickTime = now;
        } else {
            // 点击列表内空白行取消选择
            selectedSong = -1;
        }
    }

    private void refreshLocalList() {
        songs = new ArrayList<>(ClientMusicManager.getInstance().getLastSongList());
        updateFilteredSongs();
    }

    /** 0.27.0：根据搜索关键词更新过滤后的显示列表（0.28.0：应用排序） */
    private void updateFilteredSongs() {
        if (searchFilter.isEmpty()) {
            displaySongs = new ArrayList<>(songs);
        } else {
            displaySongs = new ArrayList<>();
            ClientTagStore tags = ClientTagStore.getInstance();
            for (String s : songs) {
                String title = tags.displayTitle(s).toLowerCase();
                String artist = tags.artist(s).toLowerCase();
                if (title.contains(searchFilter) || artist.contains(searchFilter)) {
                    displaySongs.add(s);
                }
            }
        }
        sortDisplaySongs();
        if (selectedSong >= displaySongs.size()) selectedSong = -1;
        int maxScroll = Math.max(0, displaySongs.size() - LIST_ROWS);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    /** 0.28.0：按配置的排序模式排序显示列表（0.28.1：默认 TITLE，始终排序） */
    private void sortDisplaySongs() {
        MusicModConfig.SortMode mode = MusicModConfig.getSortMode();
        if (displaySongs.isEmpty()) return;
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
        displaySongs.sort(cmp);
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

    private void openQueueScreen() {
        Minecraft.getInstance().setScreen(new QueueScreen(this));
    }

    private void requestSongList() {
        NetworkHandler.sendToServer(new C2SRequestListPacket());
        refreshLocalList();
    }

    private void sendAction(byte action) {
        NetworkHandler.sendToServer(new C2SMusicActionPacket(action, ""));
    }

    /**
     * 播放/暂停按钮：
     * - 正在播放 → 暂停
     * - 已暂停   → 继续
     * - 未播放   → 播放选中歌曲
     */
    private void onPlayPauseClicked() {
        ClientMusicManager mgr = ClientMusicManager.getInstance();
        if (mgr.isPlaying() || mgr.isPaused()) {
            sendAction(C2SMusicActionPacket.ACTION_PAUSE);
        } else if (selectedSong >= 0 && selectedSong < displaySongs.size()) {
            NetworkHandler.sendToServer(new C2SMusicActionPacket(
                    C2SMusicActionPacket.ACTION_PLAY, displaySongs.get(selectedSong)));
        }
    }

    private String truncate(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        while (!s.isEmpty() && font.width(s + ellipsis) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ellipsis;
    }
}