package com.musicmod.network.packet;

import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务器：管理员上传音乐文件（分块传输）。
 * 每块数据不超过 30000 字节，服务器按序拼接后保存到歌曲目录。
 * 仅 OP 可上传，服务器端校验权限与文件扩展名。
 */
public class C2SUploadSongPacket {
    public static final int MAX_CHUNK_SIZE = 30000;

    private final String fileName;     // 完整文件名（含扩展名）
    private final int chunkIndex;      // 当前块序号（从0开始）
    private final int totalChunks;     // 总块数
    private final byte[] data;         // 数据块
    private final boolean lastChunk;   // 是否为最后一块

    public C2SUploadSongPacket(String fileName, int chunkIndex, int totalChunks,
                               byte[] data, boolean lastChunk) {
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
        this.lastChunk = lastChunk;
    }

    public C2SUploadSongPacket(FriendlyByteBuf buf) {
        this.fileName = buf.readUtf(256);
        this.chunkIndex = buf.readVarInt();
        this.totalChunks = buf.readVarInt();
        this.data = buf.readByteArray();
        this.lastChunk = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(fileName, 256);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        buf.writeBoolean(lastChunk);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                MusicServerManager.getInstance().onUploadChunkReceived(
                        player, fileName, chunkIndex, totalChunks, data, lastChunk);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}