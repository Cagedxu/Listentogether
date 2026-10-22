package com.musicmod.playlist;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 服务器端歌单库：负责歌单的增删改查与 JSON 持久化。
 * <p>
 * 存储位置：{@code <服务器目录>/config/musicmod/playlists.json}。
 * 文件格式：
 * <pre>{@code
 * {
 *   "version": 1,
 *   "playlists": [
 *     { "id": "...", "name": "我的歌单", "createdAt": 1700000000000, "songs": ["song1", "song2"] }
 *   ]
 * }
 * }</pre>
 * 歌单库只保存歌曲名，不保存音频数据；歌曲文件仍由 {@link com.musicmod.audio.MusicLibrary} 管理。
 * 该类的所有公开方法均加锁，允许命令、网络包与服务端 tick 并发访问。
 */
public class PlaylistLibrary {

    private static final Logger LOGGER = LogManager.getLogger("together-listening-playlists");

    /** 最多允许创建的歌单数量 */
    public static final int MAX_PLAYLISTS = 64;
    /** 单个歌单最多歌曲数量 */
    public static final int MAX_SONGS_PER_PLAYLIST = 500;
    /** 歌单名称最大长度（字符） */
    public static final int MAX_NAME_LENGTH = 32;

    private static final int DATA_VERSION = 1;

    private final File file;
    private final List<Playlist> playlists = new ArrayList<>();
    private boolean loaded = false;

    public PlaylistLibrary(File file) {
        this.file = file;
    }

    // ==================== 持久化 ====================

    /**
     * 从磁盘载入歌单。文件不存在或解析失败时以空列表启动，不抛出异常。
     */
    public synchronized void load() {
        playlists.clear();
        loaded = true;
        if (file == null || !file.isFile()) {
            LOGGER.info("Playlist file not found, starting with empty playlist library: {}",
                    file == null ? "<null>" : file.getAbsolutePath());
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (rootElement == null || !rootElement.isJsonObject()) {
                throw new IllegalArgumentException("根节点不是 JSON 对象");
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonElement listElement = root.get("playlists");
            if (listElement == null || !listElement.isJsonArray()) {
                return;
            }
            JsonArray array = listElement.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                Playlist playlist = parsePlaylist(element.getAsJsonObject());
                if (playlist == null) continue;
                // 防止损坏文件造成 ID/名称重复
                if (getByIdInternal(playlist.getId()) != null) continue;
                if (findByNameInternal(playlist.getName()) != null) continue;
                if (playlists.size() >= MAX_PLAYLISTS) {
                    LOGGER.warn("Playlist limit reached while loading, ignoring remaining entries");
                    break;
                }
                playlists.add(playlist);
            }
            LOGGER.info("Loaded {} playlists from {}", playlists.size(), file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Failed to load playlists from " + file.getAbsolutePath()
                    + ", starting with empty library", e);
            playlists.clear();
        }
    }

    private Playlist parsePlaylist(JsonObject object) {
        String id = getString(object, "id");
        String name = sanitizeName(getString(object, "name"));
        if (id.isEmpty() || name.isEmpty()) return null;
        long createdAt = 0;
        try {
            if (object.has("createdAt")) createdAt = object.get("createdAt").getAsLong();
        } catch (Exception ignored) {}
        List<String> songs = new ArrayList<>();
        JsonElement songsElement = object.get("songs");
        if (songsElement != null && songsElement.isJsonArray()) {
            for (JsonElement songElement : songsElement.getAsJsonArray()) {
                if (songElement == null || !songElement.isJsonPrimitive()) continue;
                String song = songElement.getAsString();
                if (song == null || song.isEmpty() || song.length() > 256) continue;
                if (!songs.contains(song)) songs.add(song);
                if (songs.size() >= MAX_SONGS_PER_PLAYLIST) break;
            }
        }
        return new Playlist(id, name, createdAt, songs);
    }

    /** 将当前歌单写入磁盘（先写临时文件再原子替换，避免写一半损坏）。 */
    public synchronized void save() {
        if (file == null) return;
        try {
            Path target = file.toPath().toAbsolutePath();
            Path dir = target.getParent();
            if (dir != null) Files.createDirectories(dir);

            JsonObject root = new JsonObject();
            root.addProperty("version", DATA_VERSION);
            JsonArray array = new JsonArray();
            for (Playlist playlist : playlists) {
                JsonObject object = new JsonObject();
                object.addProperty("id", playlist.getId());
                object.addProperty("name", playlist.getName());
                object.addProperty("createdAt", playlist.getCreatedAt());
                JsonArray songs = new JsonArray();
                for (String song : playlist.getSongs()) {
                    songs.add(song);
                }
                object.add("songs", songs);
                array.add(object);
            }
            root.add("playlists", array);

            String json = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create()
                    .toJson(root);

            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save playlists to {}", file.getAbsolutePath(), e);
        }
    }

    // ==================== 查询 ====================

    /** 返回歌单快照（按创建顺序），调用方可安全遍历。 */
    public synchronized List<Playlist> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(playlists));
    }

    public synchronized Playlist getById(String id) {
        return getByIdInternal(id);
    }

    private Playlist getByIdInternal(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Playlist playlist : playlists) {
            if (playlist.getId().equals(id)) return playlist;
        }
        return null;
    }

    /** 按名称查找（忽略大小写），用于命令与 GUI 的显示名称查找。 */
    public synchronized Playlist findByName(String name) {
        return findByNameInternal(name);
    }

    private Playlist findByNameInternal(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Playlist playlist : playlists) {
            if (playlist.getName().equalsIgnoreCase(name)) return playlist;
        }
        return null;
    }

    public synchronized int size() {
        return playlists.size();
    }

    public synchronized boolean isEmpty() {
        return playlists.isEmpty();
    }

    // ==================== 修改 ====================

    /**
     * 新建歌单。
     *
     * @return 新建的歌单
     * @throws IllegalArgumentException 名称为空/重复或数量达到上限时抛出（消息可直接展示）
     */
    public synchronized Playlist create(String name) {
        ensureLoaded();
        String clean = sanitizeName(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("歌单名称不能为空");
        }
        checkReservedName(clean);
        if (playlists.size() >= MAX_PLAYLISTS) {
            throw new IllegalArgumentException("歌单数量已达上限（" + MAX_PLAYLISTS + " 个）");
        }
        if (findByNameInternal(clean) != null) {
            throw new IllegalArgumentException("已存在同名歌单: " + clean);
        }
        Playlist playlist = new Playlist(UUID.randomUUID().toString(), clean,
                System.currentTimeMillis(), null);
        playlists.add(playlist);
        save();
        LOGGER.info("Created playlist '{}' ({})", clean, playlist.getId());
        return playlist;
    }

    /**
     * 重命名歌单。
     *
     * @throws IllegalArgumentException 歌单不存在/新名称为空/与其它歌单重名时抛出
     */
    public synchronized void rename(String id, String newName) {
        ensureLoaded();
        Playlist playlist = getByIdInternal(id);
        if (playlist == null) {
            throw new IllegalArgumentException("歌单不存在");
        }
        String clean = sanitizeName(newName);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("歌单名称不能为空");
        }
        checkReservedName(clean);
        if (clean.equals(playlist.getName())) {
            return;
        }
        Playlist sameName = findByNameInternal(clean);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new IllegalArgumentException("已存在同名歌单: " + clean);
        }
        playlist.setName(clean);
        save();
        LOGGER.info("Renamed playlist {} to '{}'", id, clean);
    }

    /**
     * 删除歌单。
     *
     * @return 被删除的歌单
     * @throws IllegalArgumentException 歌单不存在时抛出
     */
    public synchronized Playlist delete(String id) {
        ensureLoaded();
        Playlist playlist = getByIdInternal(id);
        if (playlist == null) {
            throw new IllegalArgumentException("歌单不存在");
        }
        playlists.remove(playlist);
        save();
        LOGGER.info("Deleted playlist '{}' ({})", playlist.getName(), id);
        return playlist;
    }

    /**
     * 向歌单添加歌曲（重复添加会被拒绝）。
     *
     * @param id   歌单 ID
     * @param song 歌曲名（音乐库中的键）
     * @throws IllegalArgumentException 歌单不存在/歌曲已在歌单/歌单已满时抛出
     */
    public synchronized void addSong(String id, String song) {
        ensureLoaded();
        Playlist playlist = getByIdInternal(id);
        if (playlist == null) {
            throw new IllegalArgumentException("歌单不存在");
        }
        if (song == null || song.isEmpty()) {
            throw new IllegalArgumentException("歌曲名不能为空");
        }
        if (playlist.contains(song)) {
            throw new IllegalArgumentException("歌曲已在歌单中: " + song);
        }
        if (playlist.size() >= MAX_SONGS_PER_PLAYLIST) {
            throw new IllegalArgumentException("单个歌单最多 " + MAX_SONGS_PER_PLAYLIST + " 首歌曲");
        }
        playlist.mutableSongs().add(song);
        save();
    }

    /**
     * 批量向歌单添加歌曲（自动跳过重复项，只落盘一次）。
     *
     * @return 实际添加的歌曲数量
     * @throws IllegalArgumentException 歌单不存在时抛出
     */
    public synchronized int addSongs(String id, List<String> songs) {
        ensureLoaded();
        Playlist playlist = getByIdInternal(id);
        if (playlist == null) {
            throw new IllegalArgumentException("歌单不存在");
        }
        if (songs == null || songs.isEmpty()) return 0;
        int added = 0;
        for (String song : songs) {
            if (song == null || song.isEmpty() || playlist.contains(song)) continue;
            if (playlist.size() >= MAX_SONGS_PER_PLAYLIST) break;
            playlist.mutableSongs().add(song);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    /**
     * 从歌单移除歌曲。
     *
     * @throws IllegalArgumentException 歌单不存在或歌曲不在歌单中时抛出
     */
    public synchronized void removeSong(String id, String song) {
        ensureLoaded();
        Playlist playlist = getByIdInternal(id);
        if (playlist == null) {
            throw new IllegalArgumentException("歌单不存在");
        }
        if (song == null || !playlist.mutableSongs().remove(song)) {
            throw new IllegalArgumentException("歌单中没有这首歌: " + (song == null ? "" : song));
        }
        save();
    }

    // ==================== 工具方法 ====================

    /** 内置歌单名称（收藏 / 最近播放）由客户端维护，服务器禁止创建同名歌单 */
    private static void checkReservedName(String name) {
        if (name.equalsIgnoreCase(Playlist.BUILTIN_FAVORITES_NAME)
                || name.equalsIgnoreCase(Playlist.BUILTIN_RECENT_NAME)) {
            throw new IllegalArgumentException("「" + name + "」是内置歌单名称，请换一个名称");
        }
    }

    /**
     * 清理歌单名称：去掉首尾空白、颜色控制符与不可见控制字符，并限制长度。
     * 空名称返回空串（由调用方拒绝）。
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        // 去掉 § 颜色控制序列（§ + 颜色字符）与所有控制字符，避免聊天/界面格式注入
        name = name.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "");
        name = name.replaceAll("[\\p{Cntrl}\\u00a7]", "").trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).trim();
        }
        return name;
    }

    private static String getString(JsonObject object, String key) {        if (object == null || !object.has(key)) return "";
        try {
            JsonElement element = object.get(key);
            return element == null || element.isJsonNull() ? "" : element.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** 供调试/统计：返回名称与歌曲数量摘要 */
    public synchronized String summary() {
        StringBuilder sb = new StringBuilder();
        for (Playlist playlist : playlists) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(playlist.getName()).append('(').append(playlist.size()).append(')');
        }
        return sb.toString();
    }

    /** 按名称自然排序后的副本（用于命令行展示；GUI 使用创建顺序） */
    public synchronized List<Playlist> getAllSortedByName() {
        List<Playlist> result = new ArrayList<>(playlists);
        result.sort((a, b) -> a.getName().toLowerCase(Locale.ROOT)
                .compareTo(b.getName().toLowerCase(Locale.ROOT)));
        return result;
    }
}
