package com.musicmod.audio;

import com.musicmod.MusicMod;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器端音乐文件库管理。
 * 扫描服务器指定文件夹（默认 config/musicmod/songs/）中的音乐文件。
 * 支持扫描格式：mp3, ogg, flac, wav, aiff, aif, au, m4a, mp4, aac, wma, opus。
 * 实际可播放格式取决于客户端解码库：mp3/ogg/wav/aiff/au（内置 SPI 支持），
 * flac（jflac-codec）、m4a/mp4/aac（jaad）已内置 SPI 支持，
 * wma/opus 需额外的 Java Sound SPI 库才能播放。
 */
public class MusicLibrary {
    // 支持的音频文件扩展名
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "mp3", "ogg", "flac", "wav", "aiff", "aif", "au",
            "m4a", "mp4", "aac", "wma", "opus"
    );

    private final File songsDir;
    // 歌曲名（不含扩展名） -> 文件
    private final Map<String, File> songMap = new ConcurrentHashMap<>();
    // 歌曲名 -> 完整文件名（含扩展名，用于显示）
    private final Map<String, String> fileNameMap = new ConcurrentHashMap<>();
    // 歌曲名 -> 元数据标签（标题/艺术家/专辑/时长）
    private final Map<String, SongTags> tagMap = new ConcurrentHashMap<>();

    public MusicLibrary(File songsDir) {
        this.songsDir = songsDir;
    }

    /**
     * 扫描音乐文件夹，刷新歌曲列表
     */
    public void scan() {
        songMap.clear();
        fileNameMap.clear();
        tagMap.clear();
        if (!songsDir.exists()) {
            boolean created = songsDir.mkdirs();
            MusicMod.LOGGER.info("Music songs directory {} created: {}", songsDir.getPath(), created);
            return;
        }
        File[] files = songsDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile() && isSupportedFile(file)) {
                String name = getSongName(file);
                // 处理重名：如果重名则加上扩展名区分
                if (songMap.containsKey(name)) {
                    name = file.getName();
                }
                songMap.put(name, file);
                fileNameMap.put(name, file.getName());
                // 提取元数据标签（失败静默降级为文件名）
                SongTags tags = TagReader.read(file);
                tagMap.put(name, tags);
            }
        }
        MusicMod.LOGGER.info("Music library scanned: {} songs found in {}", songMap.size(), songsDir.getPath());
    }

    /**
     * 获取歌曲标签（扫描时已提取）
     */
    public SongTags getTags(String songName) {
        SongTags t = tagMap.get(songName);
        if (t != null) return t;
        // 兜底：重名处理过的歌曲可能以完整文件名为 key
        File f = songMap.get(songName);
        if (f != null) {
            SongTags nt = TagReader.read(f);
            tagMap.put(songName, nt);
            return nt;
        }
        return new SongTags(songName);
    }

    /**
     * 判断文件是否为支持的音乐文件
     */
    public static boolean isSupportedFile(File file) {
        String name = file.getName();
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 获取歌曲显示名（不含扩展名）
     */
    public static String getSongName(File file) {
        String name = file.getName();
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(0, dotIdx) : name;
    }

    /**
     * 模糊查找歌曲：先精确匹配歌曲名，再匹配标签标题，再包含匹配，最后文件名匹配。
     * 支持通过歌曲标签标题（如 ID3 title）播放，而非仅文件名。
     */
    public File findSong(String query) {
        // 精确匹配（歌曲名 = 文件名去扩展名）
        File exact = songMap.get(query);
        if (exact != null) return exact;

        // 忽略大小写精确匹配（歌曲名）
        for (Map.Entry<String, File> entry : songMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(query)) {
                return entry.getValue();
            }
        }

        // 标签标题精确匹配（支持通过歌曲标题播放）
        for (Map.Entry<String, SongTags> entry : tagMap.entrySet()) {
            if (entry.getValue().getDisplayTitle().equalsIgnoreCase(query)) {
                return songMap.get(entry.getKey());
            }
        }

        // 包含匹配（歌曲名）
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, File> entry : songMap.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return entry.getValue();
            }
        }

        // 标签标题包含匹配
        for (Map.Entry<String, SongTags> entry : tagMap.entrySet()) {
            if (entry.getValue().getDisplayTitle().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return songMap.get(entry.getKey());
            }
        }

        // 文件名匹配
        for (Map.Entry<String, File> entry : songMap.entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(query)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public File getSongFile(String name) {
        return songMap.get(name);
    }

    public List<String> getSongNames() {
        List<String> names = new ArrayList<>(songMap.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public Map<String, File> getSongMap() {
        return Collections.unmodifiableMap(songMap);
    }

    public File getSongsDir() {
        return songsDir;
    }

    public int getSongCount() {
        return songMap.size();
    }
}