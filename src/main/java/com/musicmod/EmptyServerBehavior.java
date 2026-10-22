package com.musicmod;

/**
 * 服务器无人时的音乐行为枚举（0.17.0 新增）。
 * <ul>
 *   <li>{@link #CONTINUE} - 继续播放（默认）：无人时音乐照常播放</li>
 *   <li>{@link #STOP}     - 停止播放：最后一名玩家离开后停止音乐，有人加入后从头开始</li>
 *   <li>{@link #PAUSE}    - 暂停播放：最后一名玩家离开后暂停，有人加入后从暂停处继续</li>
 * </ul>
 */
public enum EmptyServerBehavior {
    CONTINUE("继续播放"),
    STOP("停止播放"),
    PAUSE("暂停直到有人加入");

    private final String displayName;

    EmptyServerBehavior(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    /** 循环切换到下一个行为 */
    public EmptyServerBehavior next() {
        EmptyServerBehavior[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    /** 按名称解析，无效名称回退到 CONTINUE */
    public static EmptyServerBehavior fromName(String name) {
        if (name == null || name.isEmpty()) return CONTINUE;
        try {
            return EmptyServerBehavior.valueOf(name);
        } catch (IllegalArgumentException e) {
            return CONTINUE;
        }
    }
}