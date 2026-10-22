package com.musicmod.client;

import com.musicmod.MusicMod;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SRequestSongInfoPacket;
import com.musicmod.network.packet.S2CSongInfoPacket;
import com.musicmod.network.packet.S2CSongTagsPacket;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端歌曲标签缓存（单例）。
 * 存储服务器下发的标签摘要（标题/艺术家/专辑/时长）与按需请求的单曲详情（封面/歌词）。
 * 供播放器界面分组（歌手/专辑）、详情展示使用。
 */
public class ClientTagStore {
    private static final ClientTagStore INSTANCE = new ClientTagStore();

    /** 标签摘要 */
    public static class TagEntry {
        public String title = "";
        public String artist = "";
        public String album = "";
        public int durationSec = -1; // -1 = 未知

        public String displayTitle(String fallback) {
            return title.isEmpty() ? fallback : title;
        }
    }

    /** 单曲详情（按需请求） */
    public static class SongInfo {
        public String title = "";
        public String artist = "";
        public String album = "";
        public long durationMs = -1;
        public String lyrics = "";
        public volatile boolean coverReady = false; // 封面纹理是否已就绪
    }

    private final Map<String, TagEntry> tagMap = new ConcurrentHashMap<>();
    private final Map<String, SongInfo> infoMap = new ConcurrentHashMap<>();
    private final Set<String> requestedInfo = ConcurrentHashMap.newKeySet();

    private ClientTagStore() {}

    public static ClientTagStore getInstance() {
        return INSTANCE;
    }

    /** 收到标签摘要批量同步 */
    public void onTagsReceived(List<S2CSongTagsPacket.Entry> entries) {
        String currentSong = ClientMusicManager.getInstance().getCurrentSong();
        for (S2CSongTagsPacket.Entry e : entries) {
            TagEntry t = new TagEntry();
            t.title = e.title;
            t.artist = e.artist;
            t.album = e.album;
            t.durationSec = e.durationSec;
            tagMap.put(e.songName, t);
            // 重进服务器时播放包先于标签到达，fallbackDurationMs 未设置；
            // 标签到达后回补当前播放歌曲的回退时长，恢复进度条渲染与拖动
            if (e.durationSec > 0 && e.songName.equals(currentSong)) {
                ClientMusicManager.getInstance().getAudioEngine()
                        .setFallbackDurationMs(e.durationSec * 1000L);
            }
        }
        MusicMod.LOGGER.info("Received tags for {} songs", entries.size());
    }

    /** 收到单曲详情 */
    public void onSongInfoReceived(String songName, String title, String artist, String album,
                                   long durationMs, byte[] coverJpeg, String lyrics) {
        SongInfo info = infoMap.computeIfAbsent(songName, k -> new SongInfo());
        info.title = title;
        info.artist = artist;
        info.album = album;
        info.durationMs = durationMs;
        info.lyrics = lyrics;
        // 回补当前播放歌曲的回退时长（同 onTagsReceived，详情包可能先到）
        if (durationMs > 0 && songName.equals(ClientMusicManager.getInstance().getCurrentSong())) {
            ClientMusicManager.getInstance().getAudioEngine().setFallbackDurationMs(durationMs);
        }
        // 注册封面纹理（在主线程）
        if (coverJpeg != null && coverJpeg.length > 0) {
            CoverTextureManager.setCover(songName, coverJpeg, () -> info.coverReady = true);
        } else {
            info.coverReady = false;
        }
    }

    /** 请求单曲详情（自动去重，同一首只请求一次直到收到响应） */
    public void requestSongInfo(String songName) {
        if (songName == null || songName.isEmpty()) return;
        if (infoMap.containsKey(songName)) return;
        if (!requestedInfo.add(songName)) return; // 已在请求中
        NetworkHandler.sendToServer(new C2SRequestSongInfoPacket(songName));
    }

    /** 询问是否已有详情缓存 */
    public boolean hasSongInfo(String songName) {
        return infoMap.containsKey(songName);
    }

    public SongInfo getSongInfo(String songName) {
        return infoMap.get(songName);
    }

    public TagEntry getTag(String songName) {
        return tagMap.get(songName);
    }

    /** 显示标题（无标签时返回 fallback，通常为歌曲名） */
    public String displayTitle(String songName) {
        TagEntry t = tagMap.get(songName);
        if (t != null) return t.displayTitle(songName);
        return songName;
    }

    public String artist(String songName) {
        TagEntry t = tagMap.get(songName);
        return t != null ? t.artist : "";
    }

    public String album(String songName) {
        TagEntry t = tagMap.get(songName);
        return t != null ? t.album : "";
    }

    public int durationSec(String songName) {
        TagEntry t = tagMap.get(songName);
        return t != null ? t.durationSec : -1;
    }

    /**
     * 当标签时长未知时，用音频流实测时长补写（服务端 TagReader 对部分格式解析失败，
     * 但客户端 AudioEngine 播放时可从流 frameLength 获取准确时长）。
     */
    public void updateDurationIfUnknown(String songName, int durationSec) {
        if (songName == null || songName.isEmpty() || durationSec <= 0) return;
        TagEntry t = tagMap.get(songName);
        if (t != null && t.durationSec > 0) return; // 已有标签时长，不覆盖
        if (t == null) {
            t = new TagEntry();
            tagMap.put(songName, t);
        }
        t.durationSec = durationSec;
    }

    /** 清空（断开连接时调用） */
    public void clear() {
        tagMap.clear();
        infoMap.clear();
        requestedInfo.clear();
        CoverTextureManager.clearAll();
    }
}