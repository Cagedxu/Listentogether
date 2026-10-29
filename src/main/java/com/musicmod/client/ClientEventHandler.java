package com.musicmod.client;

import com.musicmod.i18n.Lang;

import com.musicmod.MusicMod;
import com.musicmod.client.gui.LyricsParser;
import com.musicmod.client.gui.MusicPlayerScreen;
import com.musicmod.config.MusicModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 客户端事件处理器。
 * 负责客户端初始化、HUD渲染、按键打开播放器界面、断开连接时清理资源。
 */
@Mod.EventBusSubscriber(modid = MusicMod.MOD_ID, value = Dist.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onClientPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // 初始化客户端管理器
        ClientMusicManager.getInstance().init();
        MusicMod.LOGGER.info(Lang.tr("一起听 client initialized"));
    }

    @SubscribeEvent
    public static void onClientPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 断开连接时停止播放并清理标签/封面/歌词缓存
        ClientMusicManager.getInstance().getAudioEngine().stop();
        ClientTagStore.getInstance().clear();
        ClientPlaylists.getInstance().clear();
        lyricsCacheSong = "";
        lyricsCacheText = null;
        lyricsCacheLines = null;
        MusicMod.LOGGER.info(Lang.tr("一起听 client stopped playback on disconnect"));
    }

    /**
     * 按键监听：M 键打开播放器界面（按配置风格路由）
     */
    private static boolean fontThemeChecked = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!fontThemeChecked) {
            fontThemeChecked = true;
            if (!MusicModConfig.isFontThemeSynced()) {
                MusicModConfig.setFontMode(MusicModConfig.FontMode.GAME);
                MusicModConfig.setFontThemeSynced(true);
                MusicModConfig.saveClientConfig();
                com.musicmod.client.gui.ModFonts.invalidate();
            }
        }
        if (mc.player == null) return;
        // 单人游戏打开任意 GUI 时集成服务器会暂停，音乐服务端 tick 也停止；
        // 这里在客户端 tick 中补 tick，保证歌曲播完后仍会自动下一首。
        if (mc.isPaused() && mc.getSingleplayerServer() != null) {
            com.musicmod.server.MusicServerManager.getInstance().tick();
        }
        while (ModKeyBindings.OPEN_PLAYER.consumeClick()) {
            if (mc.screen instanceof com.musicmod.client.gui.MusicAppScreen
                    || mc.screen instanceof MusicPlayerScreen) {
                mc.setScreen(null);
            } else if (mc.screen == null) {
                mc.setScreen(MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.CLASSIC
                        ? new MusicPlayerScreen()
                        : new com.musicmod.client.gui.MusicAppScreen());
            }
        }
        // K 键循环切换 HUD 模式：歌词 → 歌名 → 关闭
        while (ModKeyBindings.CYCLE_HUD_MODE.consumeClick()) {
            MusicModConfig.HudMode mode = MusicModConfig.cycleHudMode();
            MusicModConfig.saveClientConfig();
            String modeText = mode == MusicModConfig.HudMode.LYRICS ? Lang.tr("§b歌词")
                    : mode == MusicModConfig.HudMode.SONG_NAME ? Lang.tr("§a歌名")
                    : mode == MusicModConfig.HudMode.CARD ? Lang.tr("§d卡片") : Lang.tr("§c关闭");
            mc.player.displayClientMessage(
                    Component.literal(Lang.tr("§6♪ §eHUD 显示: ") + modeText), true);
        }
        // N 键循环切换播放模式（需 OP，服务端校验）
        while (ModKeyBindings.CYCLE_PLAY_MODE.consumeClick()) {
            com.musicmod.network.packet.C2SMusicActionPacket pkt =
                    new com.musicmod.network.packet.C2SMusicActionPacket(
                            com.musicmod.network.packet.C2SMusicActionPacket.ACTION_PLAY_MODE, "");
            com.musicmod.network.NetworkHandler.sendToServer(pkt);
        }
    }

    // ==================== HUD 歌词缓存 ====================
    private static String lyricsCacheSong = "";
    private static String lyricsCacheText = null;
    private static List<LyricsParser.Line> lyricsCacheLines = null;

    /**
     * 0.29.0：根据配置计算 HUD 显示位置（左上/右上/左下/右下/物品栏上方/顶部中央）。
     * @return [0]=x, [1]=y
     */
    private static int[] hudPos(int width, int height, int screenW, int screenH) {
        MusicModConfig.HudPosition pos = MusicModConfig.getHudPosition();
        int x, y;
        switch (pos) {
            case TOP_RIGHT:    x = screenW - width - 4;   y = 4; break;
            case BOTTOM_LEFT:  x = 4;                     y = screenH - height - 4; break;
            case BOTTOM_RIGHT: x = screenW - width - 4;   y = screenH - height - 4; break;
            case TOP_CENTER:   x = (screenW - width) / 2; y = 4; break;
            default:           x = 4;                     y = 4; break;
        }
        return new int[]{Math.max(0, x), Math.max(0, y)};
    }

    /**
     * HUD 渲染：显示当前播放信息（位置由配置决定，默认左上角）。
     * 模式由配置决定：LYRICS=当前歌词行（无歌词回退歌名），SONG_NAME=歌名，OFF=不显示。
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        ClientMusicManager manager = ClientMusicManager.getInstance();
        if (!manager.isPlaying()) return;

        MusicModConfig.HudMode hudMode = MusicModConfig.getHudMode();
        if (hudMode == MusicModConfig.HudMode.OFF) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        // 0.28.0：卡片模式——封面 + 歌名 + 作者 + 当前歌词行，独立布局
        if (hudMode == MusicModConfig.HudMode.CARD) {
            int maxW = (int) (mc.getWindow().getGuiScaledWidth() * 0.6);
            renderHudCard(event.getGuiGraphics(), mc, manager, maxW);
            return;
        }

        String displayText;
        String transText = null; // 翻译行（仅歌词模式、配置开启且有翻译时）
        boolean isLyricsLine = false;
        if (hudMode == MusicModConfig.HudMode.LYRICS) {
            String[] lyrics = getLyricsLine(manager);
            if (lyrics != null) {
                displayText = lyrics[0];
                transText = lyrics[1];
                isLyricsLine = true;
            } else {
                displayText = getSongNameText(manager);
            }
        } else {
            displayText = getSongNameText(manager);
        }

        GuiGraphics guiGraphics = event.getGuiGraphics();
        // 限制 HUD 宽度不超过屏幕的 60%，超长截断
        int maxWidth = (int) (mc.getWindow().getGuiScaledWidth() * 0.6);
        // 音符图标（仅歌词行模式且配置开启时在左侧独立绘制，原文/译文从同一位置开始保证对齐）
        boolean showNote = isLyricsLine && MusicModConfig.getShowHudNoteIcon();
        int noteW = showNote ? mc.font.width("♪") + 5 : 0;
        String drawn = displayText;
        if (mc.font.width(drawn) > maxWidth) {
            while (!drawn.isEmpty() && mc.font.width(drawn + "…") > maxWidth) {
                drawn = drawn.substring(0, drawn.length() - 1);
            }
            drawn = drawn + "…";
        }
        // 翻译行截断
        String drawnTrans = null;
        if (transText != null && !transText.isEmpty()) {
            drawnTrans = transText;
            if (mc.font.width(drawnTrans) > maxWidth) {
                while (!drawnTrans.isEmpty() && mc.font.width(drawnTrans + "…") > maxWidth) {
                    drawnTrans = drawnTrans.substring(0, drawnTrans.length() - 1);
                }
                drawnTrans = drawnTrans + "…";
            }
        }
        // 0.25.0：翻译位置——AFTER = 原文后方同一行，BELOW = 原文下方
        boolean transAfter = isLyricsLine && drawnTrans != null
                && MusicModConfig.getHudLyricsTransPos() == MusicModConfig.LyricsTransPos.AFTER;
        String combined = null;
        if (transAfter) {
            combined = drawn + " " + drawnTrans;
            if (mc.font.width(combined) > maxWidth) {
                while (!combined.isEmpty() && mc.font.width(combined + "…") > maxWidth) {
                    combined = combined.substring(0, combined.length() - 1);
                }
                combined = combined + "…";
            }
        }
        int width;
        int height;
        if (transAfter) {
            width = mc.font.width(combined) + 8 + noteW;
            height = 14;
        } else {
            width = mc.font.width(drawn) + 8 + noteW;
            if (isLyricsLine) width = Math.max(width, 80); // 歌词框最小宽度（空白时保持可见）
            height = 14;
            if (drawnTrans != null) {
                width = Math.max(width, mc.font.width(drawnTrans) + 8 + noteW);
                height = 26;
            }
        }
        // 0.29.0：根据配置计算 HUD 位置（默认左上角）
        int[] pos = hudPos(width, height,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        int x = pos[0];
        int y = pos[1];
        guiGraphics.fill(x, y, x + width, y + height, 0x80000000);
        if (showNote) {
            // 音符图标始终与第一行（原文行）对齐
            guiGraphics.drawString(mc.font, "§b♪", x + 4, y + 3, 0xFFFFFF);
        }
        int textX = x + 4 + noteW;
        if (transAfter) {
            // 翻译在原文后方同一行
            if (MusicModConfig.getHudLyricsAlign() == MusicModConfig.HudLyricsAlign.CENTER) {
                int centerX = textX + (width - 8 - noteW) / 2;
                guiGraphics.drawCenteredString(mc.font, combined, centerX, y + 3, 0xFFFFFF);
            } else {
                guiGraphics.drawString(mc.font, combined, textX, y + 3, 0xFFFFFF);
            }
        } else if (isLyricsLine && MusicModConfig.getHudLyricsAlign() == MusicModConfig.HudLyricsAlign.CENTER) {
            // 中心对称：原文与译文以文本区中心轴居中
            int centerX = textX + (width - 8 - noteW) / 2;
            guiGraphics.drawCenteredString(mc.font, drawn, centerX, y + 3, 0xFFFFFF);
            if (drawnTrans != null) {
                guiGraphics.drawCenteredString(mc.font, drawnTrans, centerX, y + 15, 0xAAAAAA);
            }
        } else {
            // 从左往右：原文与译文左对齐
            guiGraphics.drawString(mc.font, drawn, textX, y + 3, 0xFFFFFF);
            if (drawnTrans != null) {
                guiGraphics.drawString(mc.font, drawnTrans, textX, y + 15, 0xAAAAAA);
            }
        }
    }

    /**
     * 0.28.0：HUD 卡片模式——显示封面 + 歌名 + 作者 + 当前歌词行（位置由配置决定）。
     * 0.28.3：删除歌名前播放符号；封面与卡片同高完全盖住背景；歌词与歌名拉开距离。
     * 0.29.0：卡片整体缩小（封面 48→40）；支持六种显示位置。
     */
    private static void renderHudCard(GuiGraphics g, Minecraft mc, ClientMusicManager manager, int maxWidth) {
        Font font = mc.font;
        String song = manager.getCurrentSong();
        ClientTagStore tags = ClientTagStore.getInstance();
        // 请求详情（封面+歌词），自带去重
        tags.requestSongInfo(song);

        // 0.29.0：卡片整体缩小，封面 48→40，与卡片同高，完全盖住背景
        int coverSize = 40;
        ResourceLocation cover = CoverTextureManager.get(song);
        int[] srcSize = CoverTextureManager.getSize(song);

        // 0.28.3：歌名 - 歌手 同一行，删除播放符号
        String title = tags.displayTitle(song);
        String artist = tags.artist(song);
        String titleLine = "§f" + title;
        if (!artist.isEmpty()) {
            titleLine += " §7- " + artist;
        }

        // 歌词行（含翻译）
        String[] lyrics = getLyricsLine(manager);
        String lyricsText = null;
        String transText = null;
        if (lyrics != null) {
            lyricsText = lyrics[0];
            transText = lyrics[1];
        }

        int maxTextW = Math.max(20, maxWidth - coverSize - 16);
        String drawnTitleLine = truncateHud(font, titleLine, maxTextW);
        String drawnLyrics = (lyricsText != null && !lyricsText.trim().isEmpty())
                ? truncateHud(font, lyricsText, maxTextW) : null;
        String drawnTrans = (transText != null && !transText.isEmpty())
                ? truncateHud(font, transText, maxTextW) : null;

        // 卡片尺寸：高度=封面高度，封面完全盖住背景
        int contentW = coverSize + 8 + font.width(drawnTitleLine);
        if (drawnLyrics != null) contentW = Math.max(contentW, coverSize + 8 + font.width(drawnLyrics));
        if (drawnTrans != null) contentW = Math.max(contentW, coverSize + 8 + font.width(drawnTrans));
        int width = contentW + 4;
        int height = coverSize;

        // 0.29.0：根据配置计算卡片位置（默认左上角）
        int[] pos = hudPos(width, height,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        int x = pos[0], y = pos[1];
        int textX = x + coverSize + 8;

        // 背景
        g.fill(x, y, x + width, y + height, 0x80000000);

        // 封面（与卡片同高，完全盖住左侧背景）
        if (cover != null && srcSize != null && srcSize.length >= 2
                && srcSize[0] > 0 && srcSize[1] > 0) {
            drawCoverScaledHud(g, cover, srcSize, x, y, coverSize);
        } else {
            g.fill(x, y, x + coverSize, y + coverSize, 0x802A2A3A);
            g.drawCenteredString(font, "♪", x + coverSize / 2 - 2, y + 16, 0x666666);
        }

        // 0.29.0：歌名 - 歌手 同一行；无歌词时保持原位（y + 4），不因缺少歌词而下调
        int titleY = y + 4;
        g.drawString(font, drawnTitleLine, textX, titleY, 0xFFFFFF);
        if (drawnLyrics != null) {
            g.drawString(font, drawnLyrics, textX, y + 20, 0xFFFFFF);
            if (drawnTrans != null) {
                g.drawString(font, drawnTrans, textX, y + 31, 0xAAAAAA);
            }
        }
    }

    /** HUD 文本截断（超宽加省略号） */
    private static String truncateHud(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (!s.isEmpty() && font.width(s + "…") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** 按目标尺寸等比缩放绘制封面（居中裁剪），与 MusicAppScreen.drawCoverScaled 逻辑一致 */
    private static void drawCoverScaledHud(GuiGraphics g, ResourceLocation cover, int[] srcSize,
                                            int dx, int dy, int dSize) {
        int srcW = srcSize[0], srcH = srcSize[1];
        float scale = (float) dSize / Math.min(srcW, srcH);
        int drawW = (int) (srcW * scale);
        int drawH = (int) (srcH * scale);
        int offsetX = dx - (drawW - dSize) / 2;
        int offsetY = dy - (drawH - dSize) / 2;
        g.blit(cover, offsetX, offsetY, 0, 0, dSize, dSize, drawW, drawH);
    }

    /** 歌名显示文本（含播放/暂停状态图标） */
    private static String getSongNameText(ClientMusicManager manager) {
        String song = manager.getCurrentSong();
        String status = manager.isPaused() ? "⏸" : "▶";
        String title = ClientTagStore.getInstance().displayTitle(song);
        return "§6♪ " + status + " §f" + title;
    }

    /**
     * 获取当前应显示的歌词行（含翻译）。
     * 按需请求单曲详情（含歌词），解析后根据播放进度高亮当前行。
     * @return [0]=原文行文本，[1]=翻译行文本（可为 null）；无歌词/未到首句时返回 null（回退歌名）
     */
    private static String[] getLyricsLine(ClientMusicManager manager) {
        String song = manager.getCurrentSong();
        if (song.isEmpty()) return null;
        ClientTagStore tags = ClientTagStore.getInstance();
        // 按需请求详情（自带去重，不会重复发包）
        tags.requestSongInfo(song);
        ClientTagStore.SongInfo info = tags.getSongInfo(song);
        if (info == null || info.lyrics == null || info.lyrics.isEmpty()) return null;
        // 缓存解析结果，歌曲或歌词变化时重新解析
        if (!song.equals(lyricsCacheSong) || !info.lyrics.equals(lyricsCacheText)) {
            lyricsCacheSong = song;
            lyricsCacheText = info.lyrics;
            lyricsCacheLines = LyricsParser.parse(info.lyrics);
        }
        if (lyricsCacheLines == null || lyricsCacheLines.isEmpty()) return null;
        long pos = manager.getAudioEngine().getCurrentPositionMs();
        int active = LyricsParser.findActiveLine(lyricsCacheLines, pos);
        if (active >= 0) {
            // 当前行为空（间奏标记）时，向前找最近的非空行继续显示
            int showLine = active;
            while (showLine >= 0 && lyricsCacheLines.get(showLine).text.isEmpty()) {
                showLine--;
            }
            if (showLine >= 0) {
                LyricsParser.Line line = lyricsCacheLines.get(showLine);
                String trans = null;
                if (MusicModConfig.getShowTranslation()
                        && line.translation != null && !line.translation.isEmpty()) {
                    trans = "§7" + line.translation;
                }
                return new String[]{"§f" + line.text, trans};
            }
        }
        // 未到首句或间奏无上一句可显示 → 显示空白，保持歌词框不回退歌名
        return new String[]{" ", null};
    }
}