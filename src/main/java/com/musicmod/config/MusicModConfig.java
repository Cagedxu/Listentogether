package com.musicmod.config;

import com.musicmod.MusicMod;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 一起听 Forge 配置。
 * <p>
 * CLIENT（客户端配置，存储在每个玩家本机）：
 *   - volume        个人音量 0.0~1.0（默认 0.4）
 *   - hudMode       左上角 HUD 模式：CARD（默认）/SONG_NAME/LYRICS/OFF
 * <p>
 * SERVER（服务器配置，存储在服务器端，单人游戏即本地）：
 *   - songsDir        歌曲文件夹路径（相对服务器根目录）
 *   - defaultPlayMode 默认播放模式（顺序/单曲循环/随机）
 */
public class MusicModConfig {

    // ==================== 客户端配置 ====================
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    public static class Client {
        public final ForgeConfigSpec.DoubleValue volume;
        public final ForgeConfigSpec.EnumValue<HudMode> hudMode;
        public final ForgeConfigSpec.EnumValue<UiStyle> uiStyle;
        public final ForgeConfigSpec.EnumValue<UiTheme> uiTheme;
        public final ForgeConfigSpec.EnumValue<FontMode> fontMode;
        public final ForgeConfigSpec.ConfigValue<String> customFontPath;
        public final ForgeConfigSpec.BooleanValue fontThemeSynced;
        public final ForgeConfigSpec.BooleanValue showTranslation;
        public final ForgeConfigSpec.BooleanValue showHudNoteIcon;
        public final ForgeConfigSpec.BooleanValue queueShowPlayed;
        public final ForgeConfigSpec.BooleanValue pauseSingleplayer;
        public final ForgeConfigSpec.BooleanValue transferLimitEnabled;
        public final ForgeConfigSpec.EnumValue<HudLyricsAlign> hudLyricsAlign;
        public final ForgeConfigSpec.EnumValue<LyricsTransPos> hudLyricsTransPos;
        public final ForgeConfigSpec.EnumValue<LyricsTransPos> detailLyricsTransPos;
        public final ForgeConfigSpec.EnumValue<LyricsTransPos> barLyricsTransPos;
        public final ForgeConfigSpec.EnumValue<LyricsClickMode> lyricsClickMode;
        public final ForgeConfigSpec.BooleanValue detailDblClickPlay;
        public final ForgeConfigSpec.EnumValue<SortMode> sortMode;
        public final ForgeConfigSpec.EnumValue<HudPosition> hudPosition;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("一起听 客户端配置（仅对本机玩家生效）").push("client");
            volume = builder
                    .comment("个人音量，范围 0.0 ~ 1.0")
                    .defineInRange("volume", 0.4D, 0.0D, 1.0D);
            hudMode = builder
                    .comment("左上角 HUD 显示模式：CARD = 卡片（默认，封面+歌名+作者+歌词），LYRICS = 歌词，SONG_NAME = 歌曲名称，OFF = 不显示")
                    .defineEnum("hudMode", HudMode.CARD);
            uiStyle = builder
                    .comment("播放器界面风格：PLAYER = 播放器风格（默认，左侧导航+歌手/专辑/歌词/封面），CLASSIC = 精简模式（紧凑列表）")
                    .defineEnum("uiStyle", UiStyle.PLAYER);
            uiTheme = builder
                    .comment("播放器界面主题：DARK = 深色（默认），LIGHT = 浅色")
                    .defineEnum("uiTheme", UiTheme.DARK);
            fontMode = builder
                    .comment("模组界面字体：MOD = 内置 Noto Sans SC（默认），GAME = 游戏字体，CUSTOM = 自定义 TTF/OTF")
                    .defineEnum("fontMode", FontMode.GAME);
            customFontPath = builder
                    .comment("自定义字体路径（fontMode=CUSTOM 时使用，留空则回退内置字体）")
                    .define("customFontPath", "");
            fontThemeSynced = builder
                    .comment("内部标记：主题与默认字体是否已完成首次同步")
                    .define("fontThemeSynced", false);
            showTranslation = builder
                    .comment("歌词详情中是否显示翻译歌词（双语 LRC 中相同时间戳的第二行）")
                    .define("showTranslation", true);
            showHudNoteIcon = builder
                    .comment("HUD 歌词左侧是否显示音符图标")
                    .define("showHudNoteIcon", true);
            queueShowPlayed = builder
                    .comment("播放队列是否显示已经播放过的歌曲（默认显示）")
                    .define("queueShowPlayed", true);
            pauseSingleplayer = builder
                    .comment("单人游戏打开播放器界面时是否暂停世界（默认 false 不暂停）")
                    .define("pauseSingleplayer", false);
            transferLimitEnabled = builder
                    .comment("上传/下载音乐时是否启用 2 MB/s 限速（默认 true）")
                    .define("transferLimitEnabled", true);
            hudLyricsAlign = builder
                    .comment("HUD 歌词对齐方式：LEFT = 从左往右（原文译文左对齐），CENTER = 中心对称（原文译文居中对齐）")
                    .defineEnum("hudLyricsAlign", HudLyricsAlign.CENTER);
            hudLyricsTransPos = builder
                    .comment("HUD 歌词翻译位置：BELOW = 原文下方（默认），AFTER = 原文后方同一行")
                    .defineEnum("hudLyricsTransPos", LyricsTransPos.BELOW);
            detailLyricsTransPos = builder
                    .comment("歌曲详情歌词翻译位置：BELOW = 原文下方（默认），AFTER = 原文后方同一行")
                    .defineEnum("detailLyricsTransPos", LyricsTransPos.BELOW);
            barLyricsTransPos = builder
                    .comment("进度条上方歌词翻译位置：BELOW = 进度条下方，AFTER = 原文后方同一行")
                    .defineEnum("barLyricsTransPos", LyricsTransPos.BELOW);
            lyricsClickMode = builder
                    .comment("歌词点击跳转模式：SINGLE = 单击直接跳转（默认），DOUBLE = 双击跳转（第一次点击显示选取框，再点同一行才跳转）")
                    .defineEnum("lyricsClickMode", LyricsClickMode.SINGLE);
            detailDblClickPlay = builder
                    .comment("查看非播放歌曲详情时，双击歌词是否跳转播放该歌曲对应进度（默认 false 不跳转）")
                    .define("detailDblClickPlay", false);
            sortMode = builder
                    .comment("歌曲列表排序方式：TITLE = 标题（默认），ARTIST = 歌手，ALBUM = 专辑，DURATION = 时长")
                    .defineEnum("sortMode", SortMode.TITLE);
            hudPosition = builder
                    .comment("HUD 显示位置：TOP_LEFT = 左上角（默认），TOP_RIGHT = 右上角，BOTTOM_LEFT = 左下角，BOTTOM_RIGHT = 右下角，TOP_CENTER = 顶部中央")
                    .defineEnum("hudPosition", HudPosition.TOP_LEFT);
            builder.pop();
        }
    }

    /** 界面风格枚举 */
    public enum UiStyle {
        /** 播放器风格：左侧功能导航 + 歌手/专辑/歌单/歌词/封面 */
        PLAYER,
        /** 精简模式：紧凑歌曲列表 */
        CLASSIC
    }

    /** 播放器界面主题 */
    public enum UiTheme {
        DARK,
        LIGHT
    }

    /** 模组界面字体来源 */
    public enum FontMode {
        MOD,
        GAME,
        CUSTOM
    }

    /** HUD 显示位置枚举（0.29.0 新增） */
    public enum HudPosition {
        /** 左上角（默认） */
        TOP_LEFT,
        /** 右上角 */
        TOP_RIGHT,
        /** 左下角 */
        BOTTOM_LEFT,
        /** 右下角 */
        BOTTOM_RIGHT,
        /** 顶部中央 */
        TOP_CENTER
    }

    /** HUD 显示模式枚举 */
    public enum HudMode {
        /** 显示当前歌词行（无歌词时回退显示歌名） */
        LYRICS,
        /** 显示歌曲名称 */
        SONG_NAME,
        /** 卡片模式：封面 + 歌名 + 作者 + 当前歌词行（0.28.0 新增） */
        CARD,
        /** 不显示 HUD */
        OFF
    }

    /** 歌曲列表排序模式枚举（0.28.0 新增；0.28.1：删除 DEFAULT，默认 TITLE） */
    public enum SortMode {
        /** 按标题字母序（默认） */
        TITLE,
        /** 按歌手字母序（歌手相同按标题） */
        ARTIST,
        /** 按专辑字母序（专辑相同按标题） */
        ALBUM,
        /** 按时长升序（未知时长排最后） */
        DURATION
    }

    /** HUD 歌词对齐方式枚举 */
    public enum HudLyricsAlign {
        /** 从左往右：原文与译文左对齐 */
        LEFT,
        /** 中心对称：原文与译文居中对齐 */
        CENTER
    }

    /** 歌词翻译位置枚举（0.25.0 新增） */
    public enum LyricsTransPos {
        /** 翻译显示在原文下方 */
        BELOW,
        /** 翻译显示在原文后方（同一行） */
        AFTER
    }

    /** 歌词点击跳转模式枚举（0.27.0 新增） */
    public enum LyricsClickMode {
        /** 单击歌词行直接跳转播放位置 */
        SINGLE,
        /** 双击歌词行跳转：第一次点击显示选取框，再次点击同一行才跳转 */
        DOUBLE
    }

    // ==================== 服务器配置 ====================
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    public static class Server {
        public final ForgeConfigSpec.ConfigValue<String> songsDir;
        public final ForgeConfigSpec.EnumValue<com.musicmod.PlayMode> defaultPlayMode;
        public final ForgeConfigSpec.EnumValue<com.musicmod.EmptyServerBehavior> emptyServerBehavior;
        public final ForgeConfigSpec.BooleanValue transferLimitEnabled;

        Server(ForgeConfigSpec.Builder builder) {
            builder.comment("一起听 服务器配置（修改歌曲目录后需重新扫描生效）").push("server");
            songsDir = builder
                    .comment("歌曲文件夹路径，相对于服务器根目录")
                    .define("songsDir", "config/musicmod/songs");
            defaultPlayMode = builder
                    .comment("默认播放模式: SEQUENTIAL(顺序播放) | LOOP_ONE(单曲循环) | SHUFFLE(随机播放)")
                    .defineEnum("defaultPlayMode", com.musicmod.PlayMode.SEQUENTIAL);
            emptyServerBehavior = builder
                    .comment("服务器无人时的音乐行为: CONTINUE(继续播放，默认) | STOP(停止播放) | PAUSE(暂停直到有人加入)")
                    .defineEnum("emptyServerBehavior", com.musicmod.EmptyServerBehavior.CONTINUE);
            transferLimitEnabled = builder
                    .comment("向客户端发送歌曲时是否启用 2 MB/s 限速（默认 true）")
                    .define("transferLimitEnabled", true);
            builder.pop();
        }
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CLIENT = new Client(builder);
        CLIENT_SPEC = builder.build();

        builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    // ==================== 安全读写工具方法 ====================
    // 配置在 FML 加载完成后才会 attach config 对象，未加载时 get/set/save 会抛异常，
    // 这里统一做 isLoaded 守卫与异常兜底，返回默认值。

    public static float getClientVolume() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.volume.get().floatValue();
        } catch (Exception ignored) {}
        return 0.4f;
    }

    /** 获取 HUD 显示模式（默认 CARD） */
    public static HudMode getHudMode() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.hudMode.get();
        } catch (Exception ignored) {}
        return HudMode.CARD;
    }

    /** 设置 HUD 显示模式（仅写入内存，不立即落盘） */
    public static void setHudMode(HudMode mode) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.hudMode.set(mode);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set hudMode config", e);
        }
    }

    /** 循环切换 HUD 模式：LYRICS → SONG_NAME → CARD → OFF → LYRICS */
    public static HudMode cycleHudMode() {
        HudMode[] modes = HudMode.values();
        HudMode next = modes[(getHudMode().ordinal() + 1) % modes.length];
        setHudMode(next);
        return next;
    }

    /** 获取 HUD 显示位置（默认 TOP_LEFT） */
    public static HudPosition getHudPosition() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.hudPosition.get();
        } catch (Exception ignored) {}
        return HudPosition.TOP_LEFT;
    }

    /** 设置 HUD 显示位置（仅写入内存，不立即落盘） */
    public static void setHudPosition(HudPosition value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.hudPosition.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set hudPosition config", e);
        }
    }

    /** 循环切换 HUD 位置：左上 → 右上 → 左下 → 右下 → 物品栏上方 → 顶部中央 → 左上 */
    public static HudPosition cycleHudPosition() {
        HudPosition[] values = HudPosition.values();
        HudPosition next = values[(getHudPosition().ordinal() + 1) % values.length];
        setHudPosition(next);
        return next;
    }

    /** 获取歌曲列表排序模式（默认 TITLE） */
    public static SortMode getSortMode() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.sortMode.get();
        } catch (Exception ignored) {}
        return SortMode.TITLE;
    }

    /** 设置歌曲列表排序模式（仅写入内存，不立即落盘） */
    public static void setSortMode(SortMode mode) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.sortMode.set(mode);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set sortMode config", e);
        }
    }

    /** 循环切换排序模式：TITLE → ARTIST → ALBUM → DURATION → TITLE */
    public static SortMode cycleSortMode() {
        SortMode[] modes = SortMode.values();
        SortMode next = modes[(getSortMode().ordinal() + 1) % modes.length];
        setSortMode(next);
        return next;
    }

    /** 设置个人音量（仅写入内存，不立即落盘） */
    public static void setClientVolume(float vol) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.volume.set((double) vol);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set client volume config", e);
        }
    }

    /** 获取界面风格（默认 PLAYER） */
    public static UiStyle getUiStyle() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.uiStyle.get();
        } catch (Exception ignored) {}
        return UiStyle.PLAYER;
    }

    /** 设置界面风格 */
    public static void setUiStyle(UiStyle style) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.uiStyle.set(style);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set uiStyle config", e);
        }
    }

    /** 模组界面字体来源 */
    public static FontMode getFontMode() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.fontMode.get();
        } catch (Exception ignored) {}
        return FontMode.GAME;
    }

    public static void setFontMode(FontMode mode) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.fontMode.set(mode);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set fontMode config", e);
        }
    }

    public static String getCustomFontPath() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.customFontPath.get();
        } catch (Exception ignored) {}
        return "";
    }

    public static void setCustomFontPath(String path) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.customFontPath.set(path == null ? "" : path);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set customFontPath config", e);
        }
    }

    public static boolean isFontThemeSynced() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.fontThemeSynced.get();
        } catch (Exception ignored) {}
        return true;
    }

    public static void setFontThemeSynced(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.fontThemeSynced.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set fontThemeSynced config", e);
        }
    }

    /** 播放器界面主题（默认 DARK） */
    public static UiTheme getUiTheme() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.uiTheme.get();
        } catch (Exception ignored) {}
        return UiTheme.DARK;
    }

    /** 设置播放器界面主题 */
    public static void setUiTheme(UiTheme theme) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.uiTheme.set(theme);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set uiTheme config", e);
        }
    }

    /** 是否显示翻译歌词（默认 true） */
    public static boolean getShowTranslation() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.showTranslation.get();
        } catch (Exception ignored) {}
        return true;
    }

    /** 设置是否显示翻译歌词 */
    public static void setShowTranslation(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.showTranslation.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set showTranslation config", e);
        }
    }

    /** HUD 歌词左侧是否显示音符图标（默认 true） */
    public static boolean getShowHudNoteIcon() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.showHudNoteIcon.get();
        } catch (Exception ignored) {}
        return true;
    }

    /** 设置 HUD 音符图标显示 */
    public static void setShowHudNoteIcon(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.showHudNoteIcon.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set showHudNoteIcon config", e);
        }
    }

    /** 播放队列是否显示已播放歌曲（默认 true） */
    public static boolean getQueueShowPlayed() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.queueShowPlayed.get();
        } catch (Exception ignored) {}
        return true;
    }

    /** 设置播放队列是否显示已播放歌曲 */
    public static void setQueueShowPlayed(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.queueShowPlayed.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set queueShowPlayed config", e);
        }
    }

    public static boolean isPauseSingleplayer() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.pauseSingleplayer.get();
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to read pauseSingleplayer config", e);
        }
        return false;
    }

    public static void setPauseSingleplayer(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.pauseSingleplayer.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set pauseSingleplayer config", e);
        }
    }

    public static boolean isTransferLimitEnabled() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.transferLimitEnabled.get();
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to read transferLimitEnabled config", e);
        }
        return true;
    }

    public static void setTransferLimitEnabled(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.transferLimitEnabled.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set transferLimitEnabled config", e);
        }
    }

    /** 获取 HUD 歌词对齐方式（默认 CENTER） */
    public static HudLyricsAlign getHudLyricsAlign() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.hudLyricsAlign.get();
        } catch (Exception ignored) {}
        return HudLyricsAlign.CENTER;
    }

    /** 设置 HUD 歌词对齐方式 */
    public static void setHudLyricsAlign(HudLyricsAlign value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.hudLyricsAlign.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set hudLyricsAlign config", e);
        }
    }

    /** 获取 HUD 歌词翻译位置（默认 BELOW） */
    public static LyricsTransPos getHudLyricsTransPos() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.hudLyricsTransPos.get();
        } catch (Exception ignored) {}
        return LyricsTransPos.BELOW;
    }

    /** 设置 HUD 歌词翻译位置 */
    public static void setHudLyricsTransPos(LyricsTransPos value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.hudLyricsTransPos.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set hudLyricsTransPos config", e);
        }
    }

    /** 获取详情面板歌词翻译位置（默认 BELOW） */
    public static LyricsTransPos getDetailLyricsTransPos() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.detailLyricsTransPos.get();
        } catch (Exception ignored) {}
        return LyricsTransPos.BELOW;
    }

    /** 设置详情面板歌词翻译位置 */
    public static void setDetailLyricsTransPos(LyricsTransPos value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.detailLyricsTransPos.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set detailLyricsTransPos config", e);
        }
    }

    /** 获取进度条歌词翻译位置（默认 BELOW） */
    public static LyricsTransPos getBarLyricsTransPos() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.barLyricsTransPos.get();
        } catch (Exception ignored) {}
        return LyricsTransPos.BELOW;
    }

    /** 设置进度条歌词翻译位置 */
    public static void setBarLyricsTransPos(LyricsTransPos value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.barLyricsTransPos.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set barLyricsTransPos config", e);
        }
    }

    /** 获取歌词点击跳转模式（默认 SINGLE） */
    public static LyricsClickMode getLyricsClickMode() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.lyricsClickMode.get();
        } catch (Exception ignored) {}
        return LyricsClickMode.SINGLE;
    }

    /** 设置歌词点击跳转模式 */
    public static void setLyricsClickMode(LyricsClickMode value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.lyricsClickMode.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set lyricsClickMode config", e);
        }
    }

    /** 查看非播放歌曲详情时双击歌词是否跳转播放（默认 false） */
    public static boolean getDetailDblClickPlay() {
        try {
            if (CLIENT_SPEC.isLoaded()) return CLIENT.detailDblClickPlay.get();
        } catch (Exception ignored) {}
        return false;
    }

    /** 设置查看非播放歌曲详情时双击歌词是否跳转播放 */
    public static void setDetailDblClickPlay(boolean value) {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT.detailDblClickPlay.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set detailDblClickPlay config", e);
        }
    }

    public static void saveClientConfig() {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT_SPEC.save();
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to save client config", e);
        }
    }

    public static String getServerSongsDir() {
        try {
            if (SERVER_SPEC.isLoaded()) return SERVER.songsDir.get();
        } catch (Exception ignored) {}
        return "config/musicmod/songs";
    }

    public static boolean isServerTransferLimitEnabled() {
        try {
            if (SERVER_SPEC.isLoaded()) return SERVER.transferLimitEnabled.get();
        } catch (Exception ignored) {}
        return true;
    }

    public static void setServerTransferLimitEnabled(boolean value) {
        try {
            if (SERVER_SPEC.isLoaded()) SERVER.transferLimitEnabled.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set server transferLimitEnabled config", e);
        }
    }

    public static com.musicmod.PlayMode getServerDefaultPlayMode() {
        try {
            if (SERVER_SPEC.isLoaded()) return SERVER.defaultPlayMode.get();
        } catch (Exception ignored) {}
        return com.musicmod.PlayMode.SEQUENTIAL;
    }

    /** 获取服务器无人时的音乐行为（默认 CONTINUE） */
    public static com.musicmod.EmptyServerBehavior getServerEmptyServerBehavior() {
        try {
            if (SERVER_SPEC.isLoaded()) return SERVER.emptyServerBehavior.get();
        } catch (Exception ignored) {}
        return com.musicmod.EmptyServerBehavior.CONTINUE;
    }

    /** 设置服务器无人时的音乐行为（仅写入内存，不立即落盘） */
    public static void setServerEmptyServerBehavior(com.musicmod.EmptyServerBehavior value) {
        try {
            if (SERVER_SPEC.isLoaded()) SERVER.emptyServerBehavior.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set emptyServerBehavior config", e);
        }
    }

    public static void setServerSongsDir(String dir) {
        try {
            if (SERVER_SPEC.isLoaded()) SERVER.songsDir.set(dir);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set songsDir config", e);
        }
    }

    public static void setServerDefaultPlayMode(com.musicmod.PlayMode value) {
        try {
            if (SERVER_SPEC.isLoaded()) SERVER.defaultPlayMode.set(value);
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to set defaultPlayMode config", e);
        }
    }

    public static void saveServerConfig() {
        try {
            if (SERVER_SPEC.isLoaded()) SERVER_SPEC.save();
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to save server config", e);
        }
    }
}