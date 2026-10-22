package com.musicmod.client;

import com.musicmod.playlist.Playlist;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端歌单缓存（单例）。
 * <p>
 * 自定义歌单的权威数据在服务端，客户端只保存服务器最近一次广播的快照；
 * 除此之外还内置两个<strong>本地</strong>歌单：
 * <ul>
 *   <li>收藏：与「全部歌曲」列表中的 ♥ 按钮联动，数据来自 {@link ClientFavorites}；</li>
 *   <li>最近播放：与服务器播放指令联动，数据来自 {@link ClientMusicManager}，本地持久化。</li>
 * </ul>
 * 内置歌单不参与服务器同步，每个玩家拥有自己的收藏与播放历史。
 */
public class ClientPlaylists {

    /** 内置收藏歌单 ID */
    public static final String FAVORITES_ID = "__builtin_favorites__";
    /** 内置最近播放歌单 ID */
    public static final String RECENT_ID = "__builtin_recent__";
    /** 内置收藏歌单名称（服务器不允许创建同名歌单） */
    public static final String FAVORITES_NAME = Playlist.BUILTIN_FAVORITES_NAME;
    /** 内置最近播放歌单名称（服务器不允许创建同名歌单） */
    public static final String RECENT_NAME = Playlist.BUILTIN_RECENT_NAME;

    private static final ClientPlaylists INSTANCE = new ClientPlaylists();

    private volatile List<Playlist> playlists = List.of();

    private ClientPlaylists() {}

    public static ClientPlaylists getInstance() {
        return INSTANCE;
    }

    /** 收到服务器全量歌单同步 */
    public void onPlaylistsReceived(List<Playlist> received) {
        this.playlists = received == null ? List.of() : List.copyOf(received);
    }

    /** 服务器自定义歌单快照（按服务器创建顺序） */
    public List<Playlist> getAll() {
        return playlists;
    }

    /** 界面展示用完整列表：内置「收藏」「最近播放」在前，自定义歌单在后 */
    public List<Playlist> getDisplayPlaylists() {
        List<Playlist> result = new ArrayList<>(playlists.size() + 2);
        result.add(createFavoritesPlaylist());
        result.add(createRecentPlaylist());
        result.addAll(playlists);
        return result;
    }

    private Playlist createFavoritesPlaylist() {
        return new Playlist(FAVORITES_ID, FAVORITES_NAME, 0L, ClientFavorites.getFavorites());
    }

    private Playlist createRecentPlaylist() {
        return new Playlist(RECENT_ID, RECENT_NAME, 0L,
                ClientMusicManager.getInstance().getRecentPlayed());
    }

    /** 按 ID 查找，包含两个内置歌单；不存在返回 null */
    public Playlist getById(String id) {
        if (id == null || id.isEmpty()) return null;
        if (FAVORITES_ID.equals(id)) return createFavoritesPlaylist();
        if (RECENT_ID.equals(id)) return createRecentPlaylist();
        for (Playlist playlist : playlists) {
            if (playlist.getId().equals(id)) return playlist;
        }
        return null;
    }

    /** 是否为内置歌单（内置歌单不可重命名/删除） */
    public static boolean isBuiltIn(String id) {
        return FAVORITES_ID.equals(id) || RECENT_ID.equals(id);
    }

    public static boolean isFavorites(String id) {
        return FAVORITES_ID.equals(id);
    }

    public static boolean isRecent(String id) {
        return RECENT_ID.equals(id);
    }

    /** 服务器自定义歌单数量（不含内置） */
    public int size() {
        return playlists.size();
    }

    /** 断线时清空服务器歌单；本地收藏/最近播放保留 */
    public void clear() {
        playlists = List.of();
    }
}
