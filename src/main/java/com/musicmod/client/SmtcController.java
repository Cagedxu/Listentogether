package com.musicmod.client;

import com.musicmod.MusicMod;
import com.musicmod.audio.SongTags;
import com.musicmod.audio.TagReader;
import com.musicmod.client.smtc.JMTC;
import com.musicmod.client.smtc.JMTCEnabledButtons;
import com.musicmod.client.smtc.JMTCMusicProperties;
import com.musicmod.client.smtc.JMTCPlayingState;
import com.musicmod.client.smtc.JMTCTimelineProperties;
import com.musicmod.client.smtc.windows.WindowsJMTC;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SMusicActionPacket;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows System Media Transport Controls bridge.
 *
 * <p>The JMTC Windows implementation is vendored under {@code com.musicmod.client.smtc}
 * and uses the bundled {@code SMTCAdapter.dll}. Everything is defensive: unsupported
 * platforms or missing native libraries simply disable the integration.</p>
 */
public final class SmtcController {
    private static boolean initialized;
    private static boolean disabled;
    private static volatile boolean ready;
    private static Thread worker;
    private static final BlockingQueue<Runnable> TASKS = new LinkedBlockingQueue<>();
    private static volatile boolean running;
    private static JMTC jmtc;
    private static volatile ClientTagStore.SongInfo pendingInfo;
    private static volatile String pendingSong;
    private static volatile long pendingDuration;
    private static final AtomicBoolean mediaUpdateQueued = new AtomicBoolean();
    private static String lastSong = "";
    private static String lastTitle;
    private static String lastArtist;
    private static String lastAlbum;
    private static long lastDuration = -1;
    private static boolean lastPausedState;
    private static boolean lastPlayingState;
    private static File coverFile;
    private static int failureCount;
    private static long lastStateSubmitMs;
    private static boolean sessionPrimed;

    private SmtcController() {
    }

    public static void tick() {
        if (disabled) return;
        if (!initialized) init();
        if (worker == null || !ready) return;

        ClientMusicManager manager = ClientMusicManager.getInstance();
        String song = manager.getCurrentSong();
        if (song == null || song.isBlank()) {
            if (!lastSong.isEmpty()) {
                lastSong = "";
                        submit(() -> {
                    if (!ready || jmtc == null) return;
                    jmtc.setPlayingState(JMTCPlayingState.STOPPED);
                    jmtc.resetDisplay();
                });
            }
            return;
        }

        try {
            ClientTagStore.SongInfo info = ClientTagStore.getInstance().getSongInfo(song);
            if (info == null) {
                ClientTagStore.getInstance().requestSongInfo(song);
                return;
            }

            long duration = info.durationMs > 0
                    ? info.durationMs
                    : manager.getAudioEngine().getSongDurationMs();
            boolean changed = !song.equals(lastSong)
                    || !info.title.equals(lastTitle)
                    || !info.artist.equals(lastArtist)
                    || !info.album.equals(lastAlbum)
                    || duration != lastDuration;
            long position = Math.max(0, manager.getAudioEngine().getCurrentPositionMs());
            boolean paused = manager.isPaused();
            boolean playing = manager.isPlaying();

            if (changed) {
                lastSong = song;
                lastTitle = info.title;
                lastArtist = info.artist;
                lastAlbum = info.album;
                lastDuration = duration;
                pendingInfo = info;
                pendingSong = song;
                pendingDuration = duration;
                if (mediaUpdateQueued.compareAndSet(false, true)) {
                    submit(() -> {
                        try {
                            ClientTagStore.SongInfo latest = pendingInfo;
                            String latestSong = pendingSong;
                            long latestDuration = pendingDuration;
                            if (latest != null && ready && jmtc != null) {
                                updateMedia(latest, latestSong, latestDuration);
                            }
                        } finally {
                            mediaUpdateQueued.set(false);
                        }
                    });
                }
            }
            long now = System.currentTimeMillis();
            // 原生 SMTC 调用很重：每帧提交会堆积任务，拖慢 COM 消息泵。
            // 仅在换歌/暂停状态变化时立即刷新，其余最多每 500ms 更新一次进度。
            boolean stateChanged = paused != lastPausedState || playing != lastPlayingState;
            if (changed || stateChanged || now - lastStateSubmitMs >= 500L) {
                lastStateSubmitMs = now;
                lastPausedState = paused;
                lastPlayingState = playing;
                submit(() -> {
                    if (!ready || jmtc == null) return;
                    if (duration > 0) {
                        jmtc.setTimelineProperties(new JMTCTimelineProperties(0L, duration, 0L, duration));
                    }
                    jmtc.setPosition(position);
                    // 短暂缓冲/seek 时不要报 STOPPED：Windows 会把整个媒体会话从面板上移除。
                    jmtc.setPlayingState(paused || !playing
                            ? JMTCPlayingState.PAUSED
                            : JMTCPlayingState.PLAYING);
                    jmtc.updateDisplay();
                });
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void init() {
        initialized = true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            disabled = true;
            return;
        }
        running = true;
        worker = new Thread(SmtcController::loop, "musicmod-smtc");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop() {
        initNative();
        while (running || !TASKS.isEmpty()) {
            try {
                Runnable task = TASKS.poll(15, TimeUnit.MILLISECONDS);
                if (task != null) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        fail(t);
                    }
                }
                pumpMessages();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            if (jmtc != null) {
                jmtc.setEnabled(false);
                jmtc.resetDisplay();
            }
        } catch (Throwable ignored) {
        }
        jmtc = null;
        ready = false;
    }

    private static void pumpMessages() {
        WinUser.MSG msg = new WinUser.MSG();
        while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0x0001)) {
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }
    }

    private static void submit(Runnable task) {
        if (running) TASKS.offer(task);
    }

    private interface Shell32Aumid extends Library {
        Shell32Aumid INSTANCE = Native.load("shell32", Shell32Aumid.class);
        int SetCurrentProcessExplicitAppUserModelID(WString appId);
    }

    private static void initNative() {
        try {
            try {
                Shell32Aumid.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString("Minecraft"));
            } catch (Throwable ignored) {
            }
            jmtc = new WindowsJMTC();
            jmtc.setEnabledButtons(new JMTCEnabledButtons(true, true, false, true, true));
            jmtc.setMediaType(com.musicmod.client.smtc.JMTCMediaType.Music);
            jmtc.setPlayingState(JMTCPlayingState.PAUSED);
            jmtc.setEnabled(true);
            com.musicmod.client.smtc.JMTCCallbacks callbacks = new com.musicmod.client.smtc.JMTCCallbacks();
            callbacks.onPlay = () -> dispatch(SmtcController::sendPauseIfPaused);
            callbacks.onPause = () -> dispatch(SmtcController::sendPauseIfPlaying);
            callbacks.onNext = () -> dispatch(() -> sendAction(C2SMusicActionPacket.ACTION_NEXT));
            callbacks.onPrevious = () -> dispatch(() -> sendAction(C2SMusicActionPacket.ACTION_PREV));
            callbacks.onStop = () -> dispatch(() -> sendAction(C2SMusicActionPacket.ACTION_STOP));
            jmtc.setCallbacks(callbacks);
            jmtc.updateDisplay();
            ready = true;
            MusicMod.LOGGER.info("SMTC initialized");
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void updateMedia(ClientTagStore.SongInfo info, String song, long duration) {
        File art = null;
        byte[] coverBytes = info.coverJpeg;
        String coverExt = ".jpg";
        // Prefer the full-resolution artwork from the downloaded/local file.
        // The packet cover is deliberately downsized for the network.
        try {
            ClientMusicManager manager = ClientMusicManager.getInstance();
            File cached = manager.findCachedFile(song);
            if (cached == null) cached = manager.getCurrentPlaybackFile();
            if (cached != null) {
                SongTags tags = TagReader.read(cached);
                if (tags.coverImage != null && tags.coverImage.length > coverBytes.length) {
                    coverBytes = tags.coverImage;
                    coverExt = coverExtension(tags.coverImage);
                }
            }
        } catch (Throwable t) {
            MusicMod.LOGGER.debug("SMTC full cover read failed", t);
        }
        if (coverBytes != null && coverBytes.length > 0) {
            try {
                Path p = Files.createTempFile("musicmod-smtc-", coverExt);
                coverFile = p.toFile();
                coverFile.deleteOnExit();
                Files.write(coverFile.toPath(), coverBytes);
                art = coverFile;
            } catch (Throwable t) {
                MusicMod.LOGGER.debug("SMTC cover write failed", t);
            }
        }

        String title = info.title.isBlank() ? stripExtension(song) : info.title;
        String artist = info.artist.isBlank() ? "未知" : info.artist;
        String album = info.album.isBlank() ? "" : info.album;
        JMTCMusicProperties props = new JMTCMusicProperties(
                title, artist, album, artist, new String[0], 0, 0, art);
        // Re-arm the native session for every song change. Some Windows
        // builds keep showing the previous card if the session was created
        // before the new metadata was applied.
        jmtc.setEnabled(false);
        jmtc.setEnabled(true);
        jmtc.setMediaProperties(props);
        jmtc.updateDisplay();
        sessionPrimed = true;
        MusicMod.LOGGER.info("SMTC media: {} - {}", title, artist);
    }

    /** 原生回调不能直接做网络 I/O，转回客户端主线程。 */
    private static void dispatch(Runnable action) {
        if (!ready) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            action.run();
            return;
        }
        if (mc.isSameThread()) {
            action.run();
        } else {
            mc.execute(() -> {
                if (ready) action.run();
            });
        }
    }

    private static void sendAction(byte action) {
        try {
            NetworkHandler.sendToServer(new C2SMusicActionPacket(action, ""));
        } catch (Throwable t) {
            MusicMod.LOGGER.debug("SMTC action send failed", t);
        }
    }

    private static void sendPauseIfPaused() {
        ClientMusicManager m = ClientMusicManager.getInstance();
        if (m.isPaused()) sendAction(C2SMusicActionPacket.ACTION_PAUSE);
    }

    private static void sendPauseIfPlaying() {
        ClientMusicManager m = ClientMusicManager.getInstance();
        if (m.isPlaying() && !m.isPaused()) sendAction(C2SMusicActionPacket.ACTION_PAUSE);
    }

    public static void shutdown() {
        running = false;
        CountDownLatch done = new CountDownLatch(1);
        TASKS.offer(() -> {
            try {
                if (jmtc != null) {
                    jmtc.setEnabled(false);
                    jmtc.resetDisplay();
                }
            } catch (Throwable ignored) {
            } finally {
                done.countDown();
            }
        });
        Thread t = worker;
        if (t != null) {
            try {
                done.await(800, TimeUnit.MILLISECONDS);
                t.interrupt();
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        jmtc = null;
        ready = false;
        initialized = false;
        running = false;
        TASKS.clear();
        mediaUpdateQueued.set(false);
        sessionPrimed = false;
        pendingInfo = null;
        pendingSong = null;
        pendingDuration = 0;
        lastSong = "";
        lastTitle = null;
        lastArtist = null;
        lastAlbum = null;
        lastDuration = -1;
    }

    private static String coverExtension(byte[] data) {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50
                && data[2] == 0x4E && data[3] == 0x47) return ".png";
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return ".jpg";
        return ".jpg";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void fail(Throwable t) {
        failureCount++;
        if (failureCount <= 3) {
            MusicMod.LOGGER.warn("SMTC disabled", t);
        }
        disabled = true;
        ready = false;
        running = false;
        jmtc = null;
        TASKS.clear();
    }
}
