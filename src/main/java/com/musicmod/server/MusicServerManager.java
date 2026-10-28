package com.musicmod.server;

import com.musicmod.i18n.Lang;

import com.musicmod.MusicMod;
import com.musicmod.PlayMode;
import com.musicmod.audio.MusicLibrary;
import com.musicmod.audio.SongTags;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.*;
import com.musicmod.playlist.Playlist;
import com.musicmod.playlist.PlaylistLibrary;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器端音乐管理器（单例）。
 * 负责音乐库扫描、播放状态管理、文件分发与多端同步。
 */
public class MusicServerManager {
    private static final MusicServerManager INSTANCE = new MusicServerManager();

    private MinecraftServer server;
    private MusicLibrary musicLibrary;
    /** 服务端歌单库（全服共享，持久化到 config/musicmod/playlists.json） */
    private PlaylistLibrary playlistLibrary;

    // 播放状态
    private String currentSong = "";           // 当前歌曲名
    private String currentFileName = "";       // 当前歌曲完整文件名
    private String currentMd5 = "";            // 当前歌曲MD5
    private long currentFileSize = 0;          // 当前歌曲文件大小
    private boolean playing = false;
    private boolean paused = false;
    private boolean pausedByEmptyServer = false;  // 因服务器无人而暂停（0.17.0）
    private long playStartTime = 0;            // 开始播放的系统时间戳
    private long pausedAtMs = 0;               // 暂停时的播放位置（毫秒）
    private long totalPausedDuration = 0;      // 累计暂停时长（毫秒）

    // 播放队列
    private final List<String> playQueue = new ArrayList<>();
    private int queueIndex = -1;
    private com.musicmod.PlayMode playMode = com.musicmod.PlayMode.SEQUENTIAL;  // 播放模式（0.26.0 默认顺序播放）
    private final Random random = new Random();

    // MD5 缓存（文件路径 -> [MD5, 修改时间戳]），文件变化时自动重算
    private final Map<String, String[]> md5Cache = new ConcurrentHashMap<>();

    // 正在传输文件的玩家集合（避免重复传输）
    private final Set<UUID> transferringPlayers = ConcurrentHashMap.newKeySet();

    // 文件传输限流：每个玩家同时只传输一个文件
    private final Map<UUID, String> playerTransferringSong = new ConcurrentHashMap<>();

    // 上传会话管理（玩家UUID -> 上传会话）
    private final Map<UUID, UploadSession> uploadSessions = new ConcurrentHashMap<>();

    // 上传文件大小上限（默认 100MB）
    private static final long MAX_UPLOAD_SIZE = 100L * 1024 * 1024;

    private MusicServerManager() {}

    public static MusicServerManager getInstance() {
        return INSTANCE;
    }

    /**
     * 服务器启动时调用
     */
    public void onServerStart(MinecraftServer server) {
        this.server = server;
        // 歌曲目录从 Forge 服务器配置读取（默认 config/musicmod/songs）
        String songsDirPath = MusicModConfig.getServerSongsDir();
        File songsDir = server.getServerDirectory().toPath().resolve(songsDirPath).toFile();
        this.musicLibrary = new MusicLibrary(songsDir);
        this.musicLibrary.scan();

        // 载入服务器歌单（JSON 持久化；文件不存在时以空歌单库启动）
        File playlistFile = server.getServerDirectory().toPath()
                .resolve("config").resolve("musicmod").resolve("playlists.json").toFile();
        this.playlistLibrary = new PlaylistLibrary(playlistFile);
        this.playlistLibrary.load();

        // 从配置读取默认播放模式
        this.playMode = MusicModConfig.getServerDefaultPlayMode();

        // 注册玩家加入事件监听
        com.musicmod.server.ServerEventHandler.register();
    }

    /**
     * 服务器停止时调用
     */
    public void onServerStop() {
        // 单人退出世界/服务器关闭时直接停止音乐
        stopMusic();
        playQueue.clear();
        queueIndex = -1;
        md5Cache.clear();
        transferringPlayers.clear();
        playerTransferringSong.clear();
        cleanupAllUploadSessions();
        // 歌单在每次修改后已即时落盘，这里再保存一次作为兜底
        if (playlistLibrary != null) {
            playlistLibrary.save();
            playlistLibrary = null;
        }
        this.server = null;
    }

    public MusicLibrary getMusicLibrary() {
        return musicLibrary;
    }

    public PlaylistLibrary getPlaylistLibrary() {
        return playlistLibrary;
    }

    /** 当前全服歌单快照（服务端未初始化时返回空列表） */
    public List<Playlist> getPlaylists() {
        return playlistLibrary != null ? playlistLibrary.getAll() : List.of();
    }

    /**
     * 重新扫描音乐库
     */
    public void rescanLibrary() {
        if (musicLibrary != null) {
            musicLibrary.scan();
            md5Cache.clear();
        }
    }

    /**
     * 切换歌曲目录并重新扫描（配置界面修改目录后调用，仅单人游戏/服务端有效）
     */
    public void reloadLibrary(String songsDirPath) {
        if (server == null) return;
        File songsDir = server.getServerDirectory().toPath().resolve(songsDirPath).toFile();
        this.musicLibrary = new MusicLibrary(songsDir);
        this.musicLibrary.scan();
        md5Cache.clear();
        MusicMod.LOGGER.info("Music library reloaded from: {}", songsDir.getAbsolutePath());
    }

    // ==================== 播放控制 ====================

    /**
     * 播放指定歌曲（全服同步）
     * @return 成功返回true
     */
    public boolean playMusic(String songQuery) {
        if (musicLibrary == null) return false;
        File songFile = musicLibrary.findSong(songQuery);
        if (songFile == null) {
            // 找不到时自动重新扫描（支持服务器运行期间新增的音乐文件）
            musicLibrary.scan();
            songFile = musicLibrary.findSong(songQuery);
        }
        if (songFile == null) return false;

        String songName = getSongDisplayName(songFile);
        currentSong = songName;
        currentFileName = songFile.getName();
        currentMd5 = computeMd5(songFile);
        currentFileSize = songFile.length();
        playing = true;
        paused = false;
        pausedByEmptyServer = false;
        playStartTime = System.currentTimeMillis();
        totalPausedDuration = 0;
        pausedAtMs = 0;

        // 更新队列位置（队列为空或不包含当前歌曲时，自动将整个歌曲库作为播放队列）
        boolean rebuiltQueue = false;
        if (playQueue.isEmpty() || !playQueue.contains(songName)) {
            playQueue.clear();
            playQueue.addAll(musicLibrary.getSongNames());
            rebuiltQueue = true;
        }
        if (playMode == com.musicmod.PlayMode.SHUFFLE && rebuiltQueue) {
            shuffleQueueKeepingCurrent();
        } else {
            updateQueuePosition(songName);
        }

        // 广播播放指令
        long offsetMs = 0;
        NetworkHandler.sendToAllPlayers(new S2CPlayPacket(
                songName, currentFileName, currentMd5, currentFileSize, offsetMs));

        // 通知所有玩家（聊天栏消息，显示标签标题）
        SongTags tags = musicLibrary.getTags(songName);
        broadcastMessage(Lang.tr("§6♪ §e正在播放: §f") + tags.getDisplayTitle());
        return true;
    }

    /**
     * 播放指定歌曲并设置播放队列（歌单/歌手/专辑上下文播放，0.14.0）。
     * 队列限定后续 playNext/playPrevious 的选歌范围。
     * @param songQuery 要播放的歌曲名
     * @param queue     播放队列（null 或空则回退到全库队列）
     * @return 成功返回true
     */
    public boolean playMusicWithQueue(String songQuery, List<String> queue) {
        if (queue != null && !queue.isEmpty()) {
            playQueue.clear();
            // 仅保留音乐库中实际存在的歌曲，避免队列中残留已删除文件
            Set<String> libraryNames = new HashSet<>(musicLibrary.getSongNames());
            for (String s : queue) {
                if (libraryNames.contains(s)) {
                    playQueue.add(s);
                }
            }
            if (playQueue.isEmpty()) {
                // 队列全部失效，回退全库
                playQueue.addAll(musicLibrary.getSongNames());
            }
        }
        boolean ok = playMusic(songQuery);
        if (ok && playMode == com.musicmod.PlayMode.SHUFFLE) {
            shuffleQueueKeepingCurrent();
        }
        return ok;
    }

    /**
     * 停止播放
     */
    public void stopMusic() {
        if (!playing) return;
        playing = false;
        paused = false;
        pausedByEmptyServer = false;
        currentSong = "";
        currentFileName = "";
        currentMd5 = "";
        currentFileSize = 0;
        playStartTime = 0;
        totalPausedDuration = 0;
        pausedAtMs = 0;

        NetworkHandler.sendToAllPlayers(new S2CStopPacket());
        broadcastMessage(Lang.tr("§6♪ §7播放已停止"));
    }

    /**
     * 暂停/恢复
     */
    public boolean togglePause() {
        if (!playing) return false;
        if (!paused) {
            // 暂停：必须先取当前进度，再设置 paused；
            // getElapsedMs() 在 paused=true 时会直接返回旧的 pausedAtMs，
            // 若顺序反了会把暂停位置记成 0，恢复时从头播放。
            pausedAtMs = getElapsedMs();
            paused = true;
            NetworkHandler.sendToAllPlayers(new S2CPausePacket(true, pausedAtMs));
            broadcastMessage(Lang.tr("§6♪ §7已暂停: §f") + currentSong);
        } else {
            // 恢复：重新设置起始时间，使已播放时长 = pausedAtMs
            paused = false;
            pausedByEmptyServer = false;
            playStartTime = System.currentTimeMillis() - pausedAtMs;
            totalPausedDuration = 0;
            NetworkHandler.sendToAllPlayers(new S2CPausePacket(false, pausedAtMs));
            broadcastMessage(Lang.tr("§6♪ §a继续播放: §f") + currentSong);
        }
        return true;
    }

    /**
     * 跳转到指定位置（全服同步，由进度条拖动触发，仅 OP）。
     * seek 自动取消暂停，所有客户端从新位置重新播放：
     * 缓存命中直接偏移播放，未命中则重新请求带 offsetMs 的数据流。
     * @param seekMs 目标位置（毫秒）
     */
    public boolean seekTo(long seekMs) {
        if (!playing || currentSong.isEmpty()) return false;
        paused = false;
        pausedByEmptyServer = false;
        pausedAtMs = 0;
        totalPausedDuration = 0;
        // 重设计时基准，使 getElapsedMs() == seekMs
        playStartTime = System.currentTimeMillis() - seekMs;
        NetworkHandler.sendToAllPlayers(new S2CPlayPacket(
                currentSong, currentFileName, currentMd5, currentFileSize, seekMs));
        broadcastMessage(Lang.tr("§6♪ §e已跳转至 §f") + formatSeekTime(seekMs));
        return true;
    }

    private String formatSeekTime(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    /**
     * 播放下一首
     */
    public boolean playNext() {
        if (playQueue.isEmpty()) return false;
        // 随机模式下队列已经在进入随机模式时整体打乱，这里按打乱后的顺序前进。
        int nextIdx = queueIndex + 1;
        if (nextIdx >= playQueue.size()) {
            nextIdx = 0; // 循环播放
        }
        return playMusic(playQueue.get(nextIdx));
    }

    /**
     * 播放上一首
     */
    public boolean playPrevious() {
        if (playQueue.isEmpty()) return false;
        int prevIdx = queueIndex - 1;
        if (prevIdx < 0) {
            prevIdx = playQueue.size() - 1;
        }
        return playMusic(playQueue.get(prevIdx));
    }

    /**
     * 服务器每 tick 调用：检测当前歌曲是否播放完毕，按播放模式自动处理下一首。
     * 通过对比已播放时长与标签时长判断结束，加 1 秒容差避免标签估算误差导致提前切歌。
     */
    public void tick() {
        if (!playing || paused || currentSong.isEmpty() || musicLibrary == null) return;
        SongTags tags = musicLibrary.getTags(currentSong);
        if (tags.durationMs > 0 && getElapsedMs() >= tags.durationMs + 1000) {
            onSongFinished(currentSong);
        }
    }

    /**
     * 歌曲播放完毕回调（由客户端通知或服务器定时检测）
     */
    public void onSongFinished(String songName) {
        if (!playing || !songName.equals(currentSong)) return;
        switch (playMode) {
            case LOOP_ONE:
                if (!currentSong.isEmpty()) {
                    playMusic(currentSong);  // 重新播放当前歌曲
                } else {
                    stopMusic();
                }
                break;
            case SEQUENTIAL:
            case SHUFFLE:
            default:
                // 0.26.0：顺序播放自动续播下一首，列表末尾后回到第一首
                if (!playQueue.isEmpty()) {
                    playNext();
                } else {
                    stopMusic();
                }
                break;
        }
    }

    // ==================== 队列管理 ====================

    public void setQueue(List<String> songs) {
        playQueue.clear();
        playQueue.addAll(songs);
        queueIndex = -1;
    }

    public void addToQueue(String song) {
        playQueue.add(song);
    }

    public void clearQueue() {
        playQueue.clear();
        queueIndex = -1;
    }

    public void setPlayMode(PlayMode mode) {
        this.playMode = mode;
        if (mode == PlayMode.SHUFFLE) {
            shuffleQueueKeepingCurrent();
        }
        broadcastPlayMode();
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    /** 循环切换播放模式 */
    public PlayMode cyclePlayMode() {
        this.playMode = playMode.next();
        if (this.playMode == PlayMode.SHUFFLE) {
            shuffleQueueKeepingCurrent();
        }
        broadcastPlayMode();
        return this.playMode;
    }

    /** 广播当前播放模式给所有在线玩家 */
    public void broadcastPlayMode() {
        NetworkHandler.sendToAllPlayers(new S2CPlayModePacket(playMode));
    }

    /** OP 设置服务器无人时的音乐行为：保存配置并广播全服（0.25.0） */
    public void setEmptyBehavior(com.musicmod.EmptyServerBehavior behavior) {
        MusicModConfig.setServerEmptyServerBehavior(behavior);
        MusicModConfig.saveServerConfig();
        NetworkHandler.sendToAllPlayers(new S2CEmptyBehaviorPacket(behavior));
        MusicMod.LOGGER.info("Empty server behavior set to {} by OP", behavior.name());
    }

    /**
     * 当前歌曲 + 即将播放的歌曲（从当前队列位置向后，不包含已经播放过的歌曲）。
     * 循环单曲只显示当前歌曲。
     */
    private List<String> buildQueueView() {
        if (playQueue.isEmpty() || currentSong == null || currentSong.isEmpty()) {
            return new ArrayList<>();
        }
        int start = -1;
        for (int i = 0; i < playQueue.size(); i++) {
            if (playQueue.get(i).equalsIgnoreCase(currentSong)) {
                start = i;
                break;
            }
        }
        if (start < 0) return new ArrayList<>();
        List<String> view = new ArrayList<>();
        view.add(playQueue.get(start));
        if (playMode != com.musicmod.PlayMode.LOOP_ONE) {
            for (int i = start + 1; i < playQueue.size(); i++) {
                view.add(playQueue.get(i));
            }
        }
        return view;
    }

    /**
     * 整体打乱队列，并把当前歌曲放到队首；后续按打乱后的顺序播放。
     */
    private void shuffleQueueKeepingCurrent() {
        if (playQueue.size() < 2) {
            queueIndex = playQueue.isEmpty() ? -1 : 0;
            return;
        }
        String playingSong = currentSong;
        Collections.shuffle(playQueue, random);
        if (playingSong != null && !playingSong.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < playQueue.size(); i++) {
                if (playQueue.get(i).equalsIgnoreCase(playingSong)) {
                    idx = i;
                    break;
                }
            }
            if (idx > 0) {
                String song = playQueue.remove(idx);
                playQueue.add(0, song);
            }
            queueIndex = idx >= 0 ? 0 : -1;
        } else {
            queueIndex = -1;
        }
    }

    public List<String> getQueue() {
        return Collections.unmodifiableList(playQueue);
    }

    private void updateQueuePosition(String songName) {
        for (int i = 0; i < playQueue.size(); i++) {
            if (playQueue.get(i).equalsIgnoreCase(songName)) {
                queueIndex = i;
                return;
            }
        }
    }

    // ==================== 歌单管理 ====================

    /** 向指定玩家同步完整歌单列表 */
    public void sendPlaylistsToPlayer(ServerPlayer player) {
        if (playlistLibrary == null || player == null) return;
        NetworkHandler.sendToPlayer(new S2CPlaylistsPacket(playlistLibrary.getAll()), player);
    }

    /** 歌单发生变更后向所有在线玩家广播最新列表 */
    public void broadcastPlaylists() {
        if (playlistLibrary == null) return;
        NetworkHandler.sendToAllPlayers(new S2CPlaylistsPacket(playlistLibrary.getAll()));
    }

    /**
     * 新建歌单。
     * @return null 表示成功，否则为可直接展示的错误信息
     */
    public String createPlaylist(String name) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        try {
            Playlist playlist = playlistLibrary.create(name);
            broadcastPlaylists();
            MusicMod.LOGGER.info("Playlist '{}' created", playlist.getName());
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 重命名歌单；返回 null 表示成功 */
    public String renamePlaylist(String id, String newName) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        try {
            playlistLibrary.rename(id, newName);
            broadcastPlaylists();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 删除歌单；返回 null 表示成功 */
    public String deletePlaylist(String id) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        try {
            Playlist removed = playlistLibrary.delete(id);
            broadcastPlaylists();
            MusicMod.LOGGER.info("Playlist '{}' deleted", removed.getName());
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 向歌单添加歌曲（支持按标题/文件名模糊匹配）；返回 null 表示成功 */
    public String addSongToPlaylist(String id, String songQuery) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        Playlist playlist = playlistLibrary.getById(id);
        if (playlist == null) return Lang.tr("歌单不存在");
        String song = resolveLibrarySong(songQuery);
        if (song == null) return Lang.tr("未找到歌曲: ") + songQuery;
        try {
            playlistLibrary.addSong(id, song);
            broadcastPlaylists();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 批量向歌单添加歌曲（歌单内勾选后点「确定」）；返回 null 表示成功 */
    public String addSongsToPlaylist(String id, List<String> songQueries) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        Playlist playlist = playlistLibrary.getById(id);
        if (playlist == null) return Lang.tr("歌单不存在");
        if (songQueries == null || songQueries.isEmpty()) return Lang.tr("没有选择歌曲");
        List<String> resolved = new ArrayList<>();
        for (String query : songQueries) {
            String song = resolveLibrarySong(query);
            if (song != null && !resolved.contains(song)) {
                resolved.add(song);
            }
        }
        if (resolved.isEmpty()) return Lang.tr("没有可添加的歌曲");
        try {
            int added = playlistLibrary.addSongs(id, resolved);
            if (added <= 0) return Lang.tr("所选歌曲已在歌单中");
            broadcastPlaylists();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 从歌单移除歌曲；返回 null 表示成功 */
    public String removeSongFromPlaylist(String id, String songQuery) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        Playlist playlist = playlistLibrary.getById(id);
        if (playlist == null) return Lang.tr("歌单不存在");
        String song = resolvePlaylistSong(playlist, songQuery);
        if (song == null) return Lang.tr("歌单中没有这首歌: ") + songQuery;
        try {
            playlistLibrary.removeSong(id, song);
            broadcastPlaylists();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * 播放歌单：以歌单中的有效歌曲作为播放队列。
     * @param id        歌单 ID
     * @param songQuery 从指定歌曲开始（空串表示从第一首开始）
     * @return null 表示成功
     */
    public String playPlaylist(String id, String songQuery) {
        if (playlistLibrary == null) return Lang.tr("歌单系统未初始化");
        if (musicLibrary == null) return Lang.tr("音乐库未初始化");
        Playlist playlist = playlistLibrary.getById(id);
        if (playlist == null) return Lang.tr("歌单不存在");
        if (playlist.isEmpty()) return Lang.tr("歌单「") + playlist.getName() + Lang.tr("」还是空的");

        // 过滤掉已被删除的歌曲，保持歌单顺序
        Set<String> existing = new HashSet<>(musicLibrary.getSongNames());
        List<String> queue = new ArrayList<>();
        for (String song : playlist.getSongs()) {
            if (existing.contains(song)) queue.add(song);
        }
        if (queue.isEmpty()) return Lang.tr("歌单中的歌曲文件都不存在（可重新扫描音乐库）");

        String target;
        if (songQuery == null || songQuery.isEmpty()) {
            target = queue.get(0);
        } else if (queue.contains(songQuery)) {
            target = songQuery;
        } else {
            String resolved = resolveLibrarySong(songQuery);
            if (resolved == null || !queue.contains(resolved)) {
                return Lang.tr("歌单中没有这首歌: ") + songQuery;
            }
            target = resolved;
        }

        if (!playMusicWithQueue(target, queue)) return Lang.tr("播放失败");
        broadcastMessage(Lang.tr("§6♪ §a正在播放歌单: §f") + playlist.getName());
        return null;
    }

    /**
     * 将用户输入解析为音乐库中的歌曲名（先精确、忽略大小写，再走标签/模糊匹配）。
     */
    private String resolveLibrarySong(String query) {
        if (musicLibrary == null || query == null || query.isEmpty()) return null;
        Map<String, File> songMap = musicLibrary.getSongMap();
        if (songMap.containsKey(query)) return query;
        for (String name : songMap.keySet()) {
            if (name.equalsIgnoreCase(query)) return name;
        }
        File file = musicLibrary.findSong(query);
        if (file != null) {
            for (Map.Entry<String, File> entry : songMap.entrySet()) {
                if (entry.getValue().equals(file)) return entry.getKey();
            }
        }
        return null;
    }

    /** 解析歌单内的歌曲：优先精确匹配（即使文件已删除也能移除），否则按音乐库模糊匹配 */
    private String resolvePlaylistSong(Playlist playlist, String query) {
        if (query == null || query.isEmpty()) return null;
        if (playlist.contains(query)) return query;
        String resolved = resolveLibrarySong(query);
        return resolved != null && playlist.contains(resolved) ? resolved : null;
    }

    // ==================== 状态查询 ====================

    /**
     * 获取当前已播放时长（毫秒）
     */
    public long getElapsedMs() {
        if (!playing) return 0;
        if (paused) return pausedAtMs;
        return System.currentTimeMillis() - playStartTime;
    }

    public String getCurrentSong() {
        return currentSong;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    // ==================== 网络请求处理 ====================

    /**
     * 客户端请求歌曲列表（同时下发标签摘要供播放器界面分组显示）
     */
    public void sendSongListToPlayer(ServerPlayer player) {
        if (musicLibrary == null) return;
        List<String> songs = musicLibrary.getSongNames();
        NetworkHandler.sendToPlayer(
                new S2CSongListPacket(songs, buildQueueView(), currentSong,
                        playing, paused, playMode), player);
        // 批量下发标签摘要
        List<S2CSongTagsPacket.Entry> entries = new ArrayList<>(songs.size());
        for (String name : songs) {
            SongTags tags = musicLibrary.getTags(name);
            int durSec = tags.durationMs > 0 ? (int) (tags.durationMs / 1000) : -1;
            entries.add(new S2CSongTagsPacket.Entry(
                    name, tags.getDisplayTitle(), tags.getArtistOrEmpty(),
                    tags.getAlbumOrEmpty(), durSec));
        }
        NetworkHandler.sendToPlayer(new S2CSongTagsPacket(entries), player);
        // 同步歌单（刷新按钮/打开界面时一并更新）
        sendPlaylistsToPlayer(player);
    }

    /**
     * 客户端请求单曲详情（封面 + 歌词 + 完整标签）
     */
    public void sendSongInfoToPlayer(ServerPlayer player, String songName) {
        if (musicLibrary == null) return;
        File songFile = musicLibrary.findSong(songName);
        if (songFile == null) return;
        SongTags tags = musicLibrary.getTags(songName);

        // 封面：优先内嵌标签，无内嵌时回退到同名外置图片文件（song.jpg/song.png/cover.jpg 等）
        byte[] coverSrc = tags.coverImage;
        if (coverSrc == null || coverSrc.length == 0) {
            coverSrc = readExternalCover(songFile);
        }
        byte[] coverJpeg = new byte[0];
        String coverMime = "";
        if (coverSrc != null && coverSrc.length > 0) {
            try {
                byte[] scaled = scaleImageToJpeg(coverSrc, 128);
                if (scaled != null && scaled.length > 0 && scaled.length < 200_000) {
                    coverJpeg = scaled;
                    coverMime = "image/jpeg";
                }
            } catch (Exception e) {
                MusicMod.LOGGER.warn("Failed to scale cover for " + songName + ": " + e);
            }
        }

        // 歌词：优先内嵌，其次同名 .lrc 文件
        String lyrics = tags.lyrics;
        if (lyrics == null || lyrics.isEmpty()) {
            String lrc = readLrcFile(songFile);
            if (lrc != null) lyrics = lrc;
        }
        if (lyrics == null) lyrics = "";

        NetworkHandler.sendToPlayer(new S2CSongInfoPacket(
                songName, tags.getDisplayTitle(), tags.getArtistOrEmpty(),
                tags.getAlbumOrEmpty(), tags.durationMs, coverJpeg, coverMime, lyrics), player);
    }

    /**
     * 读取同名 .lrc 歌词文件（如 song.mp3 -> song.lrc）
     */
    private String readLrcFile(File songFile) {
        String name = songFile.getName();
        int dot = name.lastIndexOf('.');
        String lrcName = dot > 0 ? name.substring(0, dot) + ".lrc" : name + ".lrc";
        File lrcFile = new File(songFile.getParentFile(), lrcName);
        if (!lrcFile.exists()) return null;
        try {
            byte[] data = java.nio.file.Files.readAllBytes(lrcFile.toPath());
            // 尝试 UTF-8，若含 BOM 去掉
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return text;
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to read lrc file: " + lrcName);
            return null;
        }
    }

    /**
     * 查找歌曲同目录下的外置封面图片。
     * 查找顺序：同名 .jpg/.png/.jpeg → cover.jpg/.png → folder.jpg/.png
     * @return 图片字节，未找到返回 null
     */
    private byte[] readExternalCover(File songFile) {
        String baseName = getSongDisplayName(songFile);
        File dir = songFile.getParentFile();
        if (dir == null) return null;
        String[] candidates = {
                baseName + ".jpg", baseName + ".png", baseName + ".jpeg",
                baseName + ".JPG", baseName + ".PNG",
                "cover.jpg", "cover.png", "Cover.jpg", "Cover.png",
                "folder.jpg", "folder.png", "Folder.jpg", "Folder.png"
        };
        for (String name : candidates) {
            File img = new File(dir, name);
            if (img.exists() && img.isFile() && img.length() > 0 && img.length() < 5_000_000) {
                try {
                    return java.nio.file.Files.readAllBytes(img.toPath());
                } catch (Exception e) {
                    MusicMod.LOGGER.warn("Failed to read external cover: " + name);
                }
            }
        }
        return null;
    }

    /**
     * 将图片字节缩放至最大边长 maxPx 并编码为 JPEG
     */
    private byte[] scaleImageToJpeg(byte[] imgBytes, int maxPx) throws IOException {
        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(imgBytes));
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) maxPx / Math.max(w, h));
        int nw = Math.max(1, (int) (w * scale));
        int nh = Math.max(1, (int) (h * scale));
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "jpg", bos);
        return bos.toByteArray();
    }

    /**
     * 客户端请求歌曲文件数据
     */
    public void onSongDataRequest(ServerPlayer player, String songName, long offsetMs) {
        if (musicLibrary == null) return;
        File songFile = musicLibrary.findSong(songName);
        if (songFile == null || !songFile.exists()) {
            MusicMod.LOGGER.warn("Player {} requested unknown song: {}", player.getName().getString(), songName);
            return;
        }

        UUID playerId = player.getUUID();
        // 防止重复请求
        String currentTransfer = playerTransferringSong.get(playerId);
        if (currentTransfer != null && currentTransfer.equalsIgnoreCase(songName)) {
            return; // 正在传输同一首歌
        }
        playerTransferringSong.put(playerId, songName);

        // 在异步线程中传输文件，避免阻塞主线程
        String displayName = getSongDisplayName(songFile);
        String fileName = songFile.getName();
        String md5 = computeMd5(songFile);
        File fileToSend = songFile;

        Thread transferThread = new Thread(() -> {
            try {
                sendFileInChunks(player, displayName, fileName, md5, fileToSend, offsetMs);
            } catch (Exception e) {
                MusicMod.LOGGER.error("Failed to send song data to player {}", player.getName().getString(), e);
            } finally {
                playerTransferringSong.remove(playerId);
            }
        }, "MusicMod-Transfer-" + player.getName().getString());
        transferThread.setDaemon(true);
        transferThread.start();
    }

    /**
     * 分块发送文件数据
     */
    private void sendFileInChunks(ServerPlayer player, String songName, String fileName,
                                  String md5, File file, long offsetMs) throws IOException {
        long fileLength = file.length();
        int totalChunks = (int) Math.ceil((double) fileLength / S2CSongDataPacket.MAX_CHUNK_SIZE);

        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[S2CSongDataPacket.MAX_CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                byte[] chunkData;
                if (bytesRead == buffer.length) {
                    chunkData = buffer;
                } else {
                    chunkData = Arrays.copyOf(buffer, bytesRead);
                }
                boolean last = (chunkIndex == totalChunks - 1);
                // 附带服务器当前播放进度，客户端据此对齐播放偏移（边下边播时尤为重要）
                long elapsed = playing ? getElapsedMs() : 0;
                S2CSongDataPacket packet = new S2CSongDataPacket(
                        songName, fileName, md5, chunkIndex, totalChunks, chunkData, last, elapsed);
                NetworkHandler.sendToPlayer(packet, player);

                chunkIndex++;
                // 注：Forge 网络层自带拥塞控制与分帧，无需人工限速
            }
        }
        MusicMod.LOGGER.info("Sent song '{}' to player {} ({} chunks, {} bytes)",
                songName, player.getName().getString(), totalChunks, fileLength);
    }

    /**
     * 新玩家加入服务器时同步当前播放状态
     */
    public void onPlayerJoined(ServerPlayer player) {
        // 因服务器无人而暂停的音乐：有玩家加入时自动恢复（0.17.0）
        if (pausedByEmptyServer && playing && paused) {
            pausedByEmptyServer = false;
            paused = false;
            playStartTime = System.currentTimeMillis() - pausedAtMs;
            totalPausedDuration = 0;
            MusicMod.LOGGER.info("Player joined, resuming music paused by empty server at {}ms", pausedAtMs);
        }
        if (playing) {
            long offsetMs = getElapsedMs();
            NetworkHandler.sendToPlayer(new S2CPlayPacket(
                    currentSong, currentFileName, currentMd5, currentFileSize, offsetMs), player);
            // 0.25.2：音乐处于手动暂停状态（非无人暂停）时，新玩家同步暂停而非开始播放
            if (paused) {
                NetworkHandler.sendToPlayer(new S2CPausePacket(true, pausedAtMs), player);
            }
        }
        // 同步当前无人时行为设置给新玩家（0.25.0）
        NetworkHandler.sendToPlayer(new S2CEmptyBehaviorPacket(
                MusicModConfig.getServerEmptyServerBehavior()), player);
        // 同步歌单列表给新玩家
        sendPlaylistsToPlayer(player);
    }

    /**
     * 玩家离开服务器时清理传输状态，并按配置处理服务器无人时的音乐行为（0.17.0）
     */
    public void onPlayerLeft(ServerPlayer player) {
        UUID playerId = player.getUUID();
        transferringPlayers.remove(playerId);
        playerTransferringSong.remove(playerId);
        cleanupUploadSession(playerId);

        // 服务器已无人时的音乐行为（0.25.1 修复：PlayerLoggedOutEvent 触发时离开的玩家仍在列表中，
        // 所以此处计数为 1 表示该玩家是最后一人；计数为 0 是部分环境已移除的情况，两种都处理）
        if (playing && server != null && server.getPlayerList().getPlayerCount() <= 1) {
            com.musicmod.EmptyServerBehavior behavior = MusicModConfig.getServerEmptyServerBehavior();
            switch (behavior) {
                case STOP:
                    MusicMod.LOGGER.info("Server is empty, stopping music (emptyServerBehavior=STOP)");
                    stopMusic();
                    break;
                case PAUSE:
                    if (!paused) {
                        // 同样必须先记录当前进度再置 paused，否则记录到的会是旧值（首次为 0），
                        // 玩家重新进入时会从歌曲开头播放。
                        pausedAtMs = getElapsedMs();
                        paused = true;
                        pausedByEmptyServer = true;
                        MusicMod.LOGGER.info("Server is empty, pausing music at {}ms (emptyServerBehavior=PAUSE)", pausedAtMs);
                    }
                    break;
                case CONTINUE:
                default:
                    // 继续播放，不处理
                    break;
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 计算文件MD5（带缓存）
     */
    public String computeMd5(File file) {
        String path = file.getAbsolutePath();
        long lastMod = file.lastModified();
        String[] cached = md5Cache.get(path);
        if (cached != null && Long.parseLong(cached[1]) == lastMod) {
            return cached[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String result = sb.toString();
            md5Cache.put(path, new String[]{result, String.valueOf(lastMod)});
            return result;
        } catch (Exception e) {
            MusicMod.LOGGER.error("Failed to compute MD5 for {}", file.getName(), e);
            return String.valueOf(file.lastModified()); // 降级方案：用修改时间戳
        }
    }

    private String getSongDisplayName(File file) {
        return MusicLibrary.getSongName(file);
    }

    /**
     * 向所有在线玩家发送聊天消息
     */
    public void broadcastMessage(String message) {
        if (server == null) return;
        net.minecraft.network.chat.Component component =
                net.minecraft.network.chat.Component.literal(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(component);
        }
    }

    /**
     * 向指定玩家发送消息
     */
    public void sendMessageTo(ServerPlayer player, String message) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    // ==================== 上传管理 ====================

    /**
     * 收到客户端上传的分块数据。
     * 仅 OP 可上传；按玩家维度维护上传会话，最后一块时保存到歌曲目录并重新扫描。
     */
    public void onUploadChunkReceived(ServerPlayer player, String fileName, int chunkIndex,
                                      int totalChunks, byte[] data, boolean lastChunk) {
        UUID playerId = player.getUUID();

        // 权限校验：仅 OP 可上传
        if (!player.hasPermissions(2)) {
            S2CUploadResultPacket.sendTo(player, false, fileName, Lang.tr("需要管理员权限"));
            cleanupUploadSession(playerId);
            return;
        }

        // 第一块：校验扩展名并创建上传会话
        if (chunkIndex == 0) {
            String ext = getExtension(fileName);
            if (!MusicLibrary.SUPPORTED_EXTENSIONS.contains(ext)) {
                S2CUploadResultPacket.sendTo(player, false, fileName, Lang.tr("不支持的文件格式: ") + ext);
                return;
            }
            if (totalChunks <= 0) {
                S2CUploadResultPacket.sendTo(player, false, fileName, Lang.tr("无效的数据块数"));
                return;
            }
            // 清理可能存在的旧会话（同一玩家重复上传）
            cleanupUploadSession(playerId);
            UploadSession session = new UploadSession(fileName, totalChunks);
            uploadSessions.put(playerId, session);
            MusicMod.LOGGER.info("Player {} started uploading '{}' ({} chunks)",
                    player.getName().getString(), fileName, totalChunks);
        }

        UploadSession session = uploadSessions.get(playerId);
        if (session == null) {
            S2CUploadResultPacket.sendTo(player, false, fileName, Lang.tr("上传会话不存在，请重新上传"));
            return;
        }

        // 大小校验
        if (session.receivedBytes + data.length > MAX_UPLOAD_SIZE) {
            S2CUploadResultPacket.sendTo(player, false, fileName, Lang.tr("文件超过大小上限(100MB)"));
            cleanupUploadSession(playerId);
            return;
        }

        // 顺序校验
        if (chunkIndex != session.nextExpectedIndex) {
            S2CUploadResultPacket.sendTo(player, false, fileName,
                    Lang.tr("数据块乱序(期望") + session.nextExpectedIndex + Lang.tr("，收到") + chunkIndex + ")");
            cleanupUploadSession(playerId);
            return;
        }

        session.appendData(data);
        session.nextExpectedIndex++;

        // 最后一块：保存文件并通知结果
        if (lastChunk || session.nextExpectedIndex >= totalChunks) {
            finalizeUpload(player, session);
        }
    }

    /**
     * 上传完成：将数据保存到歌曲目录，重新扫描并通知玩家
     */
    private void finalizeUpload(ServerPlayer player, UploadSession session) {
        UUID playerId = player.getUUID();
        try {
            if (musicLibrary == null || musicLibrary.getSongsDir() == null) {
                S2CUploadResultPacket.sendTo(player, false, session.fileName, Lang.tr("服务器歌曲目录未初始化"));
                cleanupUploadSession(playerId);
                return;
            }

            File songsDir = musicLibrary.getSongsDir();
            if (!songsDir.exists() && !songsDir.mkdirs()) {
                S2CUploadResultPacket.sendTo(player, false, session.fileName, Lang.tr("无法创建歌曲目录"));
                cleanupUploadSession(playerId);
                return;
            }

            // 防路径穿越：仅取文件名部分
            String safeName = new File(session.fileName).getName();
            File targetFile = new File(songsDir, safeName);

            // 同名文件覆盖提示（直接覆盖，因为 OP 有意上传）
            java.nio.file.Files.write(targetFile.toPath(), session.getData());
            MusicMod.LOGGER.info("Player {} uploaded '{}' to server ({} bytes)",
                    player.getName().getString(), safeName, session.receivedBytes);

            // 重新扫描音乐库
            rescanLibrary();

            // 通知上传者成功
            S2CUploadResultPacket.sendTo(player, true, safeName,
                    String.format("%.1f MB", session.receivedBytes / 1024.0 / 1024.0));

            // 广播通知全服
            broadcastMessage(Lang.tr("§6♪ §e管理员 §f") + player.getName().getString()
                    + Lang.tr(" §e上传了新歌曲: §f") + safeName);

        } catch (IOException e) {
            MusicMod.LOGGER.error("Failed to save uploaded file '{}'", session.fileName, e);
            S2CUploadResultPacket.sendTo(player, false, session.fileName, Lang.tr("保存文件失败: ") + e.getMessage());
        } finally {
            cleanupUploadSession(playerId);
        }
    }

    /**
     * 清理指定玩家的上传会话
     */
    private void cleanupUploadSession(UUID playerId) {
        UploadSession session = uploadSessions.remove(playerId);
        if (session != null) {
            session.clear();
        }
    }

    /**
     * 清理所有上传会话（服务器关闭时）
     */
    private void cleanupAllUploadSessions() {
        for (UploadSession session : uploadSessions.values()) {
            session.clear();
        }
        uploadSessions.clear();
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 上传会话：缓存单个玩家上传的分块数据。
     * 音乐文件通常 3-10MB，100MB 上限下内存缓冲可接受。
     */
    private static class UploadSession {
        final String fileName;
        final int totalChunks;
        int nextExpectedIndex;
        long receivedBytes;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        UploadSession(String fileName, int totalChunks) {
            this.fileName = fileName;
            this.totalChunks = totalChunks;
            this.nextExpectedIndex = 0;
            this.receivedBytes = 0;
        }

        void appendData(byte[] data) {
            buffer.write(data, 0, data.length);
            receivedBytes += data.length;
        }

        byte[] getData() {
            return buffer.toByteArray();
        }

        void clear() {
            buffer.reset();
        }
    }
}