package com.musicmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 客户端：接收服务器上传结果通知，显示在聊天栏。
 * 注意：此类仅被客户端调用（S2CUploadResultPacket.handle 在客户端线程执行），
 * 符合 dist 隔离规则。
 */
public class ClientUploadNotifier {

    public static void onUploadResult(boolean success, String fileName, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String text;
        if (success) {
            text = "§a[一起听] §f上传成功: " + fileName;
            if (message != null && !message.isEmpty()) {
                text += " §7(" + message + ")";
            }
        } else {
            text = "§c[一起听] §f上传失败: " + fileName + " §7- " + message;
        }

        mc.player.displayClientMessage(Component.literal(text), false);
    }
}