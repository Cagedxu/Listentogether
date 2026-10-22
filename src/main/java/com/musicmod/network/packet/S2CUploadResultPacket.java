package com.musicmod.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：通知上传结果。
 * success=true 表示上传完成并已加入歌曲库，否则 message 中为失败原因。
 */
public class S2CUploadResultPacket {
    private final boolean success;
    private final String fileName;
    private final String message;

    public S2CUploadResultPacket(boolean success, String fileName, String message) {
        this.success = success;
        this.fileName = fileName;
        this.message = message;
    }

    public S2CUploadResultPacket(FriendlyByteBuf buf) {
        this.success = buf.readBoolean();
        this.fileName = buf.readUtf(256);
        this.message = buf.readUtf(512);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(fileName, 256);
        buf.writeUtf(message, 512);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            com.musicmod.client.ClientUploadNotifier.onUploadResult(success, fileName, message);
        });
        ctx.get().setPacketHandled(true);
    }

    /** 服务器端发送结果的便捷方法 */
    public static void sendTo(ServerPlayer player, boolean success, String fileName, String message) {
        com.musicmod.network.NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CUploadResultPacket(success, fileName, message));
    }
}