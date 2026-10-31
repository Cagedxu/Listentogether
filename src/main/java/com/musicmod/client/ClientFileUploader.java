package com.musicmod.client;

import com.musicmod.i18n.Lang;

import com.musicmod.audio.MusicLibrary;
import com.musicmod.config.MusicModConfig;
import com.musicmod.audio.SongTags;
import com.musicmod.audio.TagReader;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.C2SUploadSongPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.File;
import java.nio.file.Files;

/**
 * 客户端：管理员从本地上传音乐文件到服务器。
 * 优先使用系统文件选择器；若 JVM 处于无头模式，则打开独立的路径输入界面，
 * 避免 HeadlessException 导致上传功能完全不可用。
 */
public class ClientFileUploader {

    private static final long UPLOAD_BYTES_PER_SECOND = 2L * 1024 * 1024;

    /**
     * 打开上传入口：正常情况弹出系统文件选择器；无头环境回退到游戏内路径输入界面。
     */
    public static void openUploadDialog() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!isOp()) {
            mc.player.displayClientMessage(Component.literal(Lang.tr("§c[一起听] 需要管理员权限才能上传音乐")), false);
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            openPathInputScreen();
            return;
        }
        new Thread(ClientFileUploader::doUpload, "MusicMod-Upload").start();
    }

    /** 无头环境下的回退：打开游戏内路径输入界面 */
    private static void openPathInputScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.setScreen(new com.musicmod.client.gui.UploadPathScreen(mc.screen));
            }
        });
    }

    private static boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    private static void doUpload() {
        try {
            // 防御性设置：确保 AWT 非无头模式（Windows 默认即为 false，但保险起见）
            System.setProperty("java.awt.headless", "false");

            notifyMc(Lang.tr("§7[一起听] 正在打开文件选择器…"));

            File[] selected = chooseFiles();
            if (selected.length == 0) return; // 用户取消
            uploadLocalFiles(selected);
        } catch (HeadlessException he) {
            // 无头模式：回退到游戏内路径输入界面
            notifyMc(Lang.tr("§e[一起听] 系统文件选择器不可用，已切换到路径输入界面"));
            openPathInputScreen();
        } catch (Throwable t) {
            notifyMc(Lang.tr("§e[一起听] 无法打开系统文件选择器，已切换到路径输入界面: ") + t.getMessage());
            openPathInputScreen();
        }
    }

    /**
     * 使用 Windows 原生文件选择器；可见的置顶属主窗口确保对话框不会被游戏盖住。
     */
    private static File[] chooseFiles() {
        FileDialog fd = null;
        Frame owner = null;
        try {
            owner = new Frame();
            owner.setType(Frame.Type.UTILITY);
            owner.setUndecorated(true);
            owner.setSize(1, 1);
            owner.setLocation(-10000, -10000);
            owner.setAlwaysOnTop(true);
            owner.setVisible(true);

            fd = new FileDialog(owner, Lang.tr("选择要上传的音乐文件"), FileDialog.LOAD);
            fd.setMultipleMode(true);
            fd.setAlwaysOnTop(true);
            fd.setFilenameFilter((dir, name) -> {
                String lower = name.toLowerCase();
                for (String ext : MusicLibrary.SUPPORTED_EXTENSIONS) {
                    if (lower.endsWith("." + ext)) return true;
                }
                return false;
            });

            final FileDialog dlg = fd;
            Thread toFrontThread = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    java.awt.EventQueue.invokeLater(() -> {
                        try { dlg.toFront(); dlg.requestFocus(); } catch (Throwable ignored) {}
                    });
                    Thread.sleep(800);
                    java.awt.EventQueue.invokeLater(() -> {
                        try { dlg.toFront(); } catch (Throwable ignored) {}
                    });
                } catch (InterruptedException ignored) {}
            }, "MusicMod-FileDialog-ToFront");
            toFrontThread.setDaemon(true);
            toFrontThread.start();

            fd.setVisible(true); // 阻塞直到用户选择或取消

            File[] files = fd.getFiles();
            return files == null ? new File[0] : files;
        } catch (Throwable t) {
            notifyMc(Lang.tr("§e[一起听] 无法打开文件选择器: ") + t.getMessage());
            openPathInputScreen();
            return new File[0];
        } finally {
            if (fd != null) fd.dispose();
            if (owner != null) owner.dispose();
        }
    }

    /**
     * 上传指定本地文件（文件选择器与路径输入界面共用）。
     * 文件读取与分块发送在后台线程执行，不阻塞游戏渲染。
     */
    public static void uploadLocalFile(File file) {
        if (file == null) return;
        uploadLocalFiles(new File[]{file});
    }

    /** 多选时按顺序逐个上传，避免并发占满服务器带宽。 */
    public static void uploadLocalFiles(File[] files) {
        if (files == null || files.length == 0) return;
        File[] copy = files.clone();
        Thread thread = new Thread(() -> {
            for (File f : copy) {
                if (Thread.currentThread().isInterrupted()) break;
                doUploadFile(f);
            }
        }, "MusicMod-Upload");
        thread.setDaemon(true);
        thread.start();
    }

    private static void doUploadFile(File file) {
        try {
            if (!file.isFile()) {
                notifyMc(Lang.tr("§c[一起听] 文件不存在: ") + file.getAbsolutePath());
                return;
            }
            String fileName = file.getName();
            long fileLength = file.length();
            if (fileLength <= 0) {
                notifyMc(Lang.tr("§c[一起听] 文件为空: ") + fileName);
                return;
            }

            // 大小限制（与服务器一致 100MB）
            if (fileLength > 100L * 1024 * 1024) {
                notifyMc(Lang.tr("§c[一起听] 文件超过 100MB 上限: ") + fileName);
                return;
            }

            // 扩展名校验
            String ext = getExtension(fileName);
            if (!MusicLibrary.SUPPORTED_EXTENSIONS.contains(ext)) {
                notifyMc(Lang.tr("§c[一起听] 不支持的文件格式: .") + ext);
                return;
            }

            String uploadName = taggedUploadName(file, fileName, ext);
            notifyMc(Lang.tr("§e[一起听] 正在上传: ") + uploadName + " ...");

            byte[] fileData = Files.readAllBytes(file.toPath());
            int totalChunks = (int) Math.ceil((double) fileData.length / C2SUploadSongPacket.MAX_CHUNK_SIZE);
            if (totalChunks <= 0) {
                notifyMc(Lang.tr("§c[一起听] 文件为空，无法上传: ") + fileName);
                return;
            }

            long transferStart = System.nanoTime();
            long sentBytes = 0;
            for (int i = 0; i < totalChunks; i++) {
                int start = i * C2SUploadSongPacket.MAX_CHUNK_SIZE;
                int end = Math.min(start + C2SUploadSongPacket.MAX_CHUNK_SIZE, fileData.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(fileData, start, chunk, 0, chunk.length);

                boolean lastChunk = (i == totalChunks - 1);
                NetworkHandler.sendToServer(new C2SUploadSongPacket(
                        uploadName, i, totalChunks, chunk, lastChunk));
                sentBytes += chunk.length;

                // 可选限速：默认 2 MB/s，避免上传把服务器 tick 和带宽占满
                if (MusicModConfig.isTransferLimitEnabled()) {
                    long targetNs = sentBytes * 1_000_000_000L / UPLOAD_BYTES_PER_SECOND;
                    long elapsedNs = System.nanoTime() - transferStart;
                    if (targetNs > elapsedNs) {
                        Thread.sleep((targetNs - elapsedNs) / 1_000_000L);
                    }
                }
            }
            // 最终结果由 S2CUploadResultPacket 通知
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyMc(Lang.tr("§c[一起听] 上传被中断"));
        } catch (Throwable t) {
            notifyMc(Lang.tr("§c[一起听] 上传失败: ") + t.getMessage());
        }
    }

    /**
     * 在 MC 客户端线程显示聊天消息
     */
    private static void notifyMc(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    /** 按音频标签生成「歌手 - 歌曲名.扩展名」；标签缺失时保留原文件名。 */
    private static String taggedUploadName(File file, String fallback, String ext) {
        try {
            SongTags tags = TagReader.read(file);
            String artist = sanitizeTag(tags.getArtistOrEmpty());
            String title = sanitizeTag(tags.title);
            String base;
            if (!artist.isEmpty() && !title.isEmpty()) {
                base = artist + " - " + title;
            } else if (!title.isEmpty()) {
                base = title;
            } else if (!artist.isEmpty()) {
                base = artist;
            } else {
                return fallback;
            }
            if (base.length() > 120) base = base.substring(0, 120).trim();
            if (base.isEmpty()) return fallback;
            return base + "." + ext;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String sanitizeTag(String text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) b.append('_');
            else b.append(c);
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }
}