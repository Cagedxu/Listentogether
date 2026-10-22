package com.musicmod.network.packet;

import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：分块传输歌曲文件数据。
 * 每块数据不超过 30000 字节，避免超过 Forge 网络包大小限制。
 */
public class S2CSongDataPacket {
    public static final int MAX_CHUNK_SIZE = 30000;

    private final String songName;    // 歌曲名
    private final String fileName;    // 完整文件名
    private final String md5;         // 文件MD5
    private final int chunkIndex;     // 当前块序号（从0开始）
    private final int totalChunks;    // 总块数
    private final byte[] data;        // 数据块
    private final boolean lastChunk;  // 是否为最后一块
    private final long serverElapsedMs; // 服务器当前播放进度（毫秒），客户端据此对齐播放偏移

    public S2CSongDataPacket(String songName, String fileName, String md5,
                             int chunkIndex, int totalChunks, byte[] data, boolean lastChunk,
                             long serverElapsedMs) {
        this.songName = songName;
        this.fileName = fileName;
        this.md5 = md5;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
        this.lastChunk = lastChunk;
        this.serverElapsedMs = serverElapsedMs;
    }

    public S2CSongDataPacket(FriendlyByteBuf buf) {
        this.songName = buf.readUtf(256);
        this.fileName = buf.readUtf(256);
        this.md5 = buf.readUtf(64);
        this.chunkIndex = buf.readVarInt();
        this.totalChunks = buf.readVarInt();
        this.data = buf.readByteArray();
        this.lastChunk = buf.readBoolean();
        this.serverElapsedMs = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(songName, 256);
        buf.writeUtf(fileName, 256);
        buf.writeUtf(md5, 64);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        buf.writeBoolean(lastChunk);
        buf.writeLong(serverElapsedMs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onSongDataReceived(
                            songName, fileName, md5, chunkIndex, totalChunks, data, lastChunk, serverElapsedMs));
        });
        ctx.get().setPacketHandled(true);
    }

    public String getSongName() { return songName; }
    public String getFileName() { return fileName; }
    public String getMd5() { return md5; }
    public int getChunkIndex() { return chunkIndex; }
    public int getTotalChunks() { return totalChunks; }
    public byte[] getData() { return data; }
    public boolean isLastChunk() { return lastChunk; }
    public long getServerElapsedMs() { return serverElapsedMs; }
}