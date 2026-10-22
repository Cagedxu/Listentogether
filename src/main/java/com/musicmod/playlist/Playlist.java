package com.musicmod.playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌单数据模型（客户端/服务端通用纯数据类）。
 * <p>
 * 歌单由服务器统一保存并同步给所有客户端，因此所有玩家看到的是同一份歌单；
 * 修改操作（新建/重命名/删除/增删歌曲）需要 OP 权限，由服务端校验。
 * <p>
 * 歌单内的歌曲以 {@link com.musicmod.audio.MusicLibrary} 中的歌曲名（文件名去扩展名）保存。
 * 音乐文件被删除后歌单条目不会自动丢失，播放/展示时会标记为缺失。
 */
public class Playlist {

    /** 内置「收藏」歌单名称（客户端本地维护，服务器保留该名称防止冲突） */
    public static final String BUILTIN_FAVORITES_NAME = "收藏";
    /** 内置「最近播放」歌单名称（客户端本地维护，服务器保留该名称防止冲突） */
    public static final String BUILTIN_RECENT_NAME = "最近播放";

    /** 歌单唯一 ID（UUID 字符串），重命名不影响 ID */
    private final String id;
    private String name;
    /** 歌曲名列表，保持用户添加顺序（播放时即按此顺序） */
    private final List<String> songs;
    /** 创建时间戳，用于稳定排序 */
    private final long createdAt;

    /**
     * @param id        唯一 ID
     * @param name      歌单名称
     * @param createdAt 创建时间戳
     * @param songs     歌曲名列表（可为 null）
     */
    public Playlist(String id, String name, long createdAt, List<String> songs) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.createdAt = createdAt;
        this.songs = new ArrayList<>();
        if (songs != null) {
            for (String song : songs) {
                if (song != null && !song.isEmpty() && !this.songs.contains(song)) {
                    this.songs.add(song);
                }
            }
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** 只读歌曲列表（顺序即播放顺序） */
    public List<String> getSongs() {
        return Collections.unmodifiableList(songs);
    }

    /** 包内可见的可变歌曲列表，仅供 {@link PlaylistLibrary} 修改 */
    List<String> mutableSongs() {
        return songs;
    }

    public int size() {
        return songs.size();
    }

    public boolean isEmpty() {
        return songs.isEmpty();
    }

    public boolean contains(String song) {
        return songs.contains(song);
    }
}
