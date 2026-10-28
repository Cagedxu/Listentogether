package com.musicmod.client;

import com.musicmod.MusicMod;
import com.musicmod.PlayMode;
import com.musicmod.audio.AudioEngine;
import com.musicmod.audio.SongBufferInputStream;
import com.musicmod.config.MusicModConfig;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SRequestSongDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端音乐管理器（单例）。
 * 负责接收服务器指令、管理本地缓存、控制音频播放。
 */
public class ClientMusicManager {
    private static final ClientMusicManager INSTANCE = new ClientMusicManager();

    private final AudioEngine audioEngine;
    private File cacheDir;

    // 当前播放信息
    private String pendingSongName = "";
    private String pendingFileName = "";
    private String pendingMd5 = "";
    private long pendingOffsetMs = 0;

    // 文件接收状态
    private final Map<String, FileReceiveState> receiveStates = new ConcurrentHashMap<>();

    /** 边下边播的预缓冲阈值：收到这么多字节后立即开始播放（约3~4个数据块） */
    private static final int PREBUFFER_BYTES = 100_000;

    /**
     * MP4/M4A 容器需要随机访问（moov box 解析），无法从网络流播放。
     * 此类格式跳过边下边播，下载完后从缓存文件播放。
     */
    private static boolean isStreamUnsupportedFormat(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase();
        return name.endsWith(".m4a") || name.endsWith(".mp4") || name.endsWith(".aac");
    }

    // 歌曲列表缓存
    private List<String> lastSongList = List.of();
    private List<String> lastQueue = List.of();
    private String lastCurrentSong = "";
    private boolean lastPlaying = false;
    private boolean lastPaused = false;
    private PlayMode lastPlayMode = PlayMode.SEQUENTIAL;
    private com.musicmod.EmptyServerBehavior lastEmptyBehavior = com.musicmod.EmptyServerBehavior.CONTINUE;

    // 最近播放记录（本地持久化，最多 50 首，供内置「最近播放」歌单使用）
    private static final int MAX_RECENT = 50;
    private static final String RECENT_FILE_NAME = "recent_played.txt";
    private final java.util.List<String> recentPlayed = new java.util.ArrayList<>();

    public static class FileReceiveState {
        final String songName;
        final String fileName;
        final String md5;
        final int totalChunks;
        final AtomicInteger receivedChunks = new AtomicInteger(0);
        final File tempFile;
        final SongBufferInputStream bufferStream;  // 边下边播的内存缓冲流
        final FileOutputStream fileOut;            // 同时落盘做缓存
        volatile boolean playbackStarted = false;  // 是否已开始播放
        volatile long latestServerElapsedMs = 0;   // 最近收到的服务器播放进度

        FileReceiveState(String songName, String fileName, String md5,
                         int totalChunks, File tempFile) throws FileNotFoundException {
            this.songName = songName;
            this.fileName = fileName;
            this.md5 = md5;
            this.totalChunks = totalChunks;
            this.tempFile = tempFile;
            this.bufferStream = new SongBufferInputStream();
            this.fileOut = new FileOutputStream(tempFile);
        }
    }

    private ClientMusicManager() {
        audioEngine = new AudioEngine();
        audioEngine.setListener(new AudioEngine.PlaybackListener() {
            @Override
            public void onPlaybackFinished(String songName) {
                MusicMod.LOGGER.info("Song finished: {}", songName);
                // 播放完毕，服务器端会自动处理下一首（通过客户端通知）
                // 这里不需要额外处理，服务器有自己的计时逻辑
            }

            @Override
            public void onPlaybackError(String songName, String error) {
                MusicMod.LOGGER.error("Playback error for {}: {}", songName, error);
            }
        });
    }

    public static ClientMusicManager getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化客户端（在客户端设置事件中调用）
     */
    public void init() {
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc.gameDirectory.toPath();
        cacheDir = gameDir.resolve("musicmod_cache").toFile();
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        // 载入本地最近播放记录（内置「最近播放」歌单）
        loadRecentPlayed();
        // 应用 Forge 客户端配置中保存的个人音量
        audioEngine.setVolume(MusicModConfig.getClientVolume());
    }

    // ==================== 服务器指令处理 ====================

    /**
     * 收到播放指令
     */
    public void onPlayRequest(String songName, String fileName, String md5, long fileSize, long offsetMs) {
        this.pendingSongName = songName;
        this.pendingFileName = fileName;
        this.pendingMd5 = md5;
        this.pendingOffsetMs = offsetMs;

        // 设置回退时长：MP3/OGG/FLAC 经 SPI 转换后流长度未知，
        // 用服务器下发的标签时长保证进度条可用
        int durSec = ClientTagStore.getInstance().durationSec(songName);
        audioEngine.setFallbackDurationMs(durSec > 0 ? durSec * 1000L : -1);

        // 记录最近播放（同时持久化，供内置「最近播放」歌单使用）
        recordRecent(songName);

        // 本地游戏（单人/LAN主机）：直接从服务器歌曲文件夹读取，不走网络流
        File localFile = findLocalSongFile(fileName, fileSize);
        if (localFile != null) {
            MusicMod.LOGGER.info("Local game: playing directly from songs dir: {}", localFile.getPath());
            playFromCache(localFile, songName, offsetMs);
            return;
        }

        // 检查本地缓存
        File cachedFile = getCachedFile(md5, fileName);
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() == fileSize) {
            // 缓存命中，直接播放
            MusicMod.LOGGER.info("Cache hit for song: {} (md5={})", songName, md5);
            playFromCache(cachedFile, songName, offsetMs);
        } else {
            // 缓存未命中，请求文件数据
            MusicMod.LOGGER.info("Cache miss for song: {}, requesting from server", songName);
            audioEngine.stop();
            NetworkHandler.sendToServer(new C2SRequestSongDataPacket(songName, offsetMs));
            showChatMessage(com.musicmod.i18n.Lang.tr("§6♪ §e正在下载歌曲: §f") + songName + com.musicmod.i18n.Lang.tr("§e..."));
        }
    }

    /**
     * 收到停止指令
     */
    public void onStop() {
        audioEngine.stop();
        pendingSongName = "";
        pendingFileName = "";
        pendingMd5 = "";
        pendingOffsetMs = 0;
    }

    /**
     * 收到暂停/恢复指令
     */
    public void onPauseToggle(boolean paused, long offsetMs) {
        audioEngine.setPaused(paused);
    }

    /**
     * 收到歌曲列表
     */
    public void onSongListReceived(List<String> songs, List<String> queue, String currentSong,
                                   boolean isPlaying, boolean isPaused,
                                   PlayMode playMode) {
        this.lastSongList = songs;
        this.lastQueue = queue == null ? List.of() : queue;
        this.lastCurrentSong = currentSong;
        this.lastPlaying = isPlaying;
        this.lastPaused = isPaused;
        this.lastPlayMode = playMode;
    }

    /**
     * 收到歌曲文件数据块（边下边播：数据写入内存缓冲流，攒够预缓冲后立即开始播放）
     */
    public void onSongDataReceived(String songName, String fileName, String md5,
                                   int chunkIndex, int totalChunks, byte[] data, boolean lastChunk,
                                   long serverElapsedMs) {
        String stateKey = md5 + "_" + fileName;
        FileReceiveState state = receiveStates.get(stateKey);

        if (state == null) {
            // 新的文件传输
            try {
                File tempFile = new File(cacheDir, md5 + ".tmp");
                // 如果临时文件已存在，删除重建
                if (tempFile.exists()) tempFile.delete();
                state = new FileReceiveState(songName, fileName, md5, totalChunks, tempFile);
                receiveStates.put(stateKey, state);
            } catch (FileNotFoundException e) {
                MusicMod.LOGGER.error("Failed to create temp file for song transfer", e);
                return;
            }
        }

        // 数据同时写入内存缓冲（供播放）和临时文件（供缓存）
        state.bufferStream.writeChunk(data);
        try {
            state.fileOut.write(data);
        } catch (IOException e) {
            // 缓存落盘失败不影响播放，仅记录
            MusicMod.LOGGER.warn("Failed to write song chunk to cache file", e);
        }
        state.receivedChunks.incrementAndGet();
        state.latestServerElapsedMs = serverElapsedMs;

        // 预缓冲足够且尚未开始播放 → 立即开始边下边播。
        // M4A/MP4/AAC 需要随机访问，等下载完成后从文件播放。
        if (!isStreamUnsupportedFormat(fileName)
                && !state.playbackStarted && state.bufferStream.getBufferedBytes() >= PREBUFFER_BYTES) {
            state.playbackStarted = true;
            playFromStream(state.bufferStream, songName, fileName, state.latestServerElapsedMs);
            showChatMessage(com.musicmod.i18n.Lang.tr("§6♪ §a缓冲完成，开始播放: §f") + songName);
        }

        if (lastChunk || state.receivedChunks.get() >= totalChunks) {
            state.bufferStream.setWriteComplete();
            try {
                state.fileOut.close();
            } catch (IOException ignored) {}
            receiveStates.remove(stateKey);

            // 保存为正式缓存文件
            File finalFile = new File(cacheDir, md5 + "_" + fileName);
            try {
                Files.move(state.tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                MusicMod.LOGGER.info("Song file saved to cache: {} ({} bytes)", finalFile.getName(), finalFile.length());
            } catch (IOException e) {
                MusicMod.LOGGER.error("Failed to move temp file to cache", e);
            }

            File playFile = finalFile.exists() ? finalFile : state.tempFile;
            if (!state.playbackStarted) {
                state.playbackStarted = true;
                playFromCache(playFile, songName, state.latestServerElapsedMs);
                showChatMessage(com.musicmod.i18n.Lang.tr("§6♪ §a下载完成，开始播放: §f") + songName);
            } else {
                // 流式播放可能因格式误判/解码失败提前结束：稍等确认，
                // 如果音频引擎没有在播也没有暂停，则从缓存文件补播。
                scheduleStreamFallback(songName, playFile, state.latestServerElapsedMs);
            }
        }
    }

    // ==================== 播放控制 ====================

    /** 流式播放未真正开始时，从完整缓存文件补播，避免“下载完成但没声音”。 */
    private void scheduleStreamFallback(String songName, File file, long offsetMs) {
        Thread check = new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                return;
            }
            Minecraft.getInstance().execute(() -> {
                // 已经切歌/停止，或手动暂停：不要覆盖用户当前状态
                if (!songName.equals(pendingSongName)) return;
                String current = audioEngine.getCurrentSongName();
                if (audioEngine.isPlaying() && songName.equals(current)) return;
                if (audioEngine.isPaused() && songName.equals(current)) return;
                if (audioEngine.isPlaying() && !songName.equals(current)) return;
                playFromCache(file, songName, offsetMs);
                showChatMessage(com.musicmod.i18n.Lang.tr("§6♪ §a已切换为文件播放: §f") + songName);
            });
        }, "MusicMod-StreamFallback");
        check.setDaemon(true);
        check.start();
    }

    private void playFromCache(File file, String songName, long offsetMs) {
        // 在主线程外执行播放（音频播放是阻塞操作）
        Thread playThread = new Thread(() -> {
            audioEngine.play(file, songName, offsetMs, MusicModConfig.getClientVolume());
            syncStreamDurationToTags(songName);
        }, "MusicMod-Play");
        playThread.setDaemon(true);
        playThread.start();
    }

    /**
     * 从内存缓冲流播放（边下边播：数据不足时解码线程阻塞等待网络数据）
     */
    private void playFromStream(InputStream stream, String songName, String fileName, long offsetMs) {
        Thread playThread = new Thread(() -> {
            audioEngine.play(stream, songName, fileName, offsetMs, MusicModConfig.getClientVolume());
            syncStreamDurationToTags(songName);
        }, "MusicMod-Play");
        playThread.setDaemon(true);
        playThread.start();
    }

    /**
     * 播放开始后将 AudioEngine 从音频流获取的时长写回 ClientTagStore。
     * 服务端 TagReader 对部分格式（如某些 M4A/WAV）解析时长失败导致列表不显示时长，
     * 但客户端播放时 SPI 可从流 frameLength 获取准确时长，补写后列表/进度条均可正常显示。
     */
    private void syncStreamDurationToTags(String songName) {
        try {
            long dur = audioEngine.getSongDurationMs();
            if (dur > 0) {
                ClientTagStore.getInstance().updateDurationIfUnknown(songName, (int) (dur / 1000));
            }
        } catch (Exception ignored) {}
    }

    /**
     * 设置个人音量
     */
    public void setVolume(float vol) {
        float clamped = Math.max(0.0f, Math.min(1.0f, vol));
        MusicModConfig.setClientVolume(clamped);
        MusicModConfig.saveClientConfig();
        audioEngine.setVolume(clamped);
        // 向玩家显示反馈
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    com.musicmod.i18n.Lang.tr("§6♪ §e个人音量已设置为: §f") + Math.round(clamped * 100) + "%"), false);
        }
    }

    public float getVolume() {
        return MusicModConfig.getClientVolume();
    }

    public AudioEngine getAudioEngine() {
        return audioEngine;
    }

    public boolean isPlaying() {
        return audioEngine.isPlaying();
    }

    public boolean isPaused() {
        return audioEngine.isPaused();
    }

    public String getCurrentSong() {
        return audioEngine.getCurrentSongName();
    }

    // ==================== 缓存管理 ====================

    /**
     * 本地游戏（单人/LAN主机）时，直接从服务器歌曲文件夹查找文件。
     * 客户端与集成服务器在同一 JVM，歌曲文件夹可直接访问，无需走网络流。
     * @return 找到的文件，或 null（非本地游戏/文件不存在/大小不匹配）
     */
    private File findLocalSongFile(String fileName, long expectedSize) {
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) return null; // 连接专用服务器，非本地游戏

            String songsDirPath = MusicModConfig.getServerSongsDir();
            File songsDir = server.getServerDirectory().toPath().resolve(songsDirPath).toFile();
            if (!songsDir.exists()) return null;

            File songFile = new File(songsDir, fileName);
            if (songFile.exists() && songFile.isFile()) {
                // 大小校验通过则直接使用（文件可能被修改过，大小不一致时回退网络流）
                if (expectedSize <= 0 || songFile.length() == expectedSize) {
                    return songFile;
                }
                MusicMod.LOGGER.warn("Local song file size mismatch for {}: expected {} but found {}, falling back to network",
                        fileName, expectedSize, songFile.length());
            }
            return null;
        } catch (Exception e) {
            MusicMod.LOGGER.debug("Failed to find local song file '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * 获取缓存文件
     */
    private File getCachedFile(String md5, String fileName) {
        File cached = new File(cacheDir, md5 + "_" + fileName);
        if (cached.exists()) return cached;
        // 兼容旧命名格式（只有MD5）
        File[] files = cacheDir.listFiles((dir, name) -> name.startsWith(md5));
        if (files != null && files.length > 0) return files[0];
        return null;
    }

    /**
     * 清理所有缓存
     */
    public void clearCache() {
        if (cacheDir == null || !cacheDir.exists()) return;
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) {
                MusicMod.LOGGER.warn("Failed to delete cache file: {}", file.getName());
            }
        }
        MusicMod.LOGGER.info("Music cache cleared");
    }

    // ==================== 配置（委托 Forge 配置系统） ====================

    public PlayMode getLastPlayMode() {
        return lastPlayMode;
    }

    /** 收到服务器广播的播放模式变更 */
    public void onPlayModeReceived(PlayMode playMode) {
        this.lastPlayMode = playMode;
    }

    public com.musicmod.EmptyServerBehavior getLastEmptyBehavior() {
        return lastEmptyBehavior;
    }

    /** 收到服务器广播的无人时行为变更（或本地乐观更新） */
    public void onEmptyBehaviorReceived(com.musicmod.EmptyServerBehavior behavior) {
        this.lastEmptyBehavior = behavior;
    }

    // ==================== 工具方法 ====================

    private void showChatMessage(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    public List<String> getLastQueue() {
        return lastQueue;
    }

    public List<String> getLastSongList() {
        return lastSongList;
    }

    /** 最近播放记录（最新在前） */
    public List<String> getRecentPlayed() {
        return java.util.Collections.unmodifiableList(recentPlayed);
    }

    /** 记录一首最近播放歌曲：去重后前插，最多保留 {@link #MAX_RECENT} 首并落盘 */
    public void recordRecent(String songName) {
        if (songName == null || songName.isEmpty()) return;
        recentPlayed.remove(songName);
        recentPlayed.add(0, songName);
        while (recentPlayed.size() > MAX_RECENT) {
            recentPlayed.remove(recentPlayed.size() - 1);
        }
        saveRecentPlayed();
    }

    /** 从最近播放中移除一首（内置「最近播放」歌单的 ✕ 按钮） */
    public void removeRecent(String songName) {
        if (songName == null || songName.isEmpty()) return;
        if (recentPlayed.remove(songName)) {
            saveRecentPlayed();
        }
    }

    /** 清空最近播放 */
    public void clearRecent() {
        if (recentPlayed.isEmpty()) return;
        recentPlayed.clear();
        saveRecentPlayed();
    }

    private void loadRecentPlayed() {
        recentPlayed.clear();
        if (cacheDir == null) return;
        File file = new File(cacheDir, RECENT_FILE_NAME);
        if (!file.isFile()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String song = line.trim();
                if (song.isEmpty() || recentPlayed.contains(song)) continue;
                recentPlayed.add(song);
                if (recentPlayed.size() >= MAX_RECENT) break;
            }
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to load recent played songs: " + e);
        }
    }

    private void saveRecentPlayed() {
        if (cacheDir == null) return;
        try {
            Files.createDirectories(cacheDir.toPath());
            StringBuilder sb = new StringBuilder();
            for (String song : recentPlayed) {
                sb.append(song.replace('\n', ' ')).append('\n');
            }
            Files.write(new File(cacheDir, RECENT_FILE_NAME).toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to save recent played songs: " + e);
        }
    }

    public com.musicmod.config.MusicModConfig.HudMode getHudMode() {
        return MusicModConfig.getHudMode();
    }

    public void setHudMode(com.musicmod.config.MusicModConfig.HudMode mode) {
        MusicModConfig.setHudMode(mode);
        MusicModConfig.saveClientConfig();
    }
}