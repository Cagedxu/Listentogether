package com.musicmod.network.packet;

import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务器：请求某首歌曲的文件数据（客户端无缓存时发送）。
 */
public class C2SRequestSongDataPacket {
    private final String songName;    // 歌曲名
    private final long offsetMs;      // 播放偏移（传输完成后从此位置开始播放）

    public C2SRequestSongDataPacket(String songName, long offsetMs) {
        this.songName = songName;
        this.offsetMs = offsetMs;
    }

    public C2SRequestSongDataPacket(FriendlyByteBuf buf) {
        this.songName = buf.readUtf(256);
        this.offsetMs = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(songName, 256);
        buf.writeLong(offsetMs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                MusicServerManager.getInstance().onSongDataRequest(player, songName, offsetMs);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getSongName() { return songName; }
    public long getOffsetMs() { return offsetMs; }
}