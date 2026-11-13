package com.musicmod.client;

import com.musicmod.api.RomajiSupport;
import com.musicmod.config.MusicModConfig;

/** 统一处理“歌词显示”三种模式，避免各处重复判断。 */
public final class LyricsDisplayHelper {
    private LyricsDisplayHelper() {
    }

    /**
     * 返回当前模式下一行歌词应显示的副行文本。
     * 翻译模式显示翻译；仅原文返回 null；罗马音模式对日语显示罗马音，
     * 其他语言回退到翻译。
     */
    public static String secondary(String original, String translation) {
        MusicModConfig.LyricsDisplay mode = MusicModConfig.getLyricsDisplay();
        if (mode == MusicModConfig.LyricsDisplay.ORIGINAL) return null;
        if (mode == MusicModConfig.LyricsDisplay.ROMAJI
                && original != null && RomajiSupport.canHandle(original)) {
            String romaji = RomajiSupport.romanize(original);
            if (romaji != null && !romaji.isEmpty()) return romaji;
        }
        return translation == null || translation.isEmpty() ? null : translation;
    }

    public static boolean showsSecondary() {
        return MusicModConfig.getLyricsDisplay() != MusicModConfig.LyricsDisplay.ORIGINAL;
    }

    public static String label() {
        MusicModConfig.LyricsDisplay mode = MusicModConfig.getLyricsDisplay();
        if (mode == MusicModConfig.LyricsDisplay.ORIGINAL) return "仅原文";
        if (mode == MusicModConfig.LyricsDisplay.ROMAJI) return "显示罗马音";
        return "显示翻译";
    }

    public static String shortLabel() {
        MusicModConfig.LyricsDisplay mode = MusicModConfig.getLyricsDisplay();
        if (mode == MusicModConfig.LyricsDisplay.ORIGINAL) return "原文";
        if (mode == MusicModConfig.LyricsDisplay.ROMAJI) return "罗马音";
        return "翻译";
    }

    public static void cycle() {
        MusicModConfig.LyricsDisplay cur = MusicModConfig.getLyricsDisplay();
        if (cur == MusicModConfig.LyricsDisplay.TRANSLATION) {
            MusicModConfig.setLyricsDisplay(MusicModConfig.LyricsDisplay.ORIGINAL);
        } else if (cur == MusicModConfig.LyricsDisplay.ORIGINAL) {
            MusicModConfig.setLyricsDisplay(RomajiSupport.available()
                    ? MusicModConfig.LyricsDisplay.ROMAJI
                    : MusicModConfig.LyricsDisplay.TRANSLATION);
        } else {
            MusicModConfig.setLyricsDisplay(MusicModConfig.LyricsDisplay.TRANSLATION);
        }
        MusicModConfig.saveClientConfig();
    }
}
