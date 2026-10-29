package com.musicmod.client.gui;

import com.musicmod.config.MusicModConfig;
import com.musicmod.i18n.Lang;

/** 播放器界面主题颜色映射：深色保持原始配色，浅色使用中性的浅色版本。 */
public final class Theme {

    private Theme() {}

    public static boolean isLight() {
        // 浅色主题只属于播放器界面；精简模式始终保持深色。
        return MusicModConfig.getUiTheme() == MusicModConfig.UiTheme.LIGHT
                && MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.PLAYER;
    }

    /** 翻译并应用当前主题的文字颜色码。 */
    public static String tr(String key) {
        return t(Lang.tr(key));
    }

    /** 浅色模式下把白色/浅灰文字码替换成深色文字码。 */
    public static String t(String text) {
        if (text == null) return null;
        if (!isLight()) return text;
        return text.replace("§f", "§0").replace("§e", "§6").replace("§7", "§8");
    }

    /** 列表/面板内容底色：浅色模式下使用白色纸面，与暖色窗口底色区分。 */
    public static int listBg() {
        return isLight() ? 0xFFF8F3E8 : 0xFF2A2A3A;
    }

    public static int rowPrimary() {
        return isLight() ? 0xFF20202A : 0xFFF0F0F0;
    }

    public static int rowSecondary() {
        return isLight() ? 0xFF555560 : 0xFFBBBBBB;
    }

    public static int rowMuted() {
        return isLight() ? 0xFF666670 : 0xFF999999;
    }

    /** 将深色 UI 颜色转换成当前主题颜色。 */
    public static int c(int dark) {
        boolean noAlpha = (dark & 0xFF000000) == 0;
        if (!isLight()) {
            return noAlpha ? (dark | 0xFF000000) : dark;
        }
        int result;
        switch (dark) {
            // 窗口中 / 面板：暖纸色 + 陶土橙 + 橄榄绿
            case 0x90000010: return 0xFF8F826F;
            case 0xFF4A4A60: return 0xFFCBB89D;
            case 0xFF3A3A50: return 0xFFD8C6AC;
            case 0xFF2A2A3A: return 0xFFE7DAC6;
            case 0xFF1E1E2E: return 0xFFFFFBF3;
            case 0xFF15151F: return 0xFFF4EBDD;
            case 0xFF202030: return 0xFFF0E5D4;
            case 0xFF333344: return 0xFFD9C8AF;
            case 0xE01E1E2E: return 0xEAFBF6EC;
            case 0xC0161626: return 0xD8F0E6D5;
            case 0x80555577: return 0x50C2703D;
            case 0x60333350: return 0x40C2703D;
            case 0x50226633: return 0x403F6B4A;
            case 0x30226633: return 0x303F6B4A;
            case 0xA0000000: return 0x40261D15;
            case 0x80000000: return 0x30261D15;
            case 0x60000000: return 0x20261D15;
            case 0x40000000: return 0x20C9B79C;
            case 0x60333333: return 0x40C9B79C;
            // 文字
            case 0xFFFFFF: return 0xFF2B2722;
            case 0xF0F0F0: return 0xFF2B2722;
            case 0xAAAAAA: return 0xFF6B6259;
            case 0xFFAAAAAA: return 0xFF6B6259;
            case 0x888888: return 0xFF8A8175;
            case 0xCCCCCC: return 0xFF4A443C;
            case 0xBBBBBB: return 0xFF5A5348;
            case 0x777777: return 0xFF8A8175;
            case 0x999999: return 0xFF6B6259;
            case 0x666666: return 0xFF6B6259;
            case 0x888899: return 0xFF6B6259;
            case 0xFF555555: return 0xFFC9B79C;
            case 0x555555: return 0xFFC9B79C;
            // 强调色
            case 0xFFE0A040: return 0xFFB4551E;
            case 0xFFFF55: return 0xFF9C5A1E;
            case 0xFFD700: return 0xFF9C5A1E;
            case 0xFF55FF55: return 0xFF4E6B45;
            case 0x55FF55: return 0xFF4E6B45;
            case 0xFF5555: return 0xFFA23B32;
            case 0xCC6666: return 0xFFA23B32;
            case 0xFF88CCFF: return 0xFF3F6A8A;
            case 0x55FFFF: return 0xFF2A6F6A;
            case 0xFF6A6A8A: return 0xFFC9B79C;
            default: result = dark;
        }
        if ((result & 0xFF000000) == 0) result |= 0xFF000000;
        return result;
    }
}
