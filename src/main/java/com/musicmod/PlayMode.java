package com.musicmod;

import com.musicmod.i18n.Lang;

/**
 * 播放模式枚举（1.7.0 新增，0.26.0 移除列表循环）。
 * <ul>
 *   <li>{@link #SEQUENTIAL} - 顺序播放：播完当前歌曲后自动播放下一首，列表末尾后回到第一首</li>
 *   <li>{@link #LOOP_ONE}  - 单曲循环：播完后重新播放当前歌曲</li>
 *   <li>{@link #SHUFFLE}   - 随机播放：随机选择下一首（避免连续重复）</li>
 * </ul>
 */
public enum PlayMode {
    SEQUENTIAL("顺序播放", "▶", 0x55FF55),
    LOOP_ONE("单曲循环", "↺", 0xFFAA00),
    SHUFFLE("随机播放", "⇄", 0x55FFFF);

    private final String displayName;
    private final String symbol;
    private final int color;

    PlayMode(String displayName, String symbol, int color) {
        this.displayName = displayName;
        this.symbol = symbol;
        this.color = color;
    }

    public String getDisplayName() { return Lang.tr(displayName); }
    public String getSymbol() { return symbol; }
    public int getColor() { return color; }

    /** 循环切换到下一个播放模式 */
    public PlayMode next() {
        PlayMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    /** 按名称解析播放模式，无效名称（含已移除的 LOOP_LIST）回退到 SEQUENTIAL */
    public static PlayMode fromName(String name) {
        if (name == null || name.isEmpty()) return SEQUENTIAL;
        try {
            return PlayMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SEQUENTIAL;
        }
    }
}