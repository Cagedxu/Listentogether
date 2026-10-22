package com.musicmod.client;

import com.musicmod.audio.MusicLibrary;
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

    /**
     * 打开上传入口：正常情况弹出系统文件选择器；无头环境回退到游戏内路径输入界面。
     */
    public static void openUploadDialog() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!isOp()) {
            mc.player.displayClientMessage(Component.literal("§c[一起听] 需要管理员权限才能上传音乐"), false);
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

            notifyMc("§7[一起听] 正在打开文件选择器…");

            // 创建不可见属主窗口（UTILITY 类型不占任务栏），比 null 父级更可靠
            Frame owner = new Frame();
            owner.setType(Frame.Type.UTILITY);
            owner.setUndecorated(true);
            owner.setSize(0, 0);

            // 使用 AWT FileDialog（系统原生文件选择器）
            FileDialog fd = new FileDialog(owner, "选择要上传的音乐文件", FileDialog.LOAD);
            fd.setMultipleMode(false);
            try { fd.setAlwaysOnTop(true); } catch (Throwable ignored) {}

            // 辅助线程：对话框显示后延迟强制置顶，避免被游戏窗口遮挡
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

            String fileName = fd.getFile();
            String dirName = fd.getDirectory();
            fd.dispose();
            owner.dispose();

            if (fileName == null || dirName == null) {
                return; // 用户取消
            }
            uploadLocalFile(new File(dirName, fileName));
        } catch (HeadlessException he) {
            // 无头模式：回退到游戏内路径输入界面
            notifyMc("§e[一起听] 系统文件选择器不可用，已切换到路径输入界面");
            openPathInputScreen();
        } catch (Throwable t) {
            notifyMc("§e[一起听] 无法打开系统文件选择器，已切换到路径输入界面: " + t.getMessage());
            openPathInputScreen();
        }
    }

    /**
     * 上传指定本地文件（文件选择器与路径输入界面共用）。
     * 文件读取与分块发送在后台线程执行，不阻塞游戏渲染。
     */
    public static void uploadLocalFile(File file) {
        if (file == null) return;
        Thread thread = new Thread(() -> doUploadFile(file), "MusicMod-Upload");
        thread.setDaemon(true);
        thread.start();
    }

    private static void doUploadFile(File file) {
        try {
            if (!file.isFile()) {
                notifyMc("§c[一起听] 文件不存在: " + file.getAbsolutePath());
                return;
            }
            String fileName = file.getName();
            long fileLength = file.length();
            if (fileLength <= 0) {
                notifyMc("§c[一起听] 文件为空: " + fileName);
                return;
            }

            // 大小限制（与服务器一致 100MB）
            if (fileLength > 100L * 1024 * 1024) {
                notifyMc("§c[一起听] 文件超过 100MB 上限: " + fileName);
                return;
            }

            // 扩展名校验
            String ext = getExtension(fileName);
            if (!MusicLibrary.SUPPORTED_EXTENSIONS.contains(ext)) {
                notifyMc("§c[一起听] 不支持的文件格式: ." + ext);
                return;
            }

            notifyMc("§e[一起听] 正在上传: " + fileName + " ...");

            byte[] fileData = Files.readAllBytes(file.toPath());
            int totalChunks = (int) Math.ceil((double) fileData.length / C2SUploadSongPacket.MAX_CHUNK_SIZE);
            if (totalChunks <= 0) {
                notifyMc("§c[一起听] 文件为空，无法上传: " + fileName);
                return;
            }

            for (int i = 0; i < totalChunks; i++) {
                int start = i * C2SUploadSongPacket.MAX_CHUNK_SIZE;
                int end = Math.min(start + C2SUploadSongPacket.MAX_CHUNK_SIZE, fileData.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(fileData, start, chunk, 0, chunk.length);

                boolean lastChunk = (i == totalChunks - 1);
                NetworkHandler.sendToServer(new C2SUploadSongPacket(
                        fileName, i, totalChunks, chunk, lastChunk));

                // 小延迟避免网络拥塞（每块 30KB，约 10ms 间隔足够）
                if (!lastChunk) {
                    Thread.sleep(10);
                }
            }
            // 最终结果由 S2CUploadResultPacket 通知
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyMc("§c[一起听] 上传被中断");
        } catch (Throwable t) {
            notifyMc("§c[一起听] 上传失败: " + t.getMessage());
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

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }
}